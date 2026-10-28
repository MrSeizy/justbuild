package dev.justbuild.construction;

import java.util.*;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** Bounded site navigation. Vertical travel lets the helper reach a course without player-built scaffolding. */
public final class ConstructionPath {
    private ConstructionPath() { }
    private record Node(BlockPos pos, int cost, int estimate) { }
    private static final Direction[] STEPS = {Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.NORTH, Direction.UP, Direction.DOWN};
    public static List<BlockPos> find(BlockPos start, BlockPos goal, BlockPos min, BlockPos max, Predicate<BlockPos> clear) {
        if (start.equals(goal)) return List.of(goal);
        if (!clear.test(goal)) return List.of();
        Comparator<Node> priority = Comparator.comparingInt(Node::estimate).thenComparingInt(Node::cost)
                .thenComparingInt(n -> n.pos().getY()).thenComparingInt(n -> n.pos().getZ()).thenComparingInt(n -> n.pos().getX());
        PriorityQueue<Node> open = new PriorityQueue<>(priority); Map<BlockPos, Integer> cost = new HashMap<>();
        Map<BlockPos, BlockPos> previous = new HashMap<>();
        open.add(new Node(start, 0, start.distManhattan(goal) * 10)); cost.put(start, 0);
        int visited = 0;
        while (!open.isEmpty() && visited++ < 4096) {
            Node node = open.remove();
            if (node.cost() != cost.get(node.pos())) continue;
            if (node.pos().equals(goal)) {
                List<BlockPos> path = new ArrayList<>(); BlockPos p = goal;
                while (!p.equals(start)) { path.add(p); p = previous.get(p); }
                path.add(start); Collections.reverse(path); return List.copyOf(path);
            }
            for (Direction direction : STEPS) {
                BlockPos next = node.pos().relative(direction);
                if (next.getX() < min.getX() || next.getX() > max.getX() || next.getY() < min.getY() || next.getY() > max.getY()
                        || next.getZ() < min.getZ() || next.getZ() > max.getZ()) continue;
                int nextCost = node.cost() + (direction.getAxis().isVertical() ? 14 : 10);
                if (nextCost >= cost.getOrDefault(next, Integer.MAX_VALUE) || !clear.test(next)) continue;
                cost.put(next, nextCost); previous.put(next, node.pos());
                open.add(new Node(next, nextCost, nextCost + next.distManhattan(goal) * 10));
            }
        }
        return List.of();
    }
}
