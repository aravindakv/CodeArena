"""
SQS worker. One message per seed from problem-service (POST /v1/admin/ingestion/runs, ADMIN only):
  {"runId": "...", "seed": {"id": "001", "topic": "sliding window", "difficulty": "MEDIUM"}}

    cd ingestion && uv run codearena-ingest          (or: python -m codearena_ingest.worker)
"""
import json
import logging
import signal
import sys

from .config import CONFIG, key_source
from .llm import make_llm
from .pipeline import Duplicate, Pipeline, VerificationError
from .rag import VectorStore
from .sandbox import Sandbox
from .store import Store

log = logging.getLogger("ingest")
_running = True


def sync_vectors(store: Store, vectors: VectorStore) -> int:
    """Make sure every existing problem is searchable (RAG context and duplicate detection)."""
    known, added = vectors.known_slugs(), 0
    for doc in store.all_problems():
        if doc["slug"] not in known:
            vectors.add(doc["slug"], doc["title"], doc["difficulty"], doc["statement"], doc.get("tags", []))
            added += 1
    return added


def main():
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    uses_openai = CONFIG.llm_provider == "openai" or CONFIG.embeddings == "openai"
    if uses_openai:
        log.info("OpenAI key: %s", key_source("OPENAI_API_KEY"))
    try:
        return run()
    except Exception as e:
        if uses_openai and type(e).__name__ == "AuthenticationError":
            raise SystemExit(f"OpenAI rejected the API key (401). The worker used {key_source('OPENAI_API_KEY')}.\n"
                             "Create a new key at https://platform.openai.com/api-keys, put it in .env as "
                             "OPENAI_API_KEY=sk-..., and if the key came from your shell run: unset OPENAI_API_KEY")
        raise


def run():
    store, sandbox, vectors = Store(), Sandbox(), VectorStore()
    log.info("LLM provider=%s model=%s embeddings=%s verify=%s", CONFIG.llm_provider, CONFIG.llm_model,
             CONFIG.embeddings, ",".join(CONFIG.verify_langs))
    log.info("vector store: %d problems added", sync_vectors(store, vectors))
    pipeline = Pipeline(make_llm(), sandbox, store, vectors)
    queue_url = store.sqs.get_queue_url(QueueName=CONFIG.queue)["QueueUrl"]

    def stop(*_):
        global _running
        _running = False
        log.info("stopping after the current message")
    signal.signal(signal.SIGINT, stop)
    signal.signal(signal.SIGTERM, stop)

    log.info("waiting for ingestion jobs on %s", CONFIG.queue)
    while _running:
        res = store.sqs.receive_message(QueueUrl=queue_url, MaxNumberOfMessages=1, WaitTimeSeconds=10)
        for m in res.get("Messages", []):
            body = json.loads(m["Body"])
            run_id, seed = body["runId"], body["seed"]
            try:
                slug = pipeline.process(run_id, seed)
                log.info("seed %s/%s -> %s ready for review", run_id, seed["id"], slug)
            except Duplicate as e:
                store.stage(run_id, seed["id"], "DUPLICATE", error=str(e))
            except (VerificationError, ValueError) as e:
                store.stage(run_id, seed["id"], "FAILED", error=str(e))
            except Exception as e:   # infrastructure problem: leave the message for a retry (then the DLQ)
                log.exception("seed %s/%s crashed; it will be retried", run_id, seed["id"])
                store.stage(run_id, seed["id"], "FAILED", error=f"worker error, will retry: {e}")
                continue
            store.sqs.delete_message(QueueUrl=queue_url, ReceiptHandle=m["ReceiptHandle"])
    return 0


if __name__ == "__main__":
    sys.exit(main())