package dev.justbuild.prompt;

import com.google.gson.JsonObject;
import java.util.List;

/** The selected ground footprint, expressed in the plan frame; height remains the world's build height. */
public record BuildArea(int minX, int minZ, int maxX, int maxZ, Integer minY, Integer maxY,
        List<Footprint.Point> polygon) {
    public static final int MAX_SPAN = 256;
    private static final int MAX_OFFSET = 4096;

    public BuildArea(int minX, int minZ, int maxX, int maxZ) { this(minX, minZ, maxX, maxZ, null, null, List.of()); }

    public BuildArea {
        if (minX > maxX || minZ > maxZ || (long) maxX - minX >= MAX_SPAN || (long) maxZ - minZ >= MAX_SPAN) {
            throw new IllegalArgumentException("a build zone must be 1–" + MAX_SPAN + " blocks wide and deep");
        }
        for (int n : new int[] {minX, minZ, maxX, maxZ}) {
            if (Math.abs((long) n) > MAX_OFFSET) {
                throw new IllegalArgumentException("the build zone is too far from this build's origin");
            }
        }
        if ((minY == null) != (maxY == null) || minY != null && (minY > maxY
                || Math.abs((long) minY) > MAX_OFFSET || Math.abs((long) maxY) > MAX_OFFSET))
            throw new IllegalArgumentException("Invalid zone height.");
        polygon = Footprint.validate(polygon);
        for (var p : polygon) if (p.x() < minX || p.x() > maxX || p.z() < minZ || p.z() > maxZ)
            throw new IllegalArgumentException("Polygon corners must be inside the zone bounds.");
    }

    public String format() {
        return "Selected build zone (inclusive, in this plan's coordinates): x=" + minX + ".." + maxX
                + ", z=" + minZ + ".." + maxZ + (minY == null ? "; automatic height." : ", y=" + minY + ".." + maxY + ".")
                + (polygon.isEmpty() ? "" : " Only inside the polygon (x,z): " + Footprint.json(polygon) + ".")
                + " Only this selected space may change. "
                + "Fit new builds, clearing, foundations, and decorations inside it. For an edit, return the complete "
                + "revised plan but keep everything outside this footprint unchanged. The mod refuses a change "
                + "outside the zone; it does not crop the plan. The usual size and block limits still apply.";
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("minX", minX);
        o.addProperty("minZ", minZ);
        o.addProperty("maxX", maxX);
        o.addProperty("maxZ", maxZ);
        if (minY != null) { o.addProperty("minY", minY); o.addProperty("maxY", maxY); }
        if (!polygon.isEmpty()) o.add("polygon", Footprint.json(polygon));
        return o;
    }

    public static BuildArea parse(JsonObject o) {
        return new BuildArea(integer(o, "minX"), integer(o, "minZ"), integer(o, "maxX"), integer(o, "maxZ"),
                o.has("minY") ? integer(o, "minY") : null, o.has("maxY") ? integer(o, "maxY") : null,
                o.has("polygon") ? Footprint.parse(o.getAsJsonArray("polygon")) : List.of());
    }

    private static int integer(JsonObject o, String key) {
        var e = o.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("zone coordinates must be integers");
        }
        return e.getAsBigDecimal().intValueExact();
    }
}
