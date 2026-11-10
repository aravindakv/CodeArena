package dev.codearena.problem;

import dev.codearena.codegen.Signature;
import dev.codearena.problem.api.ExampleInputs;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExampleInputsTest {
    private final Signature sig = new Signature("pairWithTargetSum",
            List.of(new Signature.Param("nums", "int[]"), new Signature.Param("target", "int")), "int[]", "JSON");

    @Test
    void convertsNamedExampleToJson() {
        assertThat(ExampleInputs.toJson("nums = [3,1,4,6], target = 7", sig))
                .isEqualTo("{\"nums\":[3,1,4,6],\"target\":7}");
    }

    @Test
    void acceptsPositionalValuesAndStringsWithCommas() {
        var s = new Signature("f", List.of(new Signature.Param("s", "string"), new Signature.Param("k", "int")), "int", "EXACT");
        assertThat(ExampleInputs.toJson("\"a,b\", 2", s)).isEqualTo("{\"s\":\"a,b\",\"k\":2}");
    }

    @Test
    void rejectsWrongArity() {
        assertThatThrownBy(() -> ExampleInputs.toJson("[1,2]", sig)).isInstanceOf(IllegalArgumentException.class);
    }
}