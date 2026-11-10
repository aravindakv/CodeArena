package dev.codearena.contest;

import com.github.f4b6a3.ulid.UlidCreator;
import dev.codearena.common.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1")
class ContestController {
    private final ContestRepository contests;
    private final Leaderboard leaderboard;
    private final LeaderboardJobs jobs;

    ContestController(ContestRepository contests, Leaderboard leaderboard, LeaderboardJobs jobs) {
        this.contests = contests;
        this.leaderboard = leaderboard;
        this.jobs = jobs;
    }

    record ContestView(String id, String title, String description, Instant startAt, Instant endAt, String phase,
                       List<String> problems, boolean frozen, boolean finalized) {}

    record CreateContest(@NotBlank String title, String description, @NotNull Instant startAt,
                         @NotNull Instant endAt, @NotEmpty List<String> problems) {}

    // ---------------- public ----------------

    @GetMapping("/contests")
    List<ContestView> list() {
        Instant now = Instant.now();
        return contests.all().stream().map(c -> view(c, now, false)).toList();
    }

    @GetMapping("/contests/{id}")
    ContestView get(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        boolean admin = jwt != null && String.valueOf(jwt.getClaimAsStringList("roles")).contains("ADMIN");
        return view(find(id), Instant.now(), admin);
    }

    @PostMapping("/contests/{id}/register")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void register(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        Contest c = find(id);
        if (!Instant.now().isBefore(c.endAt())) throw ApiException.badRequest("CONTEST_ENDED", "the contest has ended");
        String name = jwt.getClaimAsString("name") == null ? jwt.getSubject() : jwt.getClaimAsString("name");
        contests.register(id, jwt.getSubject(), name);
        leaderboard.register(id, jwt.getSubject(), name);
    }

    /** The cached snapshot, returned as-is: constant cost however many people watch. */
    @GetMapping(value = "/contests/{id}/leaderboard", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> leaderboard(@PathVariable String id) {
        String snap = leaderboard.snapshot(id);
        if (snap == null) snap = "{\"contestId\":\"" + id + "\",\"rows\":[],\"participants\":0}";
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofSeconds(5)).cachePublic()).body(snap);
    }

    @GetMapping("/contests/{id}/leaderboard/me")
    Map<String, Object> me(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        return leaderboard.rankOf(id, jwt.getSubject());
    }

    // ---------------- admin ----------------

    @PostMapping("/admin/contests")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    ContestView create(@Valid @RequestBody CreateContest req, @AuthenticationPrincipal Jwt admin) {
        if (!req.endAt().isAfter(req.startAt())) throw ApiException.badRequest("BAD_WINDOW", "endAt must be after startAt");
        Contest c = new Contest(UlidCreator.getMonotonicUlid().toString(), req.title(), req.description(),
                req.startAt(), req.endAt(), req.problems(), false, false, admin.getSubject());
        contests.create(c);
        leaderboard.publishMeta(c);
        return view(c, Instant.now(), true);
    }

    @PostMapping("/admin/contests/{id}/freeze")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void freeze(@PathVariable String id) throws Exception {
        Contest c = find(id);
        leaderboard.saveSnapshot(id, leaderboard.buildSnapshot(c), true);   // keep the frozen board, no TTL
        contests.setFlag(id, "frozen", true);
    }

    @PostMapping("/admin/contests/{id}/unfreeze")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void unfreeze(@PathVariable String id) {
        find(id);
        contests.setFlag(id, "frozen", false);
    }

    @PostMapping("/admin/contests/{id}/finalize")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void finalizeNow(@PathVariable String id) {
        jobs.finalizeContest(find(id));
    }

    private Contest find(String id) {
        return contests.find(id).orElseThrow(() -> ApiException.notFound("contest"));
    }

    private static ContestView view(Contest c, Instant now, boolean admin) {
        String phase = c.phase(now);
        List<String> problems = admin || !"UPCOMING".equals(phase) ? c.problems() : List.of();   // hidden until start
        return new ContestView(c.id(), c.title(), c.description(), c.startAt(), c.endAt(), phase, problems,
                c.frozen(), c.finalized());
    }
}