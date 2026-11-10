"""
Give an existing account the ADMIN role (local development only; writes straight to DynamoDB Local).
Use it when the admin account was registered before BOOTSTRAP_ADMIN_EMAIL reached auth-service.

    scripts/py.sh scripts/make_admin.py admin@codearena.local

Log out and in again afterwards: roles are read into the token at login.
"""
import os
import sys

import boto3

email = (sys.argv[1] if len(sys.argv) > 1 else os.environ.get("BOOTSTRAP_ADMIN_EMAIL", "")).strip().lower()
if not email:
    sys.exit("usage: scripts/py.sh scripts/make_admin.py <email>")

ddb = boto3.client("dynamodb", endpoint_url=os.environ.get("DYNAMO_ENDPOINT", "http://localhost:9700"),
                   region_name=os.environ.get("AWS_REGION", "ap-south-1"),
                   aws_access_key_id=os.environ.get("AWS_ACCESS_KEY_ID", "local"),
                   aws_secret_access_key=os.environ.get("AWS_SECRET_ACCESS_KEY", "local"))

guard = ddb.get_item(TableName="Users", Key={"PK": {"S": f"EMAIL#{email}"}, "SK": {"S": "PROFILE"}}).get("Item")
if not guard:
    sys.exit(f"no account with email {email}; register it first (web app or POST /api/v1/auth/register)")
user_id = guard["userId"]["S"]
ddb.update_item(TableName="Users", Key={"PK": {"S": f"USER#{user_id}"}, "SK": {"S": "PROFILE"}},
                UpdateExpression="ADD #r :admin", ExpressionAttributeNames={"#r": "roles"},
                ExpressionAttributeValues={":admin": {"SS": ["ADMIN"]}})
print(f"{email} (user {user_id}) now has the ADMIN role. Log out and log in again.")
