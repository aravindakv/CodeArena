package dev.codearena.analytics;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class StreaksTest {
    private final LocalDate today = LocalDate.parse("2026-10-11");

    @Test
    void currentStreakIncludesTodayAndLongestIsKept() {
        var days = Set.of("2026-10-01", "2026-10-02", "2026-10-03", "2026-10-04", "2026-10-10", "2026-10-11");
        assertThat(StatsController.streaks(days, today)).containsExactly(2, 4);
    }

    @Test
    void streakSurvivesUntilEndOfNextDay() {
        assertThat(StatsController.streaks(Set.of("2026-10-09", "2026-10-10"), today)).containsExactly(2, 2);
    }

    @Test
    void brokenStreakIsZero() {
        assertThat(StatsController.streaks(Set.of("2026-10-05"), today)).containsExactly(0, 1);
    }

    @Test
    void noActivity() {
        assertThat(StatsController.streaks(Set.of(), today)).containsExactly(0, 0);
    }
}