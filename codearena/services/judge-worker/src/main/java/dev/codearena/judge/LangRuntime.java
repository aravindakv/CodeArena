package dev.codearena.judge;

import dev.codearena.codegen.Lang;

/**
 * Container settings per language. Compiled languages need an executable /tmp and extra memory for the
 * compiler; their run-time memory limit is then enforced from the measured peak (maxrss).
 *
 * @param tmpfs          mount options for /tmp inside the container
 * @param extraMemoryMb  added to the problem's memory limit for the container (compiler, JVM)
 * @param startupMs      added to the problem's time limit (interpreter or JVM start-up)
 * @param compileBudgetMs worst-case compile time, used for the outer wall-clock limit
 */
record LangRuntime(String tmpfs, int extraMemoryMb, int startupMs, int compileBudgetMs) {

    static LangRuntime of(Lang lang) {
        return switch (lang) {
            case PYTHON -> new LangRuntime("rw,noexec,nosuid,size=64m", 0, 200, 2_000);
            case JAVASCRIPT -> new LangRuntime("rw,noexec,nosuid,size=64m", 64, 300, 2_000);
            case JAVA -> new LangRuntime("rw,noexec,nosuid,size=128m", 320, 800, 20_000);
            case CPP -> new LangRuntime("rw,exec,nosuid,size=128m", 512, 0, 30_000);
            case GO -> new LangRuntime("rw,exec,nosuid,size=512m", 768, 0, 30_000);
        };
    }
}