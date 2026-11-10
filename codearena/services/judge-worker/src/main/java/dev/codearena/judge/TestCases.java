package dev.codearena.judge;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Hidden tests from S3, cached on the judge host's disk by slug + version (versions are immutable,
 * so the cache never needs invalidating). Inputs and expected outputs are split here: only inputs
 * are ever mounted into the sandbox.
 */
@Component
class TestCases {
    private final S3Client s3;
    private final JudgeProps props;
    private final JsonMapper json = JsonMapper.builder().build();

    TestCases(S3Client s3, JudgeProps props) {
        this.s3 = s3;
        this.props = props;
    }

    record Cases(List<String> inputs, List<String> expected) {}

    Cases load(String slug, int version) throws IOException {
        Path cached = Path.of(props.workRoot(), "tests-cache", slug, "v" + version + ".jsonl");
        if (!Files.exists(cached)) {
            Files.createDirectories(cached.getParent());
            Path tmp = Files.createTempFile(cached.getParent(), "dl-", ".part");
            try (var in = s3.getObject(b -> b.bucket(props.testsBucket()).key(slug + "/v" + version + "/cases.jsonl"))) {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(tmp, cached, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
        List<String> inputs = new ArrayList<>(), expected = new ArrayList<>();
        for (String line : Files.readAllLines(cached, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            JsonNode n = json.readTree(line);
            inputs.add(json.writeValueAsString(n.get("input")));
            expected.add(json.writeValueAsString(n.get("output")));
        }
        return new Cases(inputs, expected);
    }
}