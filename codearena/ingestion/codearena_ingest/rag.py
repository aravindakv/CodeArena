"""
Retrieval over the existing problem bank (pgvector).
Used twice per seed: (1) fetch similar problems as style/difficulty examples and "do not duplicate" context
for the LLM, (2) reject a generated problem that is too similar to one we already have.
"""
import hashlib
import math
import re

import psycopg
from pgvector.psycopg import register_vector

from .config import CONFIG

DIM = 384


class Embedder:
    """Turns problem text into a DIM-sized vector. Kinds:
    hash      offline, no download; catches near-duplicates only
    fastembed local model (BAAI/bge-small-en-v1.5, 384 dimensions); real semantic search, ~130 MB download
    openai    OpenAI embeddings API (text-embedding-3-small, shortened to 384 dimensions); needs OPENAI_API_KEY
    """
    def __init__(self, kind: str = CONFIG.embeddings, client=None):
        if kind not in ("hash", "fastembed", "openai"):
            raise SystemExit(f"EMBEDDINGS must be hash, fastembed or openai, not {kind!r}")
        self.kind = kind
        self._model = None
        self._client = client
        if kind == "fastembed":
            from fastembed import TextEmbedding   # optional dependency
            self._model = TextEmbedding("BAAI/bge-small-en-v1.5")   # 384 dimensions
        elif kind == "openai" and client is None:
            from openai import OpenAI
            self._client = OpenAI(max_retries=5, timeout=60)

    def embed(self, text: str) -> list[float]:
        if self.kind == "openai":
            # text-embedding-3 models can return shorter vectors; 384 keeps the same table shape as the other kinds.
            res = self._client.embeddings.create(model=CONFIG.openai_embedding_model, input=text, dimensions=DIM)
            return [float(x) for x in res.data[0].embedding]
        if self._model is not None:
            return [float(x) for x in next(iter(self._model.embed([text])))]
        return hash_embed(text)


def hash_embed(text: str) -> list[float]:
    """Offline fallback: hashed bag of words and word pairs, L2-normalised. Good enough to catch near-duplicates."""
    words = re.findall(r"[a-z0-9]+", text.lower())
    vec = [0.0] * DIM
    for token in words + [a + " " + b for a, b in zip(words, words[1:])]:
        h = int(hashlib.md5(token.encode()).hexdigest(), 16)
        vec[h % DIM] += 1.0 if (h >> 9) & 1 else -1.0
    norm = math.sqrt(sum(v * v for v in vec)) or 1.0
    return [v / norm for v in vec]


def problem_text(title: str, statement: str, tags: list[str]) -> str:
    return f"{title}\n{' '.join(tags)}\n{statement}"


class VectorStore:
    def __init__(self, url: str = CONFIG.pg_url, embedder: Embedder | None = None):
        self.embedder = embedder or Embedder()
        # One table per embedding kind: vectors from different models are not comparable, so switching EMBEDDINGS
        # starts an empty table, which the worker fills from DynamoDB at startup (sync_vectors).
        self.table = f"problem_vectors_{self.embedder.kind}"
        self.conn = psycopg.connect(url, autocommit=True)
        self.conn.execute("CREATE EXTENSION IF NOT EXISTS vector")
        register_vector(self.conn)
        self.conn.execute(f"""
            CREATE TABLE IF NOT EXISTS {self.table} (
              slug text PRIMARY KEY, title text NOT NULL, difficulty text NOT NULL, statement text NOT NULL,
              embedding vector({DIM}) NOT NULL)""")
        self.conn.execute(f"CREATE INDEX IF NOT EXISTS {self.table}_hnsw ON {self.table} "
                          "USING hnsw (embedding vector_cosine_ops)")

    def known_slugs(self) -> set[str]:
        return {r[0] for r in self.conn.execute(f"SELECT slug FROM {self.table}").fetchall()}

    def add(self, slug: str, title: str, difficulty: str, statement: str, tags: list[str]):
        import numpy as np
        vec = np.array(self.embedder.embed(problem_text(title, statement, tags)), dtype=np.float32)
        self.conn.execute(
            f"INSERT INTO {self.table} (slug, title, difficulty, statement, embedding) VALUES (%s,%s,%s,%s,%s) "
            "ON CONFLICT (slug) DO UPDATE SET title = EXCLUDED.title, statement = EXCLUDED.statement, "
            "embedding = EXCLUDED.embedding", (slug, title, difficulty, statement, vec))

    def nearest(self, text: str, k: int = 3) -> list[dict]:
        import numpy as np
        vec = np.array(self.embedder.embed(text), dtype=np.float32)
        rows = self.conn.execute(
            "SELECT slug, title, difficulty, statement, 1 - (embedding <=> %s) AS sim "
            f"FROM {self.table} ORDER BY embedding <=> %s LIMIT %s", (vec, vec, k)).fetchall()
        return [{"slug": r[0], "title": r[1], "difficulty": r[2], "statement": r[3], "similarity": float(r[4])} for r in rows]