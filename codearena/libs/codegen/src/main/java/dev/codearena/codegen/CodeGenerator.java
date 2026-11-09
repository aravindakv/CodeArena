package dev.codearena.codegen;

import com.samskivert.mustache.Mustache;
import com.samskivert.mustache.Template;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Renders starter code (what the user sees in the editor) and the hidden harness (reads JSON test
 * inputs from stdin, calls the user's function, writes one JSON result per line to /out/results.jsonl).
 * Templates: src/main/resources/codegen/&lt;lang&gt;/{starter,harness}.mustache
 */
public final class CodeGenerator {

    public enum Kind { STARTER, HARNESS }

    private final Mustache.Compiler mustache = Mustache.compiler().escapeHTML(false);
    private final Map<String, Template> cache = new ConcurrentHashMap<>();

    public String starter(Lang lang, Signature sig) {
        return render(lang, Kind.STARTER, sig);
    }

    public String harness(Lang lang, Signature sig) {
        return render(lang, Kind.HARNESS, sig);
    }

    public String render(Lang lang, Kind kind, Signature sig) {
        sig.validate();
        List<Map<String, Object>> params = new ArrayList<>();
        for (int i = 0; i < sig.params().size(); i++) {
            Signature.Param p = sig.params().get(i);
            Map<String, Object> m = new HashMap<>();
            m.put("name", p.name());
            m.put("type", Types.param(lang, p.type()));
            m.put("valueType", Types.value(lang, p.type()));
            m.put("typeDoc", "{" + Types.param(lang, p.type()) + "}");
            m.put("last", i == sig.params().size() - 1);
            params.add(m);
        }
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("function", sig.function());
        ctx.put("params", params);
        ctx.put("returns", Types.value(lang, sig.returns()));
        ctx.put("returnsDoc", "{" + Types.value(lang, sig.returns()) + "}");
        String name = "codegen/" + lang.id + "/" + kind.name().toLowerCase() + ".mustache";
        return cache.computeIfAbsent(name, this::load).execute(ctx);
    }

    private Template load(String resource) {
        try (InputStream in = CodeGenerator.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("missing template " + resource);
            return mustache.compile(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}