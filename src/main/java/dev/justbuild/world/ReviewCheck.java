package dev.justbuild.world;

import dev.justbuild.plan.RepairOutcome;
import dev.justbuild.prompt.PlanningFeedback;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/** Compare actual candidate overlays before accepting a replacement. Never mutates the world. */
public final class ReviewCheck {
    public record Verdict(boolean accepted, String reason, RepairOutcome outcome) {
        private static final Verdict ACCEPT = new Verdict(true, null, RepairOutcome.ACCEPTED);
        private static Verdict reject(String reason, RepairOutcome outcome) { return new Verdict(false, reason, outcome); }
    }
    private static final Set<PlanningFeedback.Kind> TECHNICAL = Set.of(PlanningFeedback.Kind.INVALID_BLOCKS,
            PlanningFeedback.Kind.LIMIT, PlanningFeedback.Kind.OUTSIDE_WORLD, PlanningFeedback.Kind.ZONE,
            PlanningFeedback.Kind.CONSTRAINT);
    private ReviewCheck() { }

    /**
     * source is the existing build's placement map, empty for a new build. The first draft already embodies the
     * requested removals: only source cells it still occupies are retained. Reviews may replace their materials,
     * but further demolition belongs in a new edit rather than an unsolicited review correction.
     */
    public static Verdict compare(PlanningCheck.Result draft, PlanningCheck.Result review,
            Map<BlockPos, BlockState> source, ResourceKey<Level> dimension, Function<BlockPos, BlockState> live) {
        for (var kind : TECHNICAL) {
            long before = draft.feedback().problems().stream().filter(p -> p.kind() == kind).count();
            long after = review.feedback().problems().stream().filter(p -> p.kind() == kind).count();
            if (after > before) return Verdict.reject("introduced " + kind + " failures", RepairOutcome.REJECTED_TECHNICAL);
        }
        Function<BlockPos, BlockState> old = p -> draft.changes().containsKey(p) ? draft.changes().get(p) : live.apply(p);
        Function<BlockPos, BlockState> next = p -> review.changes().containsKey(p) ? review.changes().get(p) : live.apply(p);
        for (var entry : source.entrySet()) {
            if (entry.getValue().isAir()) continue;
            BlockState before = old.apply(entry.getKey()), after = next.apply(entry.getKey());
            if (before != null && !before.isAir() && after != null && after.isAir())
                return Verdict.reject("removed existing structure retained by the draft at " + entry.getKey().toShortString(),
                        RepairOutcome.REJECTED_REMOVAL);
        }
        // Incomplete plans cannot supply trustworthy routes, but repairing them must still retain existing blocks.
        if (draft.feedback().technicalFailures() > 0 || review.feedback().technicalFailures() > 0)
            return Verdict.ACCEPT;
        Set<BlockPos> positions = new HashSet<>(source.keySet());
        positions.addAll(draft.changes().keySet()); positions.addAll(review.changes().keySet());
        if (positions.isEmpty()) return Verdict.ACCEPT;
        BuildRegion bounds;
        try { bounds = BuildRegion.enclosing(dimension, positions); }
        catch (IllegalArgumentException tooWide) { return Verdict.ACCEPT; }
        Walkability.Routes before = Walkability.routes(bounds, old, null, true);
        if (!before.complete() || before.start() == null) return Verdict.ACCEPT;
        Walkability.Routes after = Walkability.routes(bounds, next, before.start(), true);
        if (!after.complete()) return Verdict.ACCEPT;
        if (after.start() == null && before.doors().isEmpty()) {
            // A doorless fallback floor can become furniture. Compare from a remaining shared floor instead.
            Set<BlockPos> remaining = after.standing();
            BlockPos anchor = before.reached().stream().filter(remaining::contains)
                    .min(Comparator.<BlockPos>comparingInt(BlockPos::getY)
                            .thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ)).orElse(null);
            if (anchor == null) return Verdict.ACCEPT;
            after = Walkability.routes(bounds, next, anchor, true);
        }
        for (BlockPos door : before.doors()) {
            if (!after.standing().contains(door) || !after.reached().contains(door))
                return Verdict.reject("blocked a usable doorway at " + door.toShortString(), RepairOutcome.REJECTED_ROUTE);
        }
        for (BlockPos p : before.reached()) {
            // Furniture can intentionally occupy floor space. A floor space that remains usable must stay connected.
            if (after.standing().contains(p) && !after.reached().contains(p))
                return Verdict.reject("disconnected a previously reachable floor space at " + p.toShortString(), RepairOutcome.REJECTED_ROUTE);
        }
        return Verdict.ACCEPT;
    }
}
