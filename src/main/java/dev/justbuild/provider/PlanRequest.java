package dev.justbuild.provider;

import dev.justbuild.relay.PromptSpec;
import java.time.Duration;

/**
 * Everything a backend needs for one call. An empty model means the backend's default. {@code spec} is what
 * {@code userContent} was made from (null in tests); a player's own AI gets the spec, not the text.
 * {@code timeout} limits inactivity during CLI planning; HTTP providers retain their request/body deadlines.
 */
public record PlanRequest(String systemPrompt, String userContent, String schemaJson, String model, Duration timeout,
        PromptSpec spec) {
    /** Block-color evidence; the separate reviewer and its repair also see the candidate's four facades. */
    public java.util.List<byte[]> images() {
        if (spec == null) return java.util.List.of();
        boolean reviewer = spec.kind() == PromptSpec.Kind.CRITIQUE || spec.critique() != null;
        return dev.justbuild.prompt.PlanImages.render(spec.feedback(), reviewer);
    }
    public PlanRequest(String systemPrompt, String userContent, String schemaJson, String model, Duration timeout) {
        this(systemPrompt, userContent, schemaJson, model, timeout, null);
    }
}
