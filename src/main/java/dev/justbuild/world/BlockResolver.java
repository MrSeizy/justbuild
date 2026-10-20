package dev.justbuild.world;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.justbuild.plan.Operation;
import dev.justbuild.plan.Rasterizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Turns plan block specs into block states using the vanilla parser (namespace optional, no tags or NBT). */
public final class BlockResolver {
    /** Operations whose own blocks are valid, plus the specs that were not. */
    public record Result(List<Operation> operations, Set<String> invalid) {
    }

    /** Resolved states for a set of specs, plus the specs that did not parse. */
    public record Specs(Map<String, BlockState> states, Set<String> invalid) {
    }

    /** Blocks that act on their own when placed (spread, flow, or explode) and could reach past the build. */
    static final Set<Block> NOT_ALLOWED = Set.of(Blocks.FIRE, Blocks.SOUL_FIRE, Blocks.LAVA, Blocks.TNT);

    /** Blocks renamed by recent Minecraft versions; models trained on older ids still write the old names. */
    static final Map<String, String> RENAMED = Map.of(
            "chain", "iron_chain",        // 1.21.9, when copper chains arrived
            "grass", "short_grass",       // 1.20.3
            "grass_path", "dirt_path");   // 1.17

    private BlockResolver() {
    }

    /** Like {@link #parse} but refuses {@link #NOT_ALLOWED} blocks; null with a reason added to {@code invalid}. */
    private static BlockState placeable(String spec, Set<String> invalid) {
        Optional<BlockState> state = parse(spec);
        if (state.isEmpty()) {
            invalid.add(spec);
            return null;
        }
        if (NOT_ALLOWED.contains(state.get().getBlock())) {
            invalid.add(spec + " (not allowed)");
            return null;
        }
        return built(spec, state.get());
    }

    /**
     * Leaves the build places count as placed by a player unless the plan says otherwise, so bushes, hedges, and
     * canopies away from logs don't decay.
     */
    static BlockState built(String spec, BlockState state) {
        if (state.hasProperty(BlockStateProperties.PERSISTENT) && !spec.contains("persistent")) {
            return state.setValue(BlockStateProperties.PERSISTENT, true);
        }
        return state;
    }

    public static Optional<BlockState> parse(String spec) {
        String clean = renamed(spec.trim().toLowerCase(Locale.ROOT));
        Optional<BlockState> state = parseExact(clean);
        if (state.isEmpty()) {
            // Models often write the plural block ids in the singular: brick, stone_brick, deepslate_tile, oak_plank.
            String plural = plural(clean);
            if (plural != null) {
                state = parseExact(plural);
            }
        }
        return state;
    }

    /** {@code spec} with its id made plural when it ends in brick, tile, or plank; else null. */
    static String plural(String spec) {
        int bracket = spec.indexOf('[');
        String id = bracket < 0 ? spec : spec.substring(0, bracket);
        String name = id.substring(id.indexOf(':') + 1);
        boolean singular = name.equals("brick") || name.endsWith("_brick") || name.endsWith("_tile")
                || name.endsWith("_plank");
        return singular ? id + "s" + spec.substring(id.length()) : null;
    }

    private static Optional<BlockState> parseExact(String spec) {
        StringReader reader = new StringReader(spec);
        try {
            BlockState state = BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, reader, false).blockState();
            // The vanilla parser stops at the first character it does not understand; reject trailing junk such as NBT.
            return reader.canRead() ? Optional.empty() : Optional.of(state);
        } catch (CommandSyntaxException e) {
            return Optional.empty();
        }
    }

    /** The current id for an old one, keeping the namespace and any [properties]. */
    static String renamed(String spec) {
        int bracket = spec.indexOf('[');
        String id = bracket < 0 ? spec : spec.substring(0, bracket);
        String namespace = id.startsWith("minecraft:") ? "minecraft:" : "";
        String current = RENAMED.get(id.substring(namespace.length()));
        return current == null ? spec : namespace + current + spec.substring(id.length());
    }

    /** Drops operations whose block or trim is invalid. Voxel palettes are checked per cell by resolveSpecs. */
    public static Result resolve(List<Operation> operations) {
        Map<String, Boolean> valid = new HashMap<>();
        Set<String> invalid = new LinkedHashSet<>();
        List<Operation> kept = new ArrayList<>();
        for (Operation op : operations) {
            boolean ok = true;
            for (String spec : new String[] {op.block(), op.trim(), op.edge()}) {
                if (spec != null && !valid.computeIfAbsent(spec, s -> placeable(s, invalid) != null)) {
                    ok = false;
                }
            }
            if (ok) {
                kept.add(op);
            }
        }
        return new Result(List.copyOf(kept), Collections.unmodifiableSet(invalid));
    }

    /** Resolves every distinct spec, including ones shapes derive (stairs facings, slabs, palette blocks). */
    public static Specs resolveSpecs(Collection<String> specs) {
        Map<String, BlockState> states = new HashMap<>();
        states.put(Rasterizer.AIR, Blocks.AIR.defaultBlockState());
        Set<String> invalid = new LinkedHashSet<>();
        Set<String> tried = new java.util.HashSet<>();
        for (String spec : specs) {
            if (!states.containsKey(spec) && tried.add(spec)) {
                BlockState state = placeable(spec, invalid);
                if (state != null) {
                    states.put(spec, state);
                }
            }
        }
        return new Specs(Map.copyOf(states), Collections.unmodifiableSet(invalid));
    }
}
