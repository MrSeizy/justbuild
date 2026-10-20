package dev.justbuild.plan;

import java.util.List;

public record BuildPlan(String title, String summary, List<Operation> operations, TextureSettings texture) {
    public BuildPlan(String title, String summary, List<Operation> operations) {
        this(title, summary, operations, TextureSettings.DEFAULT);
    }
    public BuildPlan { operations = List.copyOf(operations); texture = texture == null ? TextureSettings.DEFAULT : texture; }
    public BuildPlan withTexture(TextureSettings settings) { return new BuildPlan(title, summary, operations, settings); }
}
