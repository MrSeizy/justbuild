package dev.justbuild.prompt;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;

/** A shared project style and plain block ids; all instructions are supplied locally. */
public record ProjectTheme(String style, List<String> palette) {
    public static final ProjectTheme EMPTY = new ProjectTheme("auto", List.of());
    public ProjectTheme {
        if (style == null || Guidance.Style.parse(style).isEmpty()) throw new IllegalArgumentException("Choose a known project style.");
        style = Guidance.Style.parse(style).orElseThrow().id();
        palette = List.copyOf(palette);
        if (palette.size() > 12 || palette.stream().distinct().count() != palette.size()
                || palette.stream().anyMatch(id -> id == null || !id.matches("[a-z0-9_.-]{1,40}:[a-z0-9_./-]{1,80}"))) {
            throw new IllegalArgumentException("Use up to 12 distinct block ids for the project palette.");
        }
    }
    public String format() {
        if (equals(EMPTY)) return "";
        String line = Guidance.Style.parse(style).orElseThrow().promptLine();
        return "\nShared village project: keep new work coordinated with its other buildings. "
                + (line == null ? "" : line + " ")
                + (palette.isEmpty() ? "" : "Use this shared material palette where suitable: " + String.join(", ", palette) + ". ")
                + "Preserve each building's function and the player's explicit constraints.";
    }
    public JsonObject toJson() {
        JsonObject o = new JsonObject(); o.addProperty("style", style);
        JsonArray a = new JsonArray(); palette.forEach(a::add); o.add("palette", a); return o;
    }
    public static ProjectTheme parse(JsonObject o) {
        JsonArray a = o.getAsJsonArray("palette");
        if (a.size() > 12) throw new IllegalArgumentException("Project palette is too large.");
        return new ProjectTheme(o.get("style").getAsString(), java.util.stream.StreamSupport.stream(a.spliterator(), false)
                .map(e -> e.getAsString()).toList());
    }
}
