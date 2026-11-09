package dev.codearena.codegen;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

/**
 * Supported languages. The file names must match what each runner image's run.sh expects
 * (see sandbox-images/&lt;lang&gt;/run.sh).
 */
public enum Lang {
    JAVA("java", "Java 25", "java", "Solution.java", "Main.java", true),
    PYTHON("python", "Python 3.13", "python", "solution.py", "harness.py", false),
    CPP("cpp", "C++20 (GCC 15)", "cpp", "solution.cpp", "main.cpp", true),
    JAVASCRIPT("javascript", "JavaScript (Node 24)", "javascript", "solution.js", "harness.js", false),
    GO("go", "Go 1.25", "go", "solution.go", "harness.go", true);

    public final String id;
    public final String label;
    public final String monacoId;
    public final String userFile;
    public final String harnessFile;
    public final boolean compiled;

    Lang(String id, String label, String monacoId, String userFile, String harnessFile, boolean compiled) {
        this.id = id;
        this.label = label;
        this.monacoId = monacoId;
        this.userFile = userFile;
        this.harnessFile = harnessFile;
        this.compiled = compiled;
    }

    /** Docker image of the sandbox runner for this language. */
    public String image() {
        return "codearena/runner-" + id + ":1";
    }

    @JsonValue
    public String id() {
        return id;
    }

    public static Lang of(String id) {
        return Arrays.stream(values()).filter(l -> l.id.equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unsupported language: " + id));
    }
}