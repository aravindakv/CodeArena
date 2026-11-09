package dev.codearena.codegen;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CodeGeneratorTest {
    private final CodeGenerator gen = new CodeGenerator();
    private final Signature sig = new Signature("rev",
            List.of(new Signature.Param("words", "string[]"), new Signature.Param("k", "int")), "string[]", "JSON");

    @Test
    void startersUseEachLanguagesTypes() {
        assertThat(gen.starter(Lang.JAVA, sig)).contains("public String[] rev(String[] words, int k) {");
        assertThat(gen.starter(Lang.PYTHON, sig)).contains("def rev(self, words: List[str], k: int) -> List[str]:");
        assertThat(gen.starter(Lang.CPP, sig)).contains("vector<string> rev(vector<string>& words, int k) {");
        assertThat(gen.starter(Lang.JAVASCRIPT, sig)).contains("@param {string[]} words").contains("var rev = function(words, k) {");
        assertThat(gen.starter(Lang.GO, sig)).contains("func rev(words []string, k int) []string {");
    }

    @Test
    void harnessesCallTheFunctionWithEveryParameterInOrder() {
        assertThat(gen.harness(Lang.JAVA, sig)).contains("new Solution().rev(words, k)");
        assertThat(gen.harness(Lang.PYTHON, sig)).contains("Solution().rev(ca_a[\"words\"], ca_a[\"k\"])");
        assertThat(gen.harness(Lang.CPP, sig)).contains("auto words = ca_j[\"words\"].get<vector<string>>();");
        assertThat(gen.harness(Lang.GO, sig)).contains("var words []string");
    }

    @Test
    void rejectsUnsupportedTypesAndReservedNames() {
        assertThatThrownBy(() -> gen.starter(Lang.JAVA, new Signature("f", List.of(new Signature.Param("m", "map")), "int", "JSON")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> gen.starter(Lang.JAVA, new Signature("f", List.of(new Signature.Param("ca_x", "int")), "int", "JSON")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}