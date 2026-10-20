package dev.justbuild.prompt;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.justbuild.plan.Cell;
import dev.justbuild.plan.InspectionRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Bounded block-state samples, never block-entity contents, chat, signs, commands, or arbitrary server text. */
public record WorldSnapshot(InspectionRequest box, int step, List<String> palette, List<String> rows) {
    public static final int MAX_SAMPLES = 4096;
    public static final int MAX_AXIS = 24;
    public static final String CODES = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz+/";
    private static final Pattern BLOCK = Pattern.compile(
            "[a-z0-9_.-]{1,40}:[a-z0-9_./-]{1,80}(\\[[a-z0-9_]{1,40}=[a-z0-9_-]{1,40}"
                    + "(?:,[a-z0-9_]{1,40}=[a-z0-9_-]{1,40})*\\])?");

    public WorldSnapshot {
        if (box == null || step < 1 || step > InspectionRequest.MAX_SPAN) {
            throw new IllegalArgumentException("invalid inspection sampling step");
        }
        palette = List.copyOf(palette);
        rows = List.copyOf(rows);
        if (palette.size() > CODES.length() || palette.stream().anyMatch(
                s -> s.length() > 512 || !BLOCK.matcher(s).matches()) || palette.stream().distinct().count() != palette.size()) {
            throw new IllegalArgumentException("inspection palette must contain only bounded block states");
        }
        int nx = size(box.from().x(), box.to().x(), step);
        int ny = size(box.from().y(), box.to().y(), step);
        int nz = size(box.from().z(), box.to().z(), step);
        if (Math.max(nx, Math.max(ny, nz)) > MAX_AXIS || nx * ny * nz > MAX_SAMPLES || rows.size() != ny * nz) {
            throw new IllegalArgumentException("inspection grid is too large or has the wrong number of rows");
        }
        for (String row : rows) {
            if (row.length() != nx) {
                throw new IllegalArgumentException("inspection rows must have the expected width");
            }
            for (int i = 0; i < row.length(); i++) {
                char c = row.charAt(i);
                if (c != '?' && (CODES.indexOf(c) < 0 || CODES.indexOf(c) >= palette.size())) {
                    throw new IllegalArgumentException("inspection contains an unknown palette index");
                }
            }
        }
    }

    /** The lookup must not load chunks or mutate the world; null means unknown, not air. */
    public static WorldSnapshot sample(InspectionRequest box, Function<Cell, String> lookup) {
        int step = 1;
        while (true) {
            int nx = size(box.from().x(), box.to().x(), step);
            int ny = size(box.from().y(), box.to().y(), step);
            int nz = size(box.from().z(), box.to().z(), step);
            if (Math.max(nx, Math.max(ny, nz)) <= MAX_AXIS && nx * ny * nz <= MAX_SAMPLES) {
                break;
            }
            step++;
        }
        List<String> palette = new ArrayList<>();
        List<String> rows = new ArrayList<>();
        for (int y = box.from().y(); y <= box.to().y(); y += step) {
            for (int z = box.from().z(); z <= box.to().z(); z += step) {
                StringBuilder row = new StringBuilder();
                for (int x = box.from().x(); x <= box.to().x(); x += step) {
                    String state = lookup.apply(new Cell(x, y, z));
                    if (state == null) {
                        row.append('?');
                        continue;
                    }
                    int index = palette.indexOf(state);
                    if (index < 0 && palette.size() < CODES.length()) {
                        index = palette.size();
                        palette.add(state);
                    }
                    row.append(index < 0 ? '?' : CODES.charAt(index));
                }
                rows.add(row.toString());
            }
        }
        return new WorldSnapshot(box, step, palette, rows);
    }

    private static int size(int from, int to, int step) {
        return (to - from) / step + 1;
    }

    public Map<String, Integer> counts() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String row : rows) {
            for (int i = 0; i < row.length(); i++) {
                int index = CODES.indexOf(row.charAt(i));
                if (index >= 0) {
                    out.merge(palette.get(index), 1, Integer::sum);
                }
            }
        }
        return out;
    }

    public String format() {
        StringBuilder text = new StringBuilder("Live world inspection (read-only block states, in the same frame as "
                + "the plan): x=").append(box.from().x()).append("..").append(box.to().x())
                .append(", y=").append(box.from().y()).append("..").append(box.to().y())
                .append(", z=").append(box.from().z()).append("..").append(box.to().z())
                .append(". Sample spacing: ").append(step).append(" block(s). ? means unavailable or omitted, never air. "
                        + "Only sampled cells are known; do not assume the gaps are empty. No inventory contents or text "
                        + "are read. Each y layer below has rows in increasing z, characters in increasing x.\nPalette:");
        for (int i = 0; i < palette.size(); i++) {
            text.append('\n').append(CODES.charAt(i)).append('=').append(palette.get(i));
        }
        int nz = size(box.from().z(), box.to().z(), step);
        for (int i = 0; i < rows.size(); i++) {
            if (i % nz == 0) {
                text.append("\ny=").append(box.from().y() + i / nz * step).append(':');
            }
            text.append('\n').append(rows.get(i));
        }
        return text.toString();
    }

    public JsonObject toJson() {
        JsonObject o = box.toJson();
        o.addProperty("step", step);
        JsonArray p = new JsonArray();
        palette.forEach(p::add);
        o.add("palette", p);
        JsonArray r = new JsonArray();
        rows.forEach(r::add);
        o.add("rows", r);
        return o;
    }

    public static WorldSnapshot parse(JsonObject o) {
        InspectionRequest box = InspectionRequest.parse(o);
        JsonElement step = o.get("step");
        if (step == null || !step.isJsonPrimitive() || !step.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("inspection step must be an integer");
        }
        return new WorldSnapshot(box, step.getAsBigDecimal().intValueExact(),
                strings(o.get("palette"), CODES.length(), 512), strings(o.get("rows"), MAX_AXIS * MAX_AXIS, MAX_AXIS));
    }

    private static List<String> strings(JsonElement e, int maxCount, int maxLength) {
        if (!(e instanceof JsonArray a) || a.size() > maxCount) {
            throw new IllegalArgumentException("inspection array is missing or too large");
        }
        List<String> out = new ArrayList<>();
        for (JsonElement v : a) {
            if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isString() || v.getAsString().length() > maxLength) {
                throw new IllegalArgumentException("invalid inspection value");
            }
            out.add(v.getAsString());
        }
        return out;
    }
}
