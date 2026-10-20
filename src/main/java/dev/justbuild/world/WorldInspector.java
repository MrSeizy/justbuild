package dev.justbuild.world;

import dev.justbuild.plan.Cell;
import dev.justbuild.plan.InspectionRequest;
import dev.justbuild.plan.PlanRotation;
import dev.justbuild.prompt.WorldSnapshot;
import dev.justbuild.prompt.BuildArea;
import java.util.Collection;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Rotation;

/** Server-thread reads of loaded blocks only, in exactly the coordinate and state frame used by plans. */
public final class WorldInspector {
    private WorldInspector() {
    }

    public static WorldSnapshot inspect(ServerLevel level, BlockPos origin, Direction facing, InspectionRequest box) {
        PlanRotation turn = PlanRotation.forFacing(facing.getName());
        Rotation inverse = Rotation.valueOf(turn.inverse().name());
        return WorldSnapshot.sample(box, cell -> {
            Cell offset = turn.apply(cell);
            BlockPos pos = origin.offset(offset.x(), offset.y(), offset.z());
            if (level.isOutsideBuildHeight(pos) || !level.hasChunk(SectionPos.blockToSectionCoord(pos.getX()),
                    SectionPos.blockToSectionCoord(pos.getZ()))) {
                return null;
            }
            return BlockStateParser.serialize(level.getBlockState(pos).rotate(inverse));
        });
    }

    public static InspectionRequest site(boolean centered) {
        return new InspectionRequest(new Cell(-12, -4, centered ? -12 : -24), new Cell(12, 20, centered ? 12 : 0));
    }

    /** A view of the footprint near its origin; larger plots can be examined with the two closer reads. */
    public static InspectionRequest site(BuildArea area) {
        return new InspectionRequest(new Cell(Math.max(-64, area.minX()), -4, Math.max(-64, area.minZ())),
                new Cell(Math.min(63, area.maxX()), 24, Math.min(63, area.maxZ())));
    }

    /** The existing build and a one-block margin, clipped to the inspection contract for unusually large builds. */
    public static InspectionRequest around(Collection<BlockPos> positions, BlockPos origin, Direction facing) {
        if (positions.isEmpty()) {
            return site(true);
        }
        PlanRotation inverse = PlanRotation.forFacing(facing.getName()).inverse();
        int[] min = {128, 128, 128};
        int[] max = {-128, -128, -128};
        for (BlockPos pos : positions) {
            Cell c = inverse.apply(new Cell(pos.getX() - origin.getX(), pos.getY() - origin.getY(), pos.getZ() - origin.getZ()));
            int[] axes = {c.x(), c.y(), c.z()};
            for (int i = 0; i < 3; i++) {
                min[i] = Math.min(min[i], Math.max(-128, Math.min(128, axes[i] - 1)));
                max[i] = Math.max(max[i], Math.max(-128, Math.min(128, axes[i] + 1)));
            }
        }
        for (int i = 0; i < 3; i++) {
            max[i] = Math.min(max[i], min[i] + InspectionRequest.MAX_SPAN - 1);
        }
        return new InspectionRequest(new Cell(min[0], min[1], min[2]), new Cell(max[0], max[1], max[2]));
    }
}
