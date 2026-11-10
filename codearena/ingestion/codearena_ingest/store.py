"""DynamoDB/S3 writes: run progress for the admin console, and the generated problem as IN_REVIEW."""
import json
import secrets
from datetime import datetime, timezone

import boto3
from botocore.exceptions import ClientError

from .config import CONFIG
from .models import GeneratedProblem


def _clients():
    creds = dict(region_name=CONFIG.region, aws_access_key_id=CONFIG.access_key, aws_secret_access_key=CONFIG.secret_key)
    return (boto3.client("dynamodb", endpoint_url=CONFIG.dynamo_endpoint, **creds),
            boto3.client("s3", endpoint_url=CONFIG.s3_endpoint, **creds),
            boto3.client("sqs", endpoint_url=CONFIG.sqs_endpoint, **creds))


class Store:
    def __init__(self):
        self.ddb, self.s3, self.sqs = _clients()

    def stage(self, run_id: str, seed_id: str, stage: str, slug: str | None = None, error: str | None = None):
        names, values, sets = {"#st": "stage"}, {":st": {"S": stage}, ":u": {"S": datetime.now(timezone.utc).isoformat()}}, ["#st = :st", "updatedAt = :u"]
        if slug:
            sets.append("slug = :sl"); values[":sl"] = {"S": slug}
        if error:
            sets.append("#err = :e"); names["#err"] = "error"; values[":e"] = {"S": error[:900]}
        self.ddb.update_item(TableName="IngestionRuns", Key={"PK": {"S": f"RUN#{run_id}"}, "SK": {"S": f"SEED#{seed_id}"}},
                             UpdateExpression="SET " + ", ".join(sets), ExpressionAttributeNames=names,
                             ExpressionAttributeValues=values)

    def unique_slug(self, slug: str) -> str:
        candidate = slug
        while "Item" in self.ddb.get_item(TableName="Problems", Key={"PK": {"S": f"PROBLEM#{candidate}"}, "SK": {"S": "META"}}):
            candidate = f"{slug[:52]}-{secrets.token_hex(3)}"
        return candidate

    def save_for_review(self, p: GeneratedProblem, cases: list[dict], run_id: str):
        """Tests to S3 (v1), problem to DynamoDB as IN_REVIEW. Publishing is an admin decision in the console."""
        body = "\n".join(json.dumps(c, separators=(",", ":")) for c in cases) + "\n"
        self.s3.put_object(Bucket=CONFIG.tests_bucket, Key=f"{p.slug}/v1/cases.jsonl", Body=body.encode())
        now = datetime.now(timezone.utc).isoformat()
        doc = p.doc()
        try:
            self.ddb.put_item(TableName="Problems", ConditionExpression="attribute_not_exists(PK)", Item={
                "PK": {"S": f"PROBLEM#{p.slug}"}, "SK": {"S": "META"}, "slug": {"S": p.slug}, "title": {"S": p.title},
                "difficulty": {"S": p.difficulty}, "tags": {"S": ",".join(doc["tags"])}, "doc": {"S": json.dumps(doc)},
                "status": {"S": "IN_REVIEW"}, "testsVersion": {"N": "1"}, "source": {"S": f"ai:{run_id}"},
                "createdAt": {"S": now}, "updatedAt": {"S": now}})
        except ClientError as e:
            if e.response["Error"]["Code"] == "ConditionalCheckFailedException":
                raise ValueError(f"slug {p.slug} was taken concurrently") from e
            raise

    def all_problems(self):
        """Every problem (any status) for syncing the vector store."""
        paginator = self.ddb.get_paginator("scan")
        for page in paginator.paginate(TableName="Problems", FilterExpression="SK = :m",
                                       ExpressionAttributeValues={":m": {"S": "META"}}):
            for item in page["Items"]:
                yield json.loads(item["doc"]["S"])