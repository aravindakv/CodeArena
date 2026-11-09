#!/usr/bin/env python3
"""
Create (idempotently) everything the services expect in the local infrastructure:
DynamoDB tables + TTL, S3 buckets. SQS queues are pre-created by deploy/compose/elasticmq.conf.

    scripts/py.sh scripts/bootstrap.py
"""
import os
import sys
import time

import boto3
from botocore.exceptions import ClientError, EndpointConnectionError

REGION = os.environ.get("AWS_REGION", "ap-south-1")
CREDS = {"aws_access_key_id": os.environ.get("AWS_ACCESS_KEY_ID", "local"),
             "aws_secret_access_key": os.environ.get("AWS_SECRET_ACCESS_KEY", "local"), "region_name": REGION}
ddb = boto3.client("dynamodb", endpoint_url=os.environ.get("DYNAMO_ENDPOINT", "http://localhost:9700"), **CREDS)
s3 = boto3.client("s3", endpoint_url=os.environ.get("S3_ENDPOINT", "http://localhost:9703"), **CREDS)
sqs = boto3.client("sqs", endpoint_url=os.environ.get("SQS_ENDPOINT", "http://localhost:9702"), **CREDS)

PK_SK = [{"AttributeName": "PK", "AttributeType": "S"}, {"AttributeName": "SK", "AttributeType": "S"}]
KEYS = [{"AttributeName": "PK", "KeyType": "HASH"}, {"AttributeName": "SK", "KeyType": "RANGE"}]

# table name -> (extra attribute definitions, GSIs, TTL attribute or None)
TABLES = {
    "Users": ([], [], None),
    "Problems": (
        [{"AttributeName": "listPk", "AttributeType": "S"}, {"AttributeName": "number", "AttributeType": "S"}],
        [{"IndexName": "byNumber",
          "KeySchema": [{"AttributeName": "listPk", "KeyType": "HASH"}, {"AttributeName": "number", "KeyType": "RANGE"}],
          "Projection": {"ProjectionType": "ALL"}}],
        None),
    "Submissions": ([], [], "ttl"),
    "Contests": ([], [], None),
    "UserStats": ([], [], None),
    "UserSolved": ([], [], None),
    "AuditLog": ([], [], None),
    "IngestionRuns": ([], [], "ttl"),
}
BUCKETS = ["codearena-tests", "codearena-submissions"]
QUEUES = ["contest-submissions", "practice-submissions", "verdict-events", "contest-verdicts", "ingestion-jobs"]


def wait_for(name, fn, attempts=30):
    for i in range(attempts):
        try:
            return fn()
        except (EndpointConnectionError, ConnectionError, ClientError) as e:
            if isinstance(e, ClientError) and i > 3:
                raise
            print(f"  waiting for {name}... ({e.__class__.__name__})")
            time.sleep(2)
    sys.exit(f"{name} is not reachable; is `docker compose ... up` running?")


def create_tables():
    existing = set(wait_for("DynamoDB", lambda: ddb.list_tables()["TableNames"]))
    for name, (extra_attrs, gsis, ttl_attr) in TABLES.items():
        if name in existing:
            print(f"table {name}: exists")
            continue
        args = {"TableName": name, "BillingMode": "PAY_PER_REQUEST",
                    "AttributeDefinitions": PK_SK + extra_attrs, "KeySchema": KEYS}
        if gsis:
            args["GlobalSecondaryIndexes"] = gsis
        ddb.create_table(**args)
        if ttl_attr:
            ddb.update_time_to_live(TableName=name,
                                    TimeToLiveSpecification={"Enabled": True, "AttributeName": ttl_attr})
        print(f"table {name}: created")


def create_buckets():
    try:
        have = {b["Name"] for b in wait_for("S3", lambda: s3.list_buckets().get("Buckets", []))}
    except ClientError as e:
        if e.response.get("Error", {}).get("Code") == "InternalError":
            sys.exit("S3Mock answered 500 InternalError: it cannot write its store. Run\n"
                     "  docker compose -f deploy/compose/docker-compose.yml logs s3 | tail -30\n"
                     "and see chapter 16 (S3Mock InternalError): recreate the s3 container and its volume.")
        raise
    for b in BUCKETS:
        if b not in have:
            s3.create_bucket(Bucket=b, CreateBucketConfiguration={"LocationConstraint": REGION})
        print(f"bucket {b}: ok")


def check_queues():
    for q in QUEUES:
        wait_for("SQS", lambda: sqs.get_queue_url(QueueName=q))
        print(f"queue {q}: ok")


if __name__ == "__main__":
    create_tables()
    create_buckets()
    check_queues()
    print("bootstrap complete")