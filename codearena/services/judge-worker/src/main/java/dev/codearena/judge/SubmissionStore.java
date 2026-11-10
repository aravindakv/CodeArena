package dev.codearena.judge;

import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static dev.codearena.common.aws.Ddb.*;

/** Reads submitted source from S3 and writes verdicts onto the Submissions item. */
@Component
class SubmissionStore {
    private final DynamoDbClient ddb;
    private final S3Client s3;
    private final JudgeProps props;

    SubmissionStore(DynamoDbClient ddb, S3Client s3, JudgeProps props) {
        this.ddb = ddb;
        this.s3 = s3;
        this.props = props;
    }

    String source(String submissionId) {
        ResponseBytes<GetObjectResponse> b = s3.getObjectAsBytes(r -> r.bucket(props.submissionsBucket()).key("src/" + submissionId));
        return b.asString(StandardCharsets.UTF_8);
    }

    boolean isPending(String userId, String submissionId) {
        var item = ddb.getItem(r -> r.tableName("Submissions").key(key("USER#" + userId, "SUB#" + submissionId))
                .consistentRead(true)).item();
        return has(item) && "PENDING".equals(str(item, "status"));
    }

    /**
     * Writes the verdict only if the submission is still PENDING. Returns false when another worker already
     * finished it (SQS delivers at least once), so the verdict event is published exactly once per submission.
     */
    boolean finish(String userId, String submissionId, Verdict v) {
        Map<String, AttributeValue> values = new HashMap<>();
        values.put(":st", s(v.status()));
        values.put(":rt", n(v.runtimeMs()));
        values.put(":mem", n(v.memoryKb()));
        values.put(":p", n(v.passed()));
        values.put(":t", n(v.total()));
        values.put(":msg", s(nz(v.message())));
        values.put(":fi", s(nz(v.failedInput())));
        values.put(":ex", s(nz(v.expected())));
        values.put(":ac", s(nz(v.actual())));
        values.put(":out", s(nz(v.stdout())));
        values.put(":co", s(nz(v.customOutput())));
        values.put(":at", s(Instant.now().toString()));
        values.put(":pending", s("PENDING"));
        try {
            ddb.updateItem(r -> r.tableName("Submissions").key(key("USER#" + userId, "SUB#" + submissionId))
                    .updateExpression("SET #st = :st, runtimeMs = :rt, memoryKb = :mem, passed = :p, #total = :t, "
                            + "message = :msg, failedInput = :fi, expected = :ex, actual = :ac, stdout = :out, "
                            + "customOutput = :co, judgedAt = :at")
                    .conditionExpression("#st = :pending")
                    .expressionAttributeNames(Map.of("#st", "status", "#total", "total"))
                    .expressionAttributeValues(values));
            return true;
        } catch (ConditionalCheckFailedException e) {
            return false;
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}