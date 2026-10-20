package dev.justbuild.prompt;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Ready-made build requests from assets/justbuild/templates.json. */
public final class TemplateLibrary {
    public record Template(String id, String category, String name, String request) {
    }

    private static final String RESOURCE = "/assets/justbuild/templates.json";
    private static volatile TemplateLibrary defaults;

    private final List<Template> templates;

    TemplateLibrary(List<Template> templates) {
        this.templates = List.copyOf(templates);
    }

    public static TemplateLibrary defaults() {
        TemplateLibrary library = defaults;
        if (library == null) {
            try (InputStream in = TemplateLibrary.class.getResourceAsStream(RESOURCE)) {
                if (in == null) {
                    throw new IllegalStateException("missing " + RESOURCE);
                }
                List<Template> list = new ArrayList<>();
                for (JsonElement e : JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                        .getAsJsonArray()) {
                    var o = e.getAsJsonObject();
                    list.add(new Template(o.get("id").getAsString(), o.get("category").getAsString(),
                            o.get("name").getAsString(), o.get("request").getAsString()));
                }
                library = new TemplateLibrary(list);
                defaults = library;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return library;
    }

    public List<Template> all() {
        return templates;
    }

    public Optional<Template> byId(String id) {
        return templates.stream().filter(t -> t.id().equalsIgnoreCase(id.trim())).findFirst();
    }

    /** Templates whose name, category, or request contains the text (all of them for blank text). */
    public List<Template> filter(String text) {
        String q = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) {
            return templates;
        }
        return templates.stream().filter(t -> t.name().toLowerCase(Locale.ROOT).contains(q)
                || t.category().toLowerCase(Locale.ROOT).contains(q)
                || t.request().toLowerCase(Locale.ROOT).contains(q)).toList();
    }
}
