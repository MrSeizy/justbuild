package dev.justbuild.plan;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A plan reduced to what a plan can say: real block ids and the shape words its operations take. Used where a plan
 * crosses between a server and a player's AI to remove arbitrary text from block fields and voxel symbols. The
 * title and summary remain text; even constrained building data can encode information.
 */
public final class PlanSanitizer {
    /** Stands in for anything that isn't a block; it never resolves, so placement skips it like any unknown block. */
    public static final String UNKNOWN_BLOCK = "unknown_block";
    private static final Set<String> STYLES = Stream.of(PlanParser.ROUND_STYLES, PlanParser.PORTAL_STYLES,
            PlanParser.ROOF_STYLES, PlanParser.ARCH_STYLES).flatMap(Set::stream).collect(Collectors.toUnmodifiableSet());

    private PlanSanitizer() {
    }

    public static BuildPlan sanitize(BuildPlan plan, Predicate<String> knownBlock) {
        return new BuildPlan(plan.title(), plan.summary(), plan.operations().stream()
                .map(op -> sanitize(op, knownBlock)).toList(), new TextureSettings(plan.texture().seed(),
                        plan.texture().weights().entrySet().stream().filter(e -> knownBlock.test(e.getKey()))
                                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue))));
    }

    /** For a plan the player's AI will read but didn't write: its summary is the server's text, so it goes. */
    public static BuildPlan withoutSummary(BuildPlan plan) {
        return new BuildPlan(plan.title(), "", plan.operations(), plan.texture());
    }

    private static Operation sanitize(Operation op, Predicate<String> known) {
        VoxelData voxels = voxels(op.layers(), op.palette(), known);
        return new Operation(op.op(), block(op.block(), known), op.from(), op.to(), op.radius(), op.height(),
                op.hollow(), block(op.trim(), known), word(op.style(), STYLES), word(op.axis(), PlanParser.AXES),
                word(op.direction(), PlanParser.DIRECTIONS), op.radii(), voxels.palette(),
                voxels.layers(), block(op.target(), known), op.repeat(), op.mirror(), block(op.edge(), known), op.pitch(),
                op.rim(), op.eaves(), op.flare(), blocks(op.mix(), known), block(op.beam(), known),
                blocks(op.finial(), known));
    }

    private static String block(String spec, Predicate<String> known) {
        return spec == null ? null : known.test(spec) ? spec : UNKNOWN_BLOCK;
    }

    private static List<String> blocks(List<String> specs, Predicate<String> known) {
        return specs == null ? null : specs.stream().map(s -> block(s, known)).toList();
    }

    private record VoxelData(Map<Character, String> palette, List<List<String>> layers) {
    }

    /**
     * Symbols are representation, not building data. Give each used material one deterministic symbol and remove
     * unused entries, so duplicate stone symbols cannot spell arbitrary text without changing the build. Both space
     * and '.' leave a cell alone. Actual geometry and material choices are still information channels.
     */
    private static VoxelData voxels(List<List<String>> layers, Map<Character, String> palette,
            Predicate<String> known) {
        if (palette == null) return new VoxelData(null, layers == null ? null : emptyLayers(layers));
        Map<Character, String> materials = new LinkedHashMap<>();
        palette.forEach((symbol, spec) -> materials.put(symbol, block(spec, known)));
        Set<String> used = new TreeSet<>();
        if (layers != null) {
            for (List<String> layer : layers) for (String row : layer) for (char symbol : row.toCharArray()) {
                if (symbol != '.' && symbol != ' ' && materials.containsKey(symbol)) used.add(materials.get(symbol));
            }
        }
        // An empty voxel operation still needs a palette to round-trip through PlanParser. Its cells remain empty.
        if (used.isEmpty()) used.add(UNKNOWN_BLOCK);
        Map<String, Character> symbols = new LinkedHashMap<>();
        Map<Character, String> cleanPalette = new LinkedHashMap<>();
        // Cover the same single-char symbol space as PlanParser; no new material-count limit. JSON escapes any
        // control symbols needed by unusually large palettes, and the assignment depends only on materials.
        int next = 'A';
        for (String material : used) {
            while ((char) next == '.' || (char) next == ' ') next++;
            char symbol = (char) next++;
            symbols.put(material, symbol);
            cleanPalette.put(symbol, material);
        }
        List<List<String>> cleanLayers = layers == null ? null : layers.stream().map(layer -> layer.stream()
                .map(row -> {
                    StringBuilder clean = new StringBuilder(row.length());
                    for (char symbol : row.toCharArray()) {
                        String material = symbol == '.' || symbol == ' ' ? null : materials.get(symbol);
                        clean.append(material == null ? '.' : symbols.get(material));
                    }
                    return clean.toString();
                }).toList()).toList();
        return new VoxelData(Map.copyOf(cleanPalette), cleanLayers);
    }

    private static List<List<String>> emptyLayers(List<List<String>> layers) {
        return layers.stream().map(layer -> layer.stream().map(row -> ".".repeat(row.length())).toList()).toList();
    }

    private static String word(String value, Set<String> vocabulary) {
        return value != null && vocabulary.contains(value) ? value : null;
    }
}
