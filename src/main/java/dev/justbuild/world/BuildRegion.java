package dev.justbuild.world;

import com.google.gson.JsonObject;
import dev.justbuild.plan.Cell;
import dev.justbuild.plan.PlanRotation;
import dev.justbuild.prompt.PlanningAreas;
import java.util.Collection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** An inclusive three-dimensional selection. Unlike a build zone, its height is explicit. */
public record BuildRegion(ResourceKey<Level> dimension, BlockPos min, BlockPos max) {
    public static final int MAX_SPAN = 256;

    public BuildRegion {
        min = min.immutable();
        max = max.immutable();
        for (int axis = 0; axis < 3; axis++) {
            int lo = coordinate(min, axis), hi = coordinate(max, axis);
            if (lo > hi || (long) hi - lo >= MAX_SPAN || Math.abs((long) lo) > 30_000_000
                    || Math.abs((long) hi) > 30_000_000) {
                throw new IllegalArgumentException("A part or locked area must be 1–256 blocks on each axis.");
            }
        }
        if (Math.abs((long) min.getY()) > 4096 || Math.abs((long) max.getY()) > 4096) {
            throw new IllegalArgumentException("The selected height is out of range.");
        }
    }

    public static BuildRegion between(ResourceKey<Level> dimension, BlockPos a, BlockPos b) {
        return new BuildRegion(dimension, new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()),
                Math.min(a.getZ(), b.getZ())), new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()),
                Math.max(a.getZ(), b.getZ())));
    }

    /** The smallest box containing the current selection and another point; existing bounds never shrink. */
    public BuildRegion including(BlockPos point) {
        return enclosing(dimension, java.util.List.of(min, max, point));
    }

    /** Corner indices 0–7 use bits 1, 2, and 4 to choose the maximum X, Y, and Z respectively. */
    public BlockPos corner(int index) {
        checkCorner(index);
        return new BlockPos((index & 1) == 0 ? min.getX() : max.getX(),
                (index & 2) == 0 ? min.getY() : max.getY(), (index & 4) == 0 ? min.getZ() : max.getZ());
    }

    /** Move any corner while keeping the diagonally opposite corner fixed; crossing it is invalid. */
    public BuildRegion corner(int index, BlockPos point) {
        checkCorner(index);
        return new BuildRegion(dimension,
                new BlockPos((index & 1) == 0 ? point.getX() : min.getX(),
                        (index & 2) == 0 ? point.getY() : min.getY(), (index & 4) == 0 ? point.getZ() : min.getZ()),
                new BlockPos((index & 1) != 0 ? point.getX() : max.getX(),
                        (index & 2) != 0 ? point.getY() : max.getY(), (index & 4) != 0 ? point.getZ() : max.getZ()));
    }

    private static void checkCorner(int index) {
        if (index < 0 || index > 7) throw new IllegalArgumentException("Choose a part corner from 0 to 7.");
    }

    public static BuildRegion enclosing(ResourceKey<Level> dimension, Collection<BlockPos> positions) {
        if (positions.isEmpty()) throw new IllegalArgumentException("No blocks were found for that selection.");
        int x0 = Integer.MAX_VALUE, y0 = x0, z0 = x0, x1 = Integer.MIN_VALUE, y1 = x1, z1 = x1;
        for (BlockPos p : positions) {
            x0 = Math.min(x0, p.getX()); y0 = Math.min(y0, p.getY()); z0 = Math.min(z0, p.getZ());
            x1 = Math.max(x1, p.getX()); y1 = Math.max(y1, p.getY()); z1 = Math.max(z1, p.getZ());
        }
        return new BuildRegion(dimension, new BlockPos(x0, y0, z0), new BlockPos(x1, y1, z1));
    }

    public boolean contains(BlockPos p) {
        return p.getX() >= min.getX() && p.getX() <= max.getX() && p.getY() >= min.getY()
                && p.getY() <= max.getY() && p.getZ() >= min.getZ() && p.getZ() <= max.getZ();
    }

    public boolean intersects(BuildRegion other) {
        return dimension.equals(other.dimension) && min.getX() <= other.max.getX() && max.getX() >= other.min.getX()
                && min.getY() <= other.max.getY() && max.getY() >= other.min.getY()
                && min.getZ() <= other.max.getZ() && max.getZ() >= other.min.getZ();
    }

    public PlanningAreas.Box relativeTo(BlockPos origin, Direction facing) {
        PlanRotation inverse = PlanRotation.forFacing(facing.getName()).inverse();
        Cell a = inverse.apply(new Cell(min.getX() - origin.getX(), min.getY() - origin.getY(), min.getZ() - origin.getZ()));
        Cell b = inverse.apply(new Cell(max.getX() - origin.getX(), max.getY() - origin.getY(), max.getZ() - origin.getZ()));
        return new PlanningAreas.Box(Math.min(a.x(), b.x()), Math.min(a.y(), b.y()), Math.min(a.z(), b.z()),
                Math.max(a.x(), b.x()), Math.max(a.y(), b.y()), Math.max(a.z(), b.z()));
    }

    public String description() {
        return (max.getX() - min.getX() + 1) + " × " + (max.getY() - min.getY() + 1) + " × "
                + (max.getZ() - min.getZ() + 1) + " blocks · " + min.toShortString() + " to " + max.toShortString();
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("dimension", dimension.identifier().toString());
        o.addProperty("minX", min.getX()); o.addProperty("minY", min.getY()); o.addProperty("minZ", min.getZ());
        o.addProperty("maxX", max.getX()); o.addProperty("maxY", max.getY()); o.addProperty("maxZ", max.getZ());
        return o;
    }

    public static BuildRegion parse(JsonObject o) {
        return new BuildRegion(ResourceKey.create(Registries.DIMENSION, Identifier.parse(o.get("dimension").getAsString())),
                new BlockPos(integer(o, "minX"), integer(o, "minY"), integer(o, "minZ")),
                new BlockPos(integer(o, "maxX"), integer(o, "maxY"), integer(o, "maxZ")));
    }

    private static int integer(JsonObject o, String name) { return o.get(name).getAsBigDecimal().intValueExact(); }
    private static int coordinate(BlockPos p, int axis) { return axis == 0 ? p.getX() : axis == 1 ? p.getY() : p.getZ(); }
}
