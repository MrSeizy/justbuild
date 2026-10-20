package dev.justbuild.plan;

import java.util.LinkedHashSet;
import java.util.Set;

/** Materials participating in a procedural mix; ordinary solid blocks are unaffected. */
public final class PlanTextures {
    private PlanTextures() { }

    public static Set<String> materials(BuildPlan plan) {
        Set<String> result = new LinkedHashSet<>();
        for (Operation op : plan.operations()) {
            if (op.mix() == null || op.mix().isEmpty()) continue;
            add(result, op.block());
            for (String state : op.mix()) add(result, state);
        }
        return Set.copyOf(result);
    }

    private static void add(Set<String> result, String state) {
        String id = BlockSpecs.id(state);
        result.add(id.contains(":") ? id : "minecraft:" + id);
    }
}
