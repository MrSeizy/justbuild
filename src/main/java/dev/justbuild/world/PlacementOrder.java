package dev.justbuild.world;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;

/** Air and full blocks first, then everything that rests on them (torches, doors, plants, fluids); each bottom-up. */
public final class PlacementOrder {
    private PlacementOrder() {
    }

    public static List<Placement> order(Map<BlockPos, BlockState> placements) {
        List<Placement> structural = new ArrayList<>();
        List<Placement> attached = new ArrayList<>();
        for (Map.Entry<BlockPos, BlockState> entry : placements.entrySet()) {
            Placement p = new Placement(entry.getKey(), entry.getValue());
            (isStructural(entry.getValue()) ? structural : attached).add(p);
        }
        Comparator<Placement> bottomUp = Comparator.comparingInt((Placement p) -> p.pos().getY());
        structural.sort(bottomUp);
        attached.sort(bottomUp);
        List<Placement> ordered = new ArrayList<>(structural.size() + attached.size());
        ordered.addAll(structural);
        ordered.addAll(attached);
        return ordered;
    }

    static boolean isStructural(BlockState state) {
        return state.isAir() || (state.getFluidState().isEmpty()
                && state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO));
    }
}
