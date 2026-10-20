package dev.justbuild.prompt;

import java.util.List;

/** What the AI needs to revise an existing build. */
public record EditContext(String originalRequest, List<String> earlierChanges, String currentPlanJson, String change) {
}
