package dev.justbuild.world;

import com.google.gson.JsonObject;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import dev.justbuild.plan.Cell;
import dev.justbuild.plan.PlanException;
import dev.justbuild.plan.PlanRotation;
import dev.justbuild.prompt.BuildArea;
import dev.justbuild.prompt.Footprint;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** An inclusive rectangle or polygon, with optional vertical bounds. */
public record BuildZone(ResourceKey<Level> dimension, int minX, int minZ, int maxX, int maxZ, int displayY,
        Integer minY, Integer maxY, List<Footprint.Point> polygon) {
    public BuildZone(ResourceKey<Level> dimension, int minX, int minZ, int maxX, int maxZ, int displayY) {
        this(dimension, minX, minZ, maxX, maxZ, displayY, null, null, List.of());
    }

    public BuildZone {
        if (dimension == null || minX > maxX || minZ > maxZ || (long) maxX - minX >= BuildArea.MAX_SPAN
                || (long) maxZ - minZ >= BuildArea.MAX_SPAN) {
            throw new IllegalArgumentException("Select a footprint no larger than " + BuildArea.MAX_SPAN + " × "
                    + BuildArea.MAX_SPAN + " blocks.");
        }
        if (Math.abs((long) minX) > 30_000_000 || Math.abs((long) maxX) > 30_000_000
                || Math.abs((long) minZ) > 30_000_000 || Math.abs((long) maxZ) > 30_000_000
                || Math.abs((long) displayY) > 4096 || (minY == null) != (maxY == null)
                || minY != null && (minY > maxY || Math.abs((long) minY) > 4096 || Math.abs((long) maxY) > 4096))
            throw new IllegalArgumentException("The zone has invalid coordinates or height.");
        polygon = Footprint.validate(polygon);
        for (var p : polygon) if (p.x() < minX || p.x() > maxX || p.z() < minZ || p.z() > maxZ)
            throw new IllegalArgumentException("Polygon corners must be inside the zone bounds.");
    }

    public static BuildZone between(ResourceKey<Level> dimension, BlockPos first, BlockPos second) {
        return new BuildZone(dimension, Math.min(first.getX(), second.getX()), Math.min(first.getZ(), second.getZ()),
                Math.max(first.getX(), second.getX()), Math.max(first.getZ(), second.getZ()), first.getY() + 1);
    }

    public boolean contains(BlockPos pos) {
        return pos.getX() >= minX && pos.getX() <= maxX && pos.getZ() >= minZ && pos.getZ() <= maxZ
                && (minY == null || pos.getY() >= minY && pos.getY() <= maxY)
                && Footprint.contains(polygon, pos.getX(), pos.getZ());
    }

    public static BuildZone polygon(ResourceKey<Level> dimension, int y, List<Footprint.Point> points,
            Integer minY, Integer maxY) {
        var checked = Footprint.validate(points);
        if (checked.isEmpty()) throw new IllegalArgumentException("Select at least three polygon corners.");
        return new BuildZone(dimension, checked.stream().mapToInt(Footprint.Point::x).min().orElseThrow(),
                checked.stream().mapToInt(Footprint.Point::z).min().orElseThrow(),
                checked.stream().mapToInt(Footprint.Point::x).max().orElseThrow(),
                checked.stream().mapToInt(Footprint.Point::z).max().orElseThrow(), y, minY, maxY, checked);
    }

    public BuildZone height(Integer bottom, Integer top) {
        return new BuildZone(dimension, minX, minZ, maxX, maxZ, displayY, bottom, top, polygon);
    }

    public BuildZone shifted(int x, int y, int z) {
        return new BuildZone(dimension, Math.addExact(minX, x), Math.addExact(minZ, z), Math.addExact(maxX, x),
                Math.addExact(maxZ, z), Math.addExact(displayY, y), minY == null ? null : Math.addExact(minY, y),
                maxY == null ? null : Math.addExact(maxY, y), polygon.stream()
                    .map(p -> new Footprint.Point(Math.addExact(p.x(), x), Math.addExact(p.z(), z))).toList());
    }

    /** Move one rectangle corner or polygon vertex; all other corners stay fixed. */
    public BuildZone corner(int index, int x, int z) {
        if (!polygon.isEmpty()) {
            var points = new java.util.ArrayList<>(polygon);
            if (index < 0 || index >= points.size()) throw new IllegalArgumentException("Choose a polygon corner.");
            points.set(index, new Footprint.Point(x, z));
            return polygon(dimension, displayY, points, minY, maxY);
        }
        return switch (index) {
            case 0 -> new BuildZone(dimension, x, z, maxX, maxZ, displayY, minY, maxY, polygon);
            case 1 -> new BuildZone(dimension, minX, z, x, maxZ, displayY, minY, maxY, polygon);
            case 2 -> new BuildZone(dimension, minX, minZ, x, z, displayY, minY, maxY, polygon);
            case 3 -> new BuildZone(dimension, x, minZ, maxX, z, displayY, minY, maxY, polygon);
            default -> throw new IllegalArgumentException("Choose a corner from 1 to 4.");
        };
    }

    /** Grow each horizontal edge equally without moving the center or changing the displayed ground height. */
    public BuildZone expandedBy(int blocks) {
        if (!polygon.isEmpty()) throw new IllegalArgumentException("Move the polygon's corners to change its footprint.");
        if (blocks < 1 || blocks > BuildArea.MAX_SPAN) {
            throw new IllegalArgumentException("Expansion must be a positive number of blocks, at most " + BuildArea.MAX_SPAN + ".");
        }
        try {
            return new BuildZone(dimension, Math.subtractExact(minX, blocks), Math.subtractExact(minZ, blocks),
                    Math.addExact(maxX, blocks), Math.addExact(maxZ, blocks), displayY, minY, maxY, polygon);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("That expansion goes outside supported world coordinates.");
        }
    }

    /** Smallest footprint containing the existing zone and the supplied positions; never shrinks an edge. */
    public BuildZone including(Iterable<BlockPos> positions) {
        if (!polygon.isEmpty()) throw new IllegalArgumentException("Move the polygon's corners to change its footprint.");
        int x0 = minX, z0 = minZ, x1 = maxX, z1 = maxZ;
        for (BlockPos pos : positions) {
            x0 = Math.min(x0, pos.getX());
            z0 = Math.min(z0, pos.getZ());
            x1 = Math.max(x1, pos.getX());
            z1 = Math.max(z1, pos.getZ());
        }
        return new BuildZone(dimension, x0, z0, x1, z1, displayY, minY, maxY, polygon);
    }

    public void check(ResourceKey<Level> world, Iterable<BlockPos> changes) throws PlanException {
        if (!dimension.equals(world)) {
            throw new PlanException("Your build zone is in another dimension. Clear it or select a zone here.");
        }
        for (BlockPos pos : changes) {
            if (!contains(pos)) {
                throw new PlanException("A change at " + pos.toShortString() + " is outside your build zone. "
                        + "Nothing was placed; adjust the request or the zone.");
            }
        }
    }

    public BuildArea relativeTo(BlockPos origin, Direction facing) {
        PlanRotation inverse = PlanRotation.forFacing(facing.getName()).inverse();
        Cell a = inverse.apply(new Cell(minX - origin.getX(), 0, minZ - origin.getZ()));
        Cell b = inverse.apply(new Cell(maxX - origin.getX(), 0, maxZ - origin.getZ()));
        var points = polygon.stream().map(p -> inverse.apply(new Cell(p.x() - origin.getX(), 0, p.z() - origin.getZ())))
                .map(p -> new Footprint.Point(p.x(), p.z())).toList();
        return new BuildArea(Math.min(a.x(), b.x()), Math.min(a.z(), b.z()), Math.max(a.x(), b.x()), Math.max(a.z(), b.z()),
                minY == null ? null : minY - origin.getY(), maxY == null ? null : maxY - origin.getY(), points);
    }

    public String description() {
        return (maxX - minX + 1) + " × " + (maxZ - minZ + 1) + " blocks, x=" + minX + ".." + maxX
                + ", z=" + minZ + ".." + maxZ + (minY == null ? "; automatic height" : "; y=" + minY + ".." + maxY)
                + (polygon.isEmpty() ? "" : "; " + polygon.size() + " polygon corners");
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("dimension", dimension.identifier().toString());
        o.addProperty("minX", minX);
        o.addProperty("minZ", minZ);
        o.addProperty("maxX", maxX);
        o.addProperty("maxZ", maxZ);
        o.addProperty("y", displayY);
        if (minY != null) { o.addProperty("minY", minY); o.addProperty("maxY", maxY); }
        if (!polygon.isEmpty()) o.add("polygon", Footprint.json(polygon));
        return o;
    }

    public static BuildZone fromJson(JsonObject o) {
        return new BuildZone(ResourceKey.create(Registries.DIMENSION, Identifier.parse(o.get("dimension").getAsString())),
                Footprint.integer(o.get("minX")), Footprint.integer(o.get("minZ")), Footprint.integer(o.get("maxX")),
                Footprint.integer(o.get("maxZ")), Footprint.integer(o.get("y")),
                o.has("minY") ? Footprint.integer(o.get("minY")) : null,
                o.has("maxY") ? Footprint.integer(o.get("maxY")) : null,
                o.has("polygon") ? Footprint.parse(o.getAsJsonArray("polygon")) : List.of());
    }
}
