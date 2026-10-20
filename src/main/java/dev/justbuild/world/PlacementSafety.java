package dev.justbuild.world;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;

/**
 * What a new plan may place. Blocks act on their own when placed (pistons push, redstone powers, water flows, sand
 * falls, a pumpkin on snow becomes a golem), past the zone, locked areas, and guards that only check the cells a build
 * writes; and some blocks can't be removed in survival. Those are left out here, before preview, with a reason.
 * Applies to new plans, local changes, and confirmation against the current world. Undo restores prior states.
 */
public final class PlacementSafety {
    /** Placements kept, and a short reason for each kind of block left out. */
    public record Result(LinkedHashMap<BlockPos, BlockState> placements, List<String> dropped) {
    }

    /** Act beyond their cell when placed or powered, or can't be removed in survival. */
    static final Set<Block> REFUSED = Set.of(Blocks.PISTON, Blocks.STICKY_PISTON, Blocks.PISTON_HEAD,
            Blocks.MOVING_PISTON, Blocks.REDSTONE_BLOCK, Blocks.REDSTONE_TORCH, Blocks.REDSTONE_WALL_TORCH,
            Blocks.REDSTONE_WIRE, Blocks.REPEATER, Blocks.COMPARATOR, Blocks.OBSERVER, Blocks.DAYLIGHT_DETECTOR,
            Blocks.SCULK_SENSOR, Blocks.CALIBRATED_SCULK_SENSOR, Blocks.SCULK_SHRIEKER, Blocks.HOPPER, Blocks.SPONGE,
            Blocks.COMMAND_BLOCK, Blocks.CHAIN_COMMAND_BLOCK, Blocks.REPEATING_COMMAND_BLOCK, Blocks.STRUCTURE_BLOCK,
            Blocks.STRUCTURE_VOID, Blocks.JIGSAW, Blocks.BARRIER, Blocks.LIGHT, Blocks.BEDROCK,
            Blocks.REINFORCED_DEEPSLATE, Blocks.SPAWNER, Blocks.TRIAL_SPAWNER, Blocks.VAULT, Blocks.END_GATEWAY,
            Blocks.TEST_BLOCK, Blocks.TEST_INSTANCE_BLOCK, Blocks.ICE, Blocks.FROSTED_ICE);
    /** How far water may be followed before it counts as escaping (a lake is fine; a flood is not). */
    private static final int MAX_FLOW_CELLS = 20_000;

    private PlacementSafety() {
    }

    /**
     * @param world what is there now where the plan places nothing
     * @param mayAffect whether the build may affect a position (inside its zone or footprint, outside locks and guards)
     */
    public static Result check(Map<BlockPos, BlockState> placements, Function<BlockPos, BlockState> world,
            Predicate<BlockPos> mayAffect) {
        LinkedHashMap<BlockPos, BlockState> kept = new LinkedHashMap<>();
        Set<String> dropped = new LinkedHashSet<>();
        for (Map.Entry<BlockPos, BlockState> e : placements.entrySet()) {
            BlockState state = e.getValue();
            if (REFUSED.contains(state.getBlock())) {
                dropped.add(name(state) + " (acts beyond the build or can't be removed)");
                continue;
            }
            // Switches, buttons, and plates go in off, so nothing is powered by placing them.
            if (state.hasProperty(BlockStateProperties.POWERED)) state = state.setValue(BlockStateProperties.POWERED, false);
            if (state.hasProperty(BlockStateProperties.POWER)) state = state.setValue(BlockStateProperties.POWER, 0);
            kept.put(e.getKey(), state);
        }
        Function<BlockPos, BlockState> layout = pos -> kept.containsKey(pos) ? kept.get(pos) : world.apply(pos);
        dropGolems(kept, layout, dropped);
        dropUnsupported(kept, layout, dropped);
        dropEscapingWater(kept, layout, mayAffect, dropped);
        return new Result(kept, List.copyOf(dropped));
    }

    private static void dropGolems(Map<BlockPos, BlockState> kept, Function<BlockPos, BlockState> layout, Set<String> dropped) {
        kept.entrySet().removeIf(e -> {
            Block block = e.getValue().getBlock();
            if (block != Blocks.CARVED_PUMPKIN && block != Blocks.JACK_O_LANTERN) return false;
            Block below = layout.apply(e.getKey().below()).getBlock();
            boolean golem = below == Blocks.SNOW_BLOCK || below == Blocks.IRON_BLOCK
                    || BuiltInRegistries.BLOCK.getKey(below).getPath().contains("copper_block");
            if (golem) dropped.add(name(e.getValue()) + " (would bring a golem to life)");
            return golem;
        });
    }

    /** Sand, gravel, concrete powder, anvils: kept only with something solid under them, lowest first. */
    private static void dropUnsupported(Map<BlockPos, BlockState> kept, Function<BlockPos, BlockState> layout,
            Set<String> dropped) {
        List<BlockPos> falling = new ArrayList<>();
        kept.forEach((pos, state) -> { if (state.getBlock() instanceof FallingBlock) falling.add(pos); });
        falling.sort(Comparator.comparingInt(BlockPos::getY));
        for (BlockPos pos : falling) {
            if (open(layout.apply(pos.below()))) {
                dropped.add(name(kept.get(pos)) + " (would fall)");
                kept.remove(pos);
            }
        }
    }

    /**
     * Each body of placed water is followed the way it would flow (down first; sideways up to seven blocks, again after
     * each fall). If it would reach a cell the build may not affect, that body is left out.
     */
    private static void dropEscapingWater(Map<BlockPos, BlockState> kept, Function<BlockPos, BlockState> layout,
            Predicate<BlockPos> mayAffect, Set<String> dropped) {
        Set<BlockPos> classified = new HashSet<>();
        List<BlockPos> sources = kept.entrySet().stream().filter(e -> isWater(e.getValue())).map(Map.Entry::getKey).toList();
        for (BlockPos source : sources) {
            if (classified.contains(source)) continue;
            Set<BlockPos> body = new HashSet<>();
            boolean escapes = flowEscapes(source, kept, layout, mayAffect, body);
            classified.addAll(body);
            if (escapes) {
                for (BlockPos pos : body) {
                    if (kept.containsKey(pos) && isWater(kept.get(pos))) kept.remove(pos);
                }
                dropped.add("water (would flow out of the build)");
            }
        }
    }

    private record Flow(BlockPos pos, int reach) {
    }

    private static boolean flowEscapes(BlockPos start, Map<BlockPos, BlockState> kept, Function<BlockPos, BlockState> layout,
            Predicate<BlockPos> mayAffect, Set<BlockPos> body) {
        ArrayDeque<Flow> queue = new ArrayDeque<>();
        Map<BlockPos, Integer> best = new java.util.HashMap<>();
        queue.add(new Flow(start, 7));
        best.put(start, 7);
        while (!queue.isEmpty()) {
            Flow f = queue.poll();
            if (!mayAffect.test(f.pos())) return true;
            if (best.size() > MAX_FLOW_CELLS) return true;
            if (kept.containsKey(f.pos()) && isWater(kept.get(f.pos()))) body.add(f.pos());
            BlockPos below = f.pos().below();
            boolean falls = passable(layout.apply(below));
            if (falls) {
                visit(queue, best, below, 7);
            }
            boolean source = kept.containsKey(f.pos()) && isWater(kept.get(f.pos()));
            if ((!falls || source) && f.reach() > 0) {
                for (Direction d : Direction.Plane.HORIZONTAL) {
                    BlockPos side = f.pos().relative(d);
                    if (passable(layout.apply(side))) visit(queue, best, side, source && isWater(kept.get(side)) ? 7
                            : f.reach() - 1);
                }
            }
        }
        return false;
    }

    private static void visit(ArrayDeque<Flow> queue, Map<BlockPos, Integer> best, BlockPos pos, int reach) {
        Integer seen = best.get(pos);
        if (seen == null || seen < reach) {
            best.put(pos, reach);
            queue.add(new Flow(pos, reach));
        }
    }

    private static boolean isWater(BlockState state) {
        return state != null && (state.getFluidState().is(Fluids.WATER) || state.getFluidState().is(Fluids.FLOWING_WATER));
    }

    /** Water can move into air, replaceable plants and snow, and other water. */
    private static boolean passable(BlockState state) {
        return state.isAir() || state.canBeReplaced() || isWater(state);
    }

    /** Nothing a falling block would rest on. */
    private static boolean open(BlockState state) {
        return state.isAir() || state.canBeReplaced() || !state.getFluidState().isEmpty();
    }

    private static String name(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath().replace('_', ' ');
    }
}
