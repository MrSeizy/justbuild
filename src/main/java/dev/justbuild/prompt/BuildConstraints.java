package dev.justbuild.prompt;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.justbuild.plan.PlanException;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

/** Bounded, structured requirements. Floor count is a design target, not a semantic floor detector. */
public record BuildConstraints(int maxHeight, int targetFloors, List<String> materials) {
    public static final BuildConstraints NONE = new BuildConstraints(0, 0, List.of());
    public BuildConstraints {
        materials = List.copyOf(materials);
        if (maxHeight < 0 || maxHeight > 256 || targetFloors < 0 || targetFloors > 64 || materials.size() > 32)
            throw new IllegalArgumentException("Use height 0–256, floor target 0–64, and at most 32 materials; 0 means unset.");
        for (String material : materials) if (!material.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || material.length() > 128)
            throw new IllegalArgumentException("Allowed materials must be plain block identifiers.");
        if (materials.stream().distinct().count() != materials.size())
            throw new IllegalArgumentException("Allowed materials must be distinct.");
    }

    public String format() {
        if (equals(NONE)) return "";
        return "\n\nBuild requirements: " + (maxHeight == 0 ? "" : "no blocks above y=" + (maxHeight - 1) + "; ")
                + (targetFloors == 0 ? "" : "design for " + targetFloors + " usable floors; ")
                + (materials.isEmpty() ? "" : "new or replaced non-air blocks must use these block IDs: "
                        + String.join(", ", materials) + ". Existing unchanged blocks may keep their materials. ")
                + "Keep locked details unchanged. Height and material restrictions are checked before placement.";
    }

    public void check(BlockPos origin, Map<BlockPos, BlockState> proposed, Map<BlockPos, BlockState> before)
            throws PlanException {
        for (var entry : proposed.entrySet()) {
            if (entry.getValue().isAir()) continue;
            if (maxHeight > 0 && (long) entry.getKey().getY() - origin.getY() >= maxHeight)
                throw new PlanException("The build exceeds its " + maxHeight + "-block height limit at "
                        + entry.getKey().toShortString() + ".");
            if (!materials.isEmpty() && !entry.getValue().equals(before.get(entry.getKey()))
                    && !materials.contains(BuiltInRegistries.BLOCK.getKey(entry.getValue().getBlock()).toString()))
                throw new PlanException("A new block at " + entry.getKey().toShortString() + " is outside the allowed materials.");
        }
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject(); o.addProperty("maxHeight", maxHeight); o.addProperty("targetFloors", targetFloors);
        JsonArray palette = new JsonArray(); materials.forEach(palette::add); o.add("materials", palette); return o;
    }

    public static BuildConstraints parse(JsonObject o) {
        JsonArray values = o.getAsJsonArray("materials");
        if (values.size() > 32) throw new IllegalArgumentException("Too many allowed materials.");
        return new BuildConstraints(Footprint.integer(o.get("maxHeight")), Footprint.integer(o.get("targetFloors")),
                values.asList().stream().map(v -> {
                    if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isString())
                        throw new IllegalArgumentException("Materials must be block IDs.");
                    return v.getAsString();
                }).toList());
    }
}
