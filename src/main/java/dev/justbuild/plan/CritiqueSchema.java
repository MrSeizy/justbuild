package dev.justbuild.plan;

import com.google.gson.JsonObject;

/**
 * JSON Schema a reviewer must answer with: findings only, no geometry. Every field is required (nullable where
 * optional) and objects are closed, like BuildPlanSchema, so Claude --json-schema, Codex --output-schema, OpenAI
 * strict json_schema and the Anthropic-compatible provider all accept it. Counts and lengths are enforced by
 * CritiqueParser, not the schema.
 */
public final class CritiqueSchema {
    public static final String JSON = schema().toString();

    private CritiqueSchema() {
    }

    private static JsonObject schema() {
        JsonObject operations = BuildPlanSchema.type("array");
        operations.add("items", BuildPlanSchema.type("integer"));
        JsonObject problem = new JsonObject();
        problem.add("severity", BuildPlanSchema.enumOf("string", "must_fix", "should_fix", "minor"));
        problem.add("category", BuildPlanSchema.enumOf("string", "request", "access", "structure", "massing", "roof",
                "facade", "materials", "interior", "setting"));
        problem.add("operations", operations);
        problem.add("cell", BuildPlanSchema.nullableObject(
                BuildPlanSchema.property("x", BuildPlanSchema.type("integer")),
                BuildPlanSchema.property("y", BuildPlanSchema.type("integer")),
                BuildPlanSchema.property("z", BuildPlanSchema.type("integer"))));
        problem.add("problem", BuildPlanSchema.type("string"));
        problem.add("fix", BuildPlanSchema.type("string"));
        JsonObject problems = BuildPlanSchema.type("array");
        problems.add("items", BuildPlanSchema.object(problem));
        JsonObject keep = BuildPlanSchema.type("array");
        keep.add("items", BuildPlanSchema.type("string"));
        JsonObject properties = new JsonObject();
        properties.add("kind", BuildPlanSchema.enumOf("string", "critique"));
        properties.add("verdict", BuildPlanSchema.enumOf("string", "ok", "revise"));
        properties.add("problems", problems);
        properties.add("keep", keep);
        return BuildPlanSchema.object(properties);
    }
}
