package dev.codearena.judge;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ComparatorsTest {

    @Test
    void jsonModeTreatsIntAndDoubleAsEqualButNotDifferentValues() {
        assertThat(Comparators.equal("JSON", "[1,2]", "[1.0, 2]")).isTrue();
        assertThat(Comparators.equal("JSON", "[1,2]", "[2,1]")).isFalse();
        assertThat(Comparators.equal("JSON", "true", "true")).isTrue();
        assertThat(Comparators.equal("JSON", "\"abc\"", "\"abd\"")).isFalse();
    }

    @Test
    void unorderedIgnoresOrderOnly() {
        assertThat(Comparators.equal("UNORDERED_ARRAY", "[[1,2],[3]]", "[[3],[1,2]]")).isTrue();
        assertThat(Comparators.equal("UNORDERED_ARRAY", "[1,2,2]", "[2,1,1]")).isFalse();
    }

    @Test
    void floatModeAllowsSmallErrors() {
        assertThat(Comparators.equal("FLOAT_1E6", "0.333333333", "0.3333334")).isTrue();
        assertThat(Comparators.equal("FLOAT_1E6", "1000000", "1000000.5")).isTrue();     // relative
        assertThat(Comparators.equal("FLOAT_1E6", "0.5", "0.51")).isFalse();
    }

    @Test
    void garbageOutputIsWrongNotACrash() {
        assertThat(Comparators.equal("JSON", "[1]", "Traceback (most recent call last)")).isFalse();
        assertThat(Comparators.equal("JSON", "[1]", null)).isFalse();
    }
}