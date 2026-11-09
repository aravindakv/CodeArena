package dev.codearena.codegen;

import java.util.List;

/**
 * Language-neutral function signature of a problem. Starter code and the hidden test harness
 * for every language are generated from this, so a problem is written once.
 *
 * @param function method/function name, e.g. "pairWithTargetSum"
 * @param params   ordered parameters; each name is also the key in the JSON test input
 * @param returns  return type (one of {@link Types#SUPPORTED})
 * @param compare  how outputs are compared: EXACT, JSON, UNORDERED_ARRAY, FLOAT_1E6
 */
public record Signature(String function, List<Param> params, String returns, String compare) {

    public record Param(String name, String type) {}

    public void validate() {
        if (function == null || !function.matches("[a-zA-Z_][a-zA-Z0-9_]*"))
            throw new IllegalArgumentException("invalid function name: " + function);
        if (params == null || params.isEmpty()) throw new IllegalArgumentException("at least one parameter");
        for (Param p : params) {
            if (!p.name().matches("[a-zA-Z][a-zA-Z0-9_]*") || p.name().startsWith("ca_"))
                throw new IllegalArgumentException("invalid parameter name: " + p.name());
            Types.require(p.type());
        }
        Types.require(returns);
    }
}