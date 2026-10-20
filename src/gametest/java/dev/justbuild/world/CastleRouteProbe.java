package dev.justbuild.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Comparator;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/** Test-only probes of the production walking graph; these are functional samples, not a design score. */
public final class CastleRouteProbe {
    private CastleRouteProbe() { }

    public static void inspect(ServerLevel level, BlockPos at, JsonObject row, int size) {
        if (size != 30 && size != 55) throw new IllegalArgumentException("Castle site size must be 30 or 55");
        int center = size / 2, cornerEnd = size == 30 ? 8 : 14, farCorner = size == 30 ? 21 : 40;
        int wallBand = size == 30 ? 4 : 6;
        int courtyardMin = size == 30 ? 9 : 12, courtyardMax = size == 30 ? 20 : 42;
        int minimumCourtyard = size == 30 ? 25 : 100, minimumWallWalk = size == 30 ? 20 : 40;
        var bounds = new BuildRegion(Level.OVERWORLD, at.offset(0, -1, -5), at.offset(size - 1, 30, size - 1));
        var graph = Walkability.routes(bounds, level::getBlockState, null, true);
        BlockPos start = graph.standing().stream().filter(p -> Math.abs(p.getX() - at.getX() - center) <= 2
                && p.getZ() <= at.getZ() - 3 && p.getY() <= at.getY() + 2)
                .min(Comparator.<BlockPos>comparingInt(BlockPos::getY).thenComparingInt(BlockPos::getZ)).orElse(null);
        if (start != null) graph = Walkability.routes(bounds, level::getBlockState, start, true);
        check(row, "North approach", graph.complete() && start != null, "Route graph starts outside the north gate; all scanned cells must be known.");
        Map<Integer, Integer> floors = new TreeMap<>(); int courtyard = 0, wallWalk = 0;
        int northWalk = 0, eastWalk = 0, westWalk = 0;
        int[] towers = new int[4];
        for (BlockPos p : graph.reached()) {
            int x = p.getX() - at.getX(), y = p.getY() - at.getY(), z = p.getZ() - at.getZ();
            boolean covered = false;
            for (int height = y + 2; height <= 30 && !covered; height++)
                covered = level.getBlockState(at.offset(x, height, z)).isSolidRender();
            if (x >= courtyardMin && x <= courtyardMax && z >= (size == 30 ? 7 : 12) && z <= courtyardMax && y <= 2 && !covered) courtyard++;
            if (x >= courtyardMin && x <= courtyardMax && z >= (size == 30 ? 10 : 15) && z <= size - 3 && covered)
                floors.merge(y, 1, Integer::sum);
            if (y >= 7) {
                if (x <= cornerEnd && z <= cornerEnd) towers[0]++;
                if (x >= farCorner && z <= cornerEnd) towers[1]++;
                if (x <= cornerEnd && z >= farCorner) towers[2]++;
                if (x >= farCorner && z >= farCorner) towers[3]++;
                // The compact keep may join the south curtain wall: sample the other three sides,
                // so its upper indoor floors cannot impersonate a reachable wall walk.
                if (x > cornerEnd && x < farCorner && z <= wallBand) northWalk++;
                if (z > cornerEnd && z < farCorner && x >= size - 1 - wallBand) eastWalk++;
                if (z > cornerEnd && z < farCorner && x <= wallBand) westWalk++;
                if ((x > cornerEnd && x < farCorner && (z <= wallBand || size == 55 && z >= size - 1 - wallBand))
                        || (z > cornerEnd && z < farCorner && (x <= wallBand || x >= size - 1 - wallBand))) wallWalk++;
            }
        }
        check(row, "Reachable courtyard", start != null && courtyard >= minimumCourtyard,
                courtyard + " reachable open-sky standing spaces in the central courtyard (minimum " + minimumCourtyard + ").");
        int levels = 0, last = -100;
        for (var floor : floors.entrySet()) if (floor.getValue() >= 24 && floor.getKey() >= last + 3) {
            levels++; last = floor.getKey();
        }
        check(row, "Three connected covered levels", start != null && levels >= 3,
                levels + " separated levels have at least 24 reachable covered standing spaces; floor counts " + floors + ".");
        boolean all = start != null;
        for (int count : towers) all &= count >= 8;
        check(row, "Four accessible upper towers", all,
                "Reachable upper standing spaces in NW/NE/SW/SE corner regions: " + java.util.Arrays.toString(towers) + "; minimum 8 each.");
        boolean sampledSides = size == 55 || northWalk > 0 && eastWalk > 0 && westWalk > 0;
        check(row, "Accessible wall walks", start != null && wallWalk >= minimumWallWalk && sampledSides,
                wallWalk + " reachable elevated spaces along the middle wall sections"
                        + (size == 30 ? " (north/east/west samples " + northWalk + "/" + eastWalk + "/" + westWalk + "; each must be reached)" : "")
                        + " (minimum " + minimumWallWalk + ").");
        row.addProperty("routeStandingSpaces", graph.standing().size());
        row.addProperty("routeReachableSpaces", graph.reached().size());
        var notes = row.getAsJsonArray("advisories"); if (notes == null) { notes = new JsonArray(); row.add("advisories", notes); }
        notes.add("Castle route probes sample ordinary walking/stairs from the north approach. Covered levels can include outdoor platforms; enclosure and furnishing require visual inspection. These probes do not certify every room, parapet or movement mechanic.");
    }

    public static void check(JsonObject row, String name, boolean pass, String detail) {
        var c = new JsonObject(); c.addProperty("name", name); c.addProperty("passed", pass); c.addProperty("detail", detail);
        row.getAsJsonArray("checks").add(c);
    }
}
