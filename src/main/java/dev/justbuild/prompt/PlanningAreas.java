package dev.justbuild.prompt;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/** Numeric, bounded editing and connection context; the client supplies all explanatory text. */
public record PlanningAreas(Box focus, List<Box> locked, List<Box> connections) {
    public static final PlanningAreas EMPTY = new PlanningAreas(null, List.of(), List.of());

    public record Box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        public Box {
            int[] lo = {minX, minY, minZ}, hi = {maxX, maxY, maxZ};
            for (int i = 0; i < 3; i++) if (lo[i] > hi[i] || (long) hi[i] - lo[i] >= 256
                    || Math.abs((long) lo[i]) > 4096 || Math.abs((long) hi[i]) > 4096) {
                throw new IllegalArgumentException("Planning area coordinates are out of range.");
            }
        }
        public String coordinates() {
            return "x=" + minX + ".." + maxX + ", y=" + minY + ".." + maxY + ", z=" + minZ + ".." + maxZ;
        }
        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("minX", minX); o.addProperty("minY", minY); o.addProperty("minZ", minZ);
            o.addProperty("maxX", maxX); o.addProperty("maxY", maxY); o.addProperty("maxZ", maxZ);
            return o;
        }
        public static Box parse(JsonObject o) {
            return new Box(number(o, "minX"), number(o, "minY"), number(o, "minZ"),
                    number(o, "maxX"), number(o, "maxY"), number(o, "maxZ"));
        }
        private static int number(JsonObject o, String k) { return o.get(k).getAsBigDecimal().intValueExact(); }
    }

    public PlanningAreas {
        locked = List.copyOf(locked);
        connections = List.copyOf(connections);
        if (locked.size() > 32 || !connections.isEmpty() && connections.size() != 2) {
            throw new IllegalArgumentException("Too many planning areas.");
        }
    }

    public String format() {
        StringBuilder text = new StringBuilder();
        if (focus != null) text.append("\nFocused edit, inclusive coordinates: ").append(focus.coordinates())
                .append(". Change only this three-dimensional part. Return the complete revised plan; everything "
                        + "outside this part is preserved by the mod, including its height boundary.");
        if (!locked.isEmpty()) {
            text.append("\nLocked regions (inclusive plan coordinates):");
            locked.forEach(b -> text.append("\n- ").append(b.coordinates()));
            text.append("\nDo not place, remove, clear, or alter blocks in these regions. The mod keeps them unchanged.");
        }
        if (!connections.isEmpty()) text.append("\nConnect building A (").append(connections.get(0).coordinates())
                .append(") and building B (").append(connections.get(1).coordinates())
                .append("). Make only the requested path, bridge, fence, or landscaping between them. "
                        + "For a path or bridge, connect the actual usable entrances or existing approach landings, "
                        + "not arbitrary nearest corners or walls of these bounding boxes. Locate both entrances in "
                        + "the live samples; request closer inspections if the sample spacing hides a doorway. "
                        + "If the entrances remain ambiguous, ask the player instead of guessing. At BOTH ends, "
                        + "center the path on the doorway and run it straight outward, perpendicular to the facade, "
                        + "for at least five blocks beyond the threshold or existing porch steps BEFORE turning. "
                        + "Then connect those two outward approach segments with a continuous walkable route and "
                        + "gentle bends around the protected buildings. Do not run the connector along a front wall "
                        + "and join its doorway from the side; a path touching both doors is not sufficient. "
                        + "Keep the full path width through both straight approaches, leave space between the "
                        + "turn and the building, and check both approach directions again during review. "
                        + "An explicit player request or an existing approach can override this layout. "
                        + "The build zone includes turning room; it is a limit, not an area to pave. "
                        + "Keep a consistent 3–5 block path width unless asked otherwise, match the actual ground "
                        + "and landing heights, and use supported steps for height changes. Preserve both buildings "
                        + "and leave two blocks of headroom at each connection.");
        return text.toString();
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        if (focus != null) o.add("focus", focus.toJson());
        JsonArray locks = new JsonArray(), links = new JsonArray();
        locked.forEach(b -> locks.add(b.toJson())); connections.forEach(b -> links.add(b.toJson()));
        o.add("locked", locks); o.add("connections", links);
        return o;
    }

    public static PlanningAreas parse(JsonObject o) {
        List<Box> locked = boxes(o, "locked", 32), connections = boxes(o, "connections", 2);
        return new PlanningAreas(o.get("focus") instanceof JsonObject f ? Box.parse(f) : null, locked, connections);
    }

    private static List<Box> boxes(JsonObject o, String key, int limit) {
        if (!(o.get(key) instanceof JsonArray a)) return List.of();
        if (a.size() > limit) throw new IllegalArgumentException("Too many planning areas.");
        List<Box> result = new ArrayList<>();
        a.forEach(e -> result.add(Box.parse(e.getAsJsonObject())));
        return result;
    }
}
