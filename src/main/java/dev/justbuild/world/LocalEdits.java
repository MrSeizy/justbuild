package dev.justbuild.world;

import dev.justbuild.plan.PlanException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** Deterministic changes to known positions. Air-only repair preserves manual replacements by default. */
public final class LocalEdits {
    private LocalEdits() { }
    public static String id(BlockState state) { return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(); }
    public static String blockId(String text) throws PlanException {
        String id = text.contains(":") ? text : "minecraft:" + text;
        if (!id.matches("[a-z0-9_.-]{1,40}:[a-z0-9_./-]{1,80}")) throw new PlanException("Choose a block id without properties.");
        var resolved = BlockResolver.resolveSpecs(java.util.List.of(id));
        if (!resolved.invalid().isEmpty()) throw new PlanException("That material is unknown or cannot be placed: " + id);
        return id(resolved.states().get(id));
    }
    public static Map<BlockPos, BlockState> replace(Map<BlockPos, BlockState> live, String from, String to) throws PlanException {
        String source = blockId(from), target = blockId(to);
        Map<BlockPos, BlockState> changes = new LinkedHashMap<>();
        for (var entry : live.entrySet()) {
            BlockState state = entry.getValue(); if (!id(state).equals(source)) continue;
            String serialized = BlockStateParser.serialize(state); int start = serialized.indexOf('[');
            String replacement = target + (start < 0 ? "" : serialized.substring(start));
            BlockState value = BlockResolver.parse(replacement).orElseThrow(() -> new PlanException(
                    "The replacement cannot preserve " + source + " properties. Choose the same kind of block (stairs, logs, etc.)."));
            if (value != state) changes.put(entry.getKey(), value);
        }
        return changes;
    }
    public static Map<BlockPos, BlockState> repair(Map<BlockPos, BlockState> expected, Function<BlockPos, BlockState> live,
            boolean missingOnly, Function<BlockPos, BlockState> connectedExpected) {
        Map<BlockPos, BlockState> changes = new LinkedHashMap<>();
        expected.forEach((pos, wanted) -> {
            BlockState now = live.apply(pos);
            if (now != wanted && now != connectedExpected.apply(pos) && (!missingOnly || now.isAir() && !wanted.isAir())) {
                changes.put(pos, wanted);
            }
        });
        return changes;
    }
    public static Map<String, Integer> palette(Map<BlockPos, BlockState> live) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        live.values().stream().filter(s -> !s.isAir()).forEach(s -> counts.merge(id(s), 1, Integer::sum));
        Map<String, Integer> sorted = new LinkedHashMap<>();
        counts.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(128).forEach(e -> sorted.put(e.getKey(), e.getValue()));
        return sorted;
    }
}
