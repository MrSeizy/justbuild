package dev.justbuild.plan;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;

class CritiqueSchemaTest {
    private static List<String> strings(JsonArray array) {
        return StreamSupport.stream(array.spliterator(), false).map(e -> e.getAsString()).toList();
    }

    @Test
    void everyFieldIsRequiredAndClosedSoStrictModesAcceptIt() {
        JsonObject root = JsonParser.parseString(CritiqueSchema.JSON).getAsJsonObject();
        assertEquals(List.of("kind", "verdict", "problems", "keep"), strings(root.getAsJsonArray("required")));
        assertFalse(root.get("additionalProperties").getAsBoolean());
        JsonObject properties = root.getAsJsonObject("properties");
        assertEquals(List.of("critique"), strings(properties.getAsJsonObject("kind").getAsJsonArray("enum")));
        assertEquals(List.of("ok", "revise"), strings(properties.getAsJsonObject("verdict").getAsJsonArray("enum")));
        JsonObject problem = properties.getAsJsonObject("problems").getAsJsonObject("items");
        assertEquals(List.of("severity", "category", "operations", "cell", "problem", "fix"),
                strings(problem.getAsJsonArray("required")));
        assertFalse(problem.get("additionalProperties").getAsBoolean());
        JsonObject fields = problem.getAsJsonObject("properties");
        assertEquals(Arrays.stream(Critique.Category.values()).map(c -> c.name().toLowerCase(Locale.ROOT)).toList(),
                strings(fields.getAsJsonObject("category").getAsJsonArray("enum")));
        assertEquals(List.of("must_fix", "should_fix", "minor"), strings(fields.getAsJsonObject("severity").getAsJsonArray("enum")));
        assertEquals(List.of("object", "null"), strings(fields.getAsJsonObject("cell").getAsJsonArray("type")));
    }
}
