package dev.justbuild.plan;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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
        assertEquals(List.of("must_fix", "should_fix", "minor"),
                strings(fields.getAsJsonObject("severity").getAsJsonArray("enum")));
        assertEquals(List.of("object", "null"), strings(fields.getAsJsonObject("cell").getAsJsonArray("type")));
    }

    @Test
    void everyObjectSchemaIsStrictAtEveryDepth() {
        assertStrict(JsonParser.parseString(CritiqueSchema.JSON).getAsJsonObject(), "$");
    }

    private static void assertStrict(JsonObject schema, String path) {
        JsonElement type = schema.get("type");
        boolean isObject = type != null && (type.isJsonArray()
                ? strings(type.getAsJsonArray()).contains("object") : type.getAsString().equals("object"));
        if (isObject) {
            assertNotNull(schema.get("additionalProperties"), path + " additionalProperties");
            assertFalse(schema.get("additionalProperties").getAsBoolean(), path + " additionalProperties");
            assertEquals(List.copyOf(schema.getAsJsonObject("properties").keySet()),
                    strings(schema.getAsJsonArray("required")), path + " required");
        }
        if (schema.has("properties")) {
            schema.getAsJsonObject("properties").entrySet()
                    .forEach(e -> assertStrict(e.getValue().getAsJsonObject(), path + "." + e.getKey()));
        }
        if (schema.has("items")) {
            assertStrict(schema.getAsJsonObject("items"), path + "[]");
        }
    }

    @Test
    void leafTypesAreIntegersAndStrings() {
        JsonObject root = JsonParser.parseString(CritiqueSchema.JSON).getAsJsonObject();
        JsonObject properties = root.getAsJsonObject("properties");
        assertEquals("string", properties.getAsJsonObject("keep").getAsJsonObject("items").get("type").getAsString());
        JsonObject fields = properties.getAsJsonObject("problems").getAsJsonObject("items")
                .getAsJsonObject("properties");
        assertEquals("string", fields.getAsJsonObject("problem").get("type").getAsString());
        assertEquals("string", fields.getAsJsonObject("fix").get("type").getAsString());
        assertEquals("integer",
                fields.getAsJsonObject("operations").getAsJsonObject("items").get("type").getAsString());
        JsonObject cell = fields.getAsJsonObject("cell");
        assertEquals(List.of("x", "y", "z"), strings(cell.getAsJsonArray("required")));
        for (String axis : List.of("x", "y", "z")) {
            assertEquals("integer",
                    cell.getAsJsonObject("properties").getAsJsonObject(axis).get("type").getAsString());
        }
    }
}
