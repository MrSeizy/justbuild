package dev.justbuild.plan;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * A separate reviewer's findings about one plan: ranked problems with fixes and what a repair must keep, never
 * geometry. {@code planKey} is the 64-hex key of the plan it judged (relay.PromptCheck#planKey).
 */
public record Critique(String planKey, Verdict verdict, List<Problem> problems, List<String> keep) {
    public static final int MAX_PROBLEMS = 6;
    public static final int MAX_KEEP = 6;
    public static final int MAX_OPERATIONS = 8;
    public static final int MAX_PROBLEM = 200;
    public static final int MAX_FIX = 240;
    public static final int MAX_KEEP_TEXT = 120;
    public static final int MAX_CELL = 4096;

    public enum Verdict { OK, REVISE }
    public enum Severity { MUST_FIX, SHOULD_FIX, MINOR }
    public enum Category { REQUEST, ACCESS, STRUCTURE, MASSING, ROOF, FACADE, MATERIALS, INTERIOR, SETTING }

    /** {@code operations} are plan indices from 0; {@code cell} is in plan coordinates or null. */
    public record Problem(Severity severity, Category category, List<Integer> operations, Cell cell, String problem,
            String fix) {
        public Problem {
            operations = List.copyOf(operations);
            if (severity == null || category == null || operations.size() > MAX_OPERATIONS
                    || problem == null || problem.isEmpty() || fix == null || fix.isEmpty()) {
                throw new IllegalArgumentException("Invalid critique problem.");
            }
        }

        public boolean worthFixing() {
            return severity != Severity.MINOR;
        }
    }

    public Critique {
        problems = List.copyOf(problems);
        keep = List.copyOf(keep);
        if (planKey == null || !planKey.matches("[0-9a-f]{64}") || verdict == null || problems.size() > MAX_PROBLEMS
                || keep.size() > MAX_KEEP) {
            throw new IllegalArgumentException("Invalid critique.");
        }
    }

    /** Whether this critique alone asks for a repair. The severities decide; the verdict never does on its own. */
    public boolean needsRepair() {
        return problems.stream().anyMatch(Problem::worthFixing);
    }

    /** The categories of the problems worth fixing, in rank order, without repeats. */
    public List<Category> categories() {
        return problems.stream().filter(Problem::worthFixing).map(Problem::category).distinct().toList();
    }

    /** The normalized form, written the same way on every computer so {@link #key()} matches. */
    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("kind", "critique");
        o.addProperty("planKey", planKey);
        o.addProperty("verdict", id(verdict));
        JsonArray list = new JsonArray();
        for (Problem p : problems) {
            JsonObject q = new JsonObject();
            q.addProperty("severity", id(p.severity()));
            q.addProperty("category", id(p.category()));
            JsonArray operations = new JsonArray();
            p.operations().forEach(operations::add);
            q.add("operations", operations);
            if (p.cell() == null) {
                q.add("cell", JsonNull.INSTANCE);
            } else {
                JsonObject cell = new JsonObject();
                cell.addProperty("x", p.cell().x());
                cell.addProperty("y", p.cell().y());
                cell.addProperty("z", p.cell().z());
                q.add("cell", cell);
            }
            q.addProperty("problem", p.problem());
            q.addProperty("fix", p.fix());
            list.add(q);
        }
        o.add("problems", list);
        JsonArray kept = new JsonArray();
        keep.forEach(kept::add);
        o.add("keep", kept);
        return o;
    }

    /** Identifies this critique, so a relayed repair can prove the player's own AI wrote it. */
    public String key() {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(toJson().toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String id(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
