package dev.justbuild.plan;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** String helpers for block specs like "minecraft:oak_stairs[facing=north,half=bottom]". */
public final class BlockSpecs {
    private BlockSpecs() {
    }

    /** Namespaced id without states: " Minecraft:OAK_STAIRS[facing=north]" → "minecraft:oak_stairs". */
    public static String id(String spec) {
        String s = spec.trim().toLowerCase(Locale.ROOT);
        int bracket = s.indexOf('[');
        String id = bracket < 0 ? s : s.substring(0, bracket);
        return id.contains(":") ? id : "minecraft:" + id;
    }

    public static boolean isStairs(String spec) {
        return id(spec).endsWith("_stairs");
    }

    public static boolean sameBlock(String a, String b) {
        return id(a).equals(id(b));
    }

    /** Sets block-state properties on a spec, overriding values it already has. */
    public static String withProperties(String spec, Map<String, String> properties) {
        String s = spec.trim();
        int bracket = s.indexOf('[');
        String base = bracket < 0 ? s : s.substring(0, bracket);
        Map<String, String> merged = new LinkedHashMap<>();
        if (bracket >= 0) {
            String inner = s.substring(bracket + 1, s.endsWith("]") ? s.length() - 1 : s.length());
            for (String pair : inner.split(",")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    merged.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
                }
            }
        }
        merged.putAll(properties);
        StringBuilder out = new StringBuilder(base).append('[');
        merged.forEach((k, v) -> out.append(k).append('=').append(v).append(','));
        out.setLength(out.length() - 1);
        return out.append(']').toString();
    }

    /** The bottom slab of a stairs block: "oak_stairs" → "oak_slab[type=bottom]" (namespace kept as written). */
    /** Blocks without a _log, _wood, nether stem, or _hyphae name that still take an axis. */
    private static final java.util.Set<String> PILLARS = java.util.Set.of("basalt", "polished_basalt", "quartz_pillar",
            "purpur_pillar", "bone_block", "hay_block", "deepslate", "bamboo_block", "stripped_bamboo_block",
            "muddy_mangrove_roots", "iron_chain", "copper_chain");

    /** {@code spec} lying along {@code axis} when it is a log-like block written without a state; otherwise as is. */
    public static String withAxis(String spec, String axis) {
        String s = spec.trim();
        if (s.indexOf('[') >= 0) {
            return s;
        }
        String name = s.substring(s.indexOf(':') + 1);
        // Only nether stems: mushroom and plant stems have no axis.
        boolean pillar = name.endsWith("_log") || name.endsWith("_wood") || name.endsWith("crimson_stem")
                || name.endsWith("warped_stem") || name.endsWith("_hyphae") || PILLARS.contains(name);
        return pillar ? s + "[axis=" + axis + "]" : s;
    }

    /** Woods whose stairs are made from planks. */
    private static final java.util.Set<String> WOODS = java.util.Set.of("oak", "spruce", "birch", "jungle", "acacia",
            "dark_oak", "mangrove", "cherry", "pale_oak", "bamboo", "crimson", "warped");

    /** The full block a stairs block is cut from (spruce_stairs → spruce_planks, brick_stairs → bricks). */
    public static String fullFor(String stairsSpec) {
        String s = stairsSpec.trim();
        int bracket = s.indexOf('[');
        String base = bracket < 0 ? s : s.substring(0, bracket);
        int colon = base.indexOf(':');
        String ns = colon < 0 ? "" : base.substring(0, colon + 1);
        String name = base.substring(colon + 1).replaceFirst("_stairs$", "");
        if (WOODS.contains(name)) {
            return ns + name + "_planks";
        }
        if (name.endsWith("brick") || name.endsWith("tile")) {
            return ns + name + "s";
        }
        return ns + switch (name) {
            case "quartz", "purpur" -> name + "_block";
            default -> name;
        };
    }

    public static String slabFor(String stairsSpec) {
        String s = stairsSpec.trim();
        int bracket = s.indexOf('[');
        String base = bracket < 0 ? s : s.substring(0, bracket);
        return base.replaceFirst("_stairs$", "_slab") + "[type=bottom]";
    }
}
