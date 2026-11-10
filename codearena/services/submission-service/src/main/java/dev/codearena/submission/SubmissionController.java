package dev.codearena.submission;

import com.github.f4b6a3.ulid.UlidCreator;
import tools.jackson.databind.json.JsonMapper;
import dev.codearena.codegen.Lang;
import dev.codearena.common.events.Queues;
import dev.codearena.common.events.SubmissionCreated;
import dev.codearena.common.web.ApiException;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import software.amazon.awssdk.services.s3.S3Client;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Accepts code, stores it, queues it, returns 202 + an id. Nothing here runs code: the judge does,
 * on its own hosts, and writes the verdict onto the submission item. Clients poll GET /{id}.
 */
@RestController
@RequestMapping("/v1/submissions")
class SubmissionController {
    private final ProblemClient problems;
    private final ContestGate contests;
    private final SubmissionRepository repo;
    private final S3Client s3;
    private final SqsTemplate sqs;
    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final String bucket;
    private final int maxCodeBytes;

    SubmissionController(ProblemClient problems, ContestGate contests, SubmissionRepository repo, S3Client s3,
                         SqsTemplate sqs, StringRedisTemplate redis, JsonMapper json,
                         @Value("${codearena.submissions-bucket}") String bucket,
                         @Value("${codearena.max-code-bytes}") int maxCodeBytes) {
        this.problems = problems;
        this.contests = contests;
        this.repo = repo;
        this.s3 = s3;
        this.sqs = sqs;
        this.redis = redis;
        this.json = json;
        this.bucket = bucket;
        this.maxCodeBytes = maxCodeBytes;
    }

    record SubmitRequest(@NotBlank String slug, @NotBlank String lang, @NotBlank String code,
                         @Pattern(regexp = "RUN|SUBMIT") String mode, String contestId, String customInput) {}

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, String> submit(@Valid @RequestBody SubmitRequest r, @AuthenticationPrincipal Jwt jwt,
                               @RequestHeader(value = "Idempotency-Key", required = false) String idemKey) {
        String userId = jwt.getSubject();
        String idemRedisKey = idemKey == null ? null : "idem:" + userId + ":" + idemKey;
        if (idemRedisKey != null) {
            String previous = redis.opsForValue().get(idemRedisKey);
            if (previous != null) return Map.of("submissionId", previous);   // client retried: same answer
        }
        if (r.code().getBytes(StandardCharsets.UTF_8).length > maxCodeBytes)
            throw ApiException.badRequest("CODE_TOO_LARGE", "code is limited to " + maxCodeBytes / 1024 + " KB");
        Lang lang = Lang.of(r.lang());
        String mode = r.mode() == null ? "SUBMIT" : r.mode();

        var meta = problems.meta(r.slug());
        if (!"PUBLISHED".equals(meta.status()) && !isAdmin(jwt)) throw ApiException.notFound("problem");
        if (r.customInput() != null && !r.customInput().isBlank()) validateCustomInput(r.customInput());

        long contestStart = 0;
        if (r.contestId() != null && !r.contestId().isBlank()) {
            if ("RUN".equals(mode)) throw ApiException.badRequest("RUN_IN_CONTEST", "use RUN without contestId");
            contestStart = contests.requireOpen(r.contestId(), userId, r.slug());
        }

        String id = UlidCreator.getMonotonicUlid().toString();
        s3.putObject(b -> b.bucket(bucket).key("src/" + id), software.amazon.awssdk.core.sync.RequestBody.fromString(r.code(), StandardCharsets.UTF_8));
        String contestId = contestStart > 0 ? r.contestId() : null;
        repo.createPending(id, userId, r.slug(), lang.id, mode, contestId);

        var event = new SubmissionCreated(id, userId, r.slug(), lang.id, mode, contestId, contestStart,
                meta.testsVersion(), meta.limits(), meta.signature().compare(), meta.difficulty(), meta.tags(),
                "RUN".equals(mode) ? r.customInput() : null, System.currentTimeMillis());
        sqs.send(contestId != null ? Queues.CONTEST_SUBMISSIONS : Queues.PRACTICE_SUBMISSIONS, event);

        if (idemRedisKey != null) redis.opsForValue().set(idemRedisKey, id, Duration.ofHours(1));
        return Map.of("submissionId", id);
    }

    @GetMapping("/{id}")
    SubmissionRepository.Submission get(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        return repo.find(jwt.getSubject(), id).orElseThrow(() -> ApiException.notFound("submission"));
    }

    @GetMapping
    List<SubmissionRepository.Submission> mine(@AuthenticationPrincipal Jwt jwt,
                                                @RequestParam(required = false) String slug,
                                                @RequestParam(defaultValue = "20") int limit) {
        return repo.recent(jwt.getSubject(), slug, Math.min(limit, 50));
    }

    private void validateCustomInput(String line) {
        if (line.length() > 8192) throw ApiException.badRequest("INPUT_TOO_LARGE", "custom input is limited to 8 KB");
        try {
            if (!json.readTree(line).isObject()) throw new IllegalArgumentException();
        } catch (Exception e) {
            throw ApiException.badRequest("BAD_CUSTOM_INPUT", "custom input must be a JSON object, e.g. {\"nums\":[1,2],\"target\":3}");
        }
    }

    private static boolean isAdmin(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        return roles != null && roles.contains("ADMIN");
    }
}