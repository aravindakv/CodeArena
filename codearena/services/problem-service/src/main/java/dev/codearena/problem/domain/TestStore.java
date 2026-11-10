package dev.codearena.problem.domain;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import dev.codearena.common.web.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.util.Arrays;

/**
 * Hidden test cases in S3: <bucket>/<slug>/v<version>/cases.jsonl
 * Each line: {"input": {"<param>": value, ...}, "output": value}
 * A new upload always creates a new version, so a running judge never sees a half-written file.
 */
@Component
public class TestStore {
    private final S3Client s3;
    private final JsonMapper json;
    private final String bucket;

    public TestStore(S3Client s3, JsonMapper json, @Value("${codearena.tests-bucket}") String bucket) {
        this.s3 = s3;
        this.json = json;
        this.bucket = bucket;
    }

    public static String keyFor(String slug, int version) {
        return slug + "/v" + version + "/cases.jsonl";
    }

    /** Validates every line against the signature's parameter names and uploads. Returns the case count. */
    public int upload(String slug, int version, String jsonl, java.util.List<String> paramNames) {
        String[] lines = Arrays.stream(jsonl.split("\n")).filter(l -> !l.isBlank()).toArray(String[]::new);
        if (lines.length == 0) throw ApiException.badRequest("NO_TESTS", "at least one test case is required");
        for (int i = 0; i < lines.length; i++) {
            try {
                JsonNode n = json.readTree(lines[i]);
                if (!n.has("input") || !n.has("output")) throw new IllegalArgumentException("needs input and output");
                for (String p : paramNames)
                    if (!n.get("input").has(p)) throw new IllegalArgumentException("input lacks parameter " + p);
            } catch (Exception e) {
                throw ApiException.badRequest("BAD_TEST_CASE", "line " + (i + 1) + ": " + e.getMessage());
            }
        }
        s3.putObject(b -> b.bucket(bucket).key(keyFor(slug, version)).contentType("application/x-ndjson"),
                RequestBody.fromString(String.join("\n", lines) + "\n"));
        return lines.length;
    }

    public boolean exists(String slug, int version) {
        if (version <= 0) return false;
        try {
            s3.headObject(b -> b.bucket(bucket).key(keyFor(slug, version)));
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (software.amazon.awssdk.services.s3.model.S3Exception e) {
            if (e.statusCode() == 404) return false;
            throw e;
        }
    }
}