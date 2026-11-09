package dev.codearena.codegen;

import java.util.List;
import java.util.Map;

/** Maps the neutral type names to each language's spelling. */
public final class Types {
    private Types() {}

    public static final List<String> SUPPORTED =
            List.of("int", "long", "double", "bool", "string", "int[]", "long[]", "double[]", "string[]", "int[][]");

    /** Type as written in a parameter list of the starter code. */
    private static final Map<Lang, Map<String, String>> PARAM = Map.of(
            Lang.JAVA, Map.of("int", "int", "long", "long", "double", "double", "bool", "boolean", "string", "String",
                    "int[]", "int[]", "long[]", "long[]", "double[]", "double[]", "string[]", "String[]", "int[][]", "int[][]"),
            Lang.PYTHON, Map.of("int", "int", "long", "int", "double", "float", "bool", "bool", "string", "str",
                    "int[]", "List[int]", "long[]", "List[int]", "double[]", "List[float]", "string[]", "List[str]",
                    "int[][]", "List[List[int]]"),
            Lang.CPP, Map.of("int", "int", "long", "long long", "double", "double", "bool", "bool", "string", "string",
                    "int[]", "vector<int>&", "long[]", "vector<long long>&", "double[]", "vector<double>&",
                    "string[]", "vector<string>&", "int[][]", "vector<vector<int>>&"),
            Lang.JAVASCRIPT, Map.of("int", "number", "long", "number", "double", "number", "bool", "boolean",
                    "string", "string", "int[]", "number[]", "long[]", "number[]", "double[]", "number[]",
                    "string[]", "string[]", "int[][]", "number[][]"),
            Lang.GO, Map.of("int", "int", "long", "int64", "double", "float64", "bool", "bool", "string", "string",
                    "int[]", "[]int", "long[]", "[]int64", "double[]", "[]float64", "string[]", "[]string",
                    "int[][]", "[][]int"));

    public static void require(String type) {
        if (!SUPPORTED.contains(type)) throw new IllegalArgumentException("unsupported type: " + type);
    }

    /** Parameter spelling, e.g. C++ passes vectors by reference. */
    public static String param(Lang lang, String type) {
        require(type);
        return PARAM.get(lang).get(type);
    }

    /** Value spelling: a local variable or return type (no references). */
    public static String value(Lang lang, String type) {
        return param(lang, type).replace("&", "");
    }
}