package dev.codearena.judge;

import dev.codearena.codegen.Signature;
import dev.codearena.common.events.Limits;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Judge metadata (signature, visible examples) from problem-service, cached in memory for 5 minutes. */
@Component
class ProblemMetaClient {
    private final RestClient http;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    ProblemMetaClient(JudgeProps props) {
        this.http = RestClient.create(props.problemUrl());
    }

    record JudgeMeta(String slug, String status, int testsVersion, Limits limits, Signature signature,
                     String difficulty, List<String> tags, List<String> visibleInputs, List<String> visibleOutputs) {}

    private record Cached(JudgeMeta meta, long at) {}

    JudgeMeta get(String slug) {
        Cached c = cache.get(slug);
        if (c != null && System.currentTimeMillis() - c.at() < 300_000) return c.meta();
        JudgeMeta meta = http.get().uri("/internal/problems/{slug}/judge-meta", slug).retrieve().body(JudgeMeta.class);
        cache.put(slug, new Cached(meta, System.currentTimeMillis()));
        return meta;
    }
}