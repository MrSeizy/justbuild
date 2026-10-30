package dev.justbuild.world;

import dev.justbuild.plan.Cell;
import dev.justbuild.plan.PlanRotation;
import dev.justbuild.prompt.PlanningFeedback;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Review evidence about access: areas of a draft no walking route reaches. Advisory; never a reason to correct. */
final class RouteFindings {
    static final int MAX_AREAS = 4, MIN_SPACES = 4;

    private RouteFindings() { }

    /**
     * positions are the draft's changes plus an edit's existing blocks; candidate reads the world with the draft in
     * place (null for unloaded cells). Returns ROUTE_START and up to four UNREACHABLE areas in the plan frame, or
     * nothing when every counted area connects or the route check can't run.
     */
    static List<PlanningFeedback.Problem> find(ResourceKey<Level> dimension, Collection<BlockPos> positions,
            Function<BlockPos, BlockState> candidate, BlockPos origin, Direction facing) {
        if (positions.isEmpty()) return List.of();
        BuildRegion bounds;
        try {
            BuildRegion box = BuildRegion.enclosing(dimension, positions);
            // routes() drops the top layer as roof; one more layer keeps a tower platform inside its parapet.
            bounds = new BuildRegion(dimension, box.min(), box.max().above());
        } catch (IllegalArgumentException tooLarge) { return List.of(); }
        Walkability.Routes routes = Walkability.routes(bounds, candidate, null, false);
        if (!routes.complete() || routes.start() == null) return List.of();
        Set<BlockPos> draft = new HashSet<>(positions);
        List<List<Walkability.Node>> areas = new ArrayList<>(Walkability.unreachedAreas(routes,
                n -> (Walkability.isInterior(bounds, n.pos()) || draft.contains(n.floor()))
                        && !(candidate.apply(n.floor()).getBlock() instanceof LeavesBlock)));
        // Pitched roofs are runs of stairs; a real stairway belongs to a larger floor area.
        areas.removeIf(a -> a.size() < MIN_SPACES || 2 * a.stream().filter(n -> n.stairs() != null).count() > a.size());
        if (areas.isEmpty()) return List.of();
        // Each area lists its lowest space first.
        areas.sort(Comparator.<List<Walkability.Node>>comparingInt(a -> -a.size())
                .thenComparingInt(a -> a.getFirst().pos().getY()).thenComparingInt(a -> a.getFirst().pos().getX())
                .thenComparingInt(a -> a.getFirst().pos().getZ()));
        PlanRotation inverse = PlanRotation.forFacing(facing.getName()).inverse();
        List<PlanningFeedback.Problem> found = new ArrayList<>();
        try {
            found.add(new PlanningFeedback.Problem(PlanningFeedback.Kind.ROUTE_START, PlanningCheck.planCell(routes.start(), origin, inverse)));
            for (List<Walkability.Node> area : areas.subList(0, Math.min(MAX_AREAS, areas.size()))) {
                int x0 = Integer.MAX_VALUE, y0 = x0, z0 = x0, x1 = Integer.MIN_VALUE, y1 = x1, z1 = x1;
                for (Walkability.Node n : area) {
                    Cell c = PlanningCheck.planCell(n.pos(), origin, inverse);
                    x0 = Math.min(x0, c.x()); y0 = Math.min(y0, c.y()); z0 = Math.min(z0, c.z());
                    x1 = Math.max(x1, c.x()); y1 = Math.max(y1, c.y()); z1 = Math.max(z1, c.z());
                }
                found.add(new PlanningFeedback.Problem(PlanningFeedback.Kind.UNREACHABLE, new Cell(x0, y0, z0), new Cell(x1, y1, z1),
                        Math.min(area.size(), PlanningFeedback.Problem.MAX_COUNT), List.of()));
            }
        } catch (IllegalArgumentException farAway) { return List.of(); }
        return found;
    }
}
