package dev.justbuild.prompt;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Local construction references. The AI selects relevant ids from the menu before planning. */
public final class GuideLibrary {
    public record Guide(String id, String title, String menu, String text) { }

    /** One selection round, with room for the subject, its functions, and complementary techniques. */
    public static final int MAX_PER_REQUEST = 6;
    public static final int MAX_TOTAL = MAX_PER_REQUEST;
    private static final String DIR = "/assets/justbuild/guides/";
    private static volatile GuideLibrary defaults;

    private final List<Guide> guides;

    GuideLibrary(List<Guide> guides) {
        this.guides = List.copyOf(guides);
    }

    public static GuideLibrary defaults() {
        GuideLibrary library = defaults;
        if (library == null) {
            List<Guide> list = new ArrayList<>();
            for (JsonElement e : JsonParser.parseString(read("guides.json")).getAsJsonArray()) {
                var o = e.getAsJsonObject();
                String id = o.get("id").getAsString();
                list.add(new Guide(id, o.get("title").getAsString(), o.get("menu").getAsString(),
                        read(id + ".md").strip()));
            }
            library = new GuideLibrary(list);
            defaults = library;
        }
        return library;
    }

    private static String read(String name) {
        try (InputStream in = GuideLibrary.class.getResourceAsStream(DIR + name)) {
            if (in == null) {
                throw new IllegalStateException("missing " + DIR + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public List<Guide> all() {
        return guides;
    }

    public Optional<Guide> byId(String id) {
        String wanted = id.strip().toLowerCase(Locale.ROOT);
        return guides.stream().filter(g -> g.id().equals(wanted)).findFirst();
    }

    /** The known guides among {@code ids}, in order, without repeats, at most {@link #MAX_PER_REQUEST}. */
    public List<Guide> pick(List<String> ids) {
        List<Guide> out = new ArrayList<>();
        for (String id : ids) {
            byId(id).filter(g -> !out.contains(g)).ifPresent(out::add);
            if (out.size() == MAX_PER_REQUEST) {
                break;
            }
        }
        return out;
    }

    /** The known guides among {@code ids}, in order, without repeats, at most {@link #MAX_TOTAL}. */
    public List<Guide> resolve(List<String> ids) {
        return pick(ids);
    }

    /** One line per guide, for the system prompt. */
    public String menu() {
        StringBuilder sb = new StringBuilder();
        guides.forEach(g -> sb.append("- ").append(g.id()).append(": ").append(g.menu()).append('\n'));
        return sb.toString();
    }
}
