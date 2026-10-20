package dev.justbuild.world;

import dev.justbuild.plan.BlockRef;
import dev.justbuild.plan.BuildPlan;
import dev.justbuild.plan.Cell;
import dev.justbuild.plan.PlanException;
import dev.justbuild.plan.PlanRotation;
import dev.justbuild.plan.Rasterizer;
import dev.justbuild.plan.WorldQuery;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Plan → world placements: validate blocks, rasterize (with world reads), resolve derived specs, mirror and rotate
 * states to the player's facing, offset to the origin, complete doors and beds. Deterministic for a given lookup.
 */
public final class PlanPipeline {
    /** What the world holds at a position, as this build should see it (its baseline first, then the live world). */
    public interface WorldLookup {
        BlockState get(BlockPos pos);
    }

    public record Result(LinkedHashMap<BlockPos, BlockState> placements, Set<String> invalid, int outsideWorld) {
    }

    private PlanPipeline() {
    }

    public static Result run(BuildPlan plan, BlockPos origin, Direction facing, LevelHeightAccessor height,
            WorldLookup world, int maxCells) throws PlanException {
        PlanRotation rotation = PlanRotation.forFacing(facing.getName());
        Rotation mcRotation = Rotation.valueOf(rotation.name());
        BlockResolver.Result base = BlockResolver.resolve(plan.operations());
        if (base.operations().isEmpty()) {
            throw new PlanException("none of the plan's blocks exist: " + String.join(", ", base.invalid()));
        }
        WorldQuery query = new WorldQuery() {
            BlockState at(Cell cell) {
                Cell r = rotation.apply(cell);
                return world.get(origin.offset(r.x(), r.y(), r.z()));
            }

            @Override
            public String blockId(Cell cell) {
                return BuiltInRegistries.BLOCK.getKey(at(cell).getBlock()).toString();
            }

            @Override
            public boolean isSolid(Cell cell) {
                BlockState state = at(cell);
                return !state.isAir() && !state.canBeReplaced();
            }
        };
        Map<Cell, BlockRef> cells = Rasterizer.rasterize(base.operations(), maxCells, query, plan.texture());
        Set<String> specs = new LinkedHashSet<>();
        cells.values().forEach(ref -> specs.add(ref.spec()));
        BlockResolver.Specs resolved = BlockResolver.resolveSpecs(specs);

        Map<BlockRef, BlockState> oriented = new HashMap<>();
        LinkedHashMap<BlockPos, BlockState> placements = new LinkedHashMap<>();
        int outside = 0;
        for (Map.Entry<Cell, BlockRef> entry : cells.entrySet()) {
            BlockState state = resolved.states().get(entry.getValue().spec());
            if (state == null) {
                continue;
            }
            Cell r = rotation.apply(entry.getKey());
            BlockPos pos = origin.offset(r.x(), r.y(), r.z());
            if (height.isOutsideBuildHeight(pos)) {
                outside++;
                continue;
            }
            placements.put(pos, oriented.computeIfAbsent(entry.getValue(), ref -> orient(state, ref, mcRotation)));
        }
        StructureCompleter.complete(placements, height);
        Set<String> invalid = new LinkedHashSet<>(base.invalid());
        invalid.addAll(resolved.invalid());
        return new Result(placements, Collections.unmodifiableSet(invalid), outside);
    }

    /** Mirror in the plan frame first (FRONT_BACK swaps east/west, LEFT_RIGHT swaps north/south), then rotate. */
    static BlockState orient(BlockState state, BlockRef ref, Rotation rotation) {
        BlockState s = state;
        if (ref.flipX()) {
            s = s.mirror(Mirror.FRONT_BACK);
        }
        if (ref.flipZ()) {
            s = s.mirror(Mirror.LEFT_RIGHT);
        }
        return s.rotate(rotation);
    }
}
