package dev.justbuild.plan;

import java.util.List;

/** What the AI sent back: a question, a plan, a refusal, or a request for guides or world inspection. */
public sealed interface PlanResponse {
    record Question(String question, List<String> options) implements PlanResponse {
    }

    record Build(BuildPlan plan) implements PlanResponse {
    }

    record Declined(String reason) implements PlanResponse {
    }

    /** The guide ids (prompt.GuideLibrary) the AI wants to read before it plans. */
    record Guides(List<String> ids) implements PlanResponse {
    }

    record Inspect(InspectionRequest inspection) implements PlanResponse {
    }
    record Blocks(BlockQuery query) implements PlanResponse { }
}
