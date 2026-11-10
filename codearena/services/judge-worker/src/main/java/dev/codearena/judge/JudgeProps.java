package dev.codearena.judge;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties("codearena.judge")
public record JudgeProps(String runtime, String workRoot, String problemUrl, String testsBucket,
                         String submissionsBucket, int maxOutputBytes, List<String> verdictQueues) {
}