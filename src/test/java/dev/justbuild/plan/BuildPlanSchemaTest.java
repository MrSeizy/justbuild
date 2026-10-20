package dev.justbuild.plan;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;

class BuildPlanSchemaTest {
    @Test
    void schemaIsStrictWithOneVariantPerOpType() {
        JsonObject root = JsonParser.parseString(BuildPlanSchema.JSON).getAsJsonObject();
        assertFalse(root.get("additionalProperties").getAsBoolean());
        assertEquals(List.of("kind", "inspection", "blockQuery", "question", "options", "title", "summary", "operations", "guides", "texture"), strings(root.get("required")));
        assertEquals(List.of("build", "question", "decline", "guides", "inspect", "blocks"),
                strings(root.getAsJsonObject("properties").getAsJsonObject("kind").get("enum")));
        var variants = root.getAsJsonObject("properties").getAsJsonObject("operations").getAsJsonObject("items")
                .getAsJsonArray("anyOf");
        assertEquals(OpType.values().length, variants.size());
        for (int i = 0; i < variants.size(); i++) {
            JsonObject variant = variants.get(i).getAsJsonObject();
            OpType type = OpType.values()[i];
            assertFalse(variant.get("additionalProperties").getAsBoolean());
            assertEquals(List.of(type.id()), strings(variant.getAsJsonObject("properties").getAsJsonObject("op").get("enum")));
            List<String> expected = new ArrayList<>(List.of("op"));
            expected.addAll(BuildPlanSchema.FIELDS.get(type));
            expected.addAll(List.of("repeat", "mirror"));
            assertEquals(expected, strings(variant.get("required")), type.id());
            assertEquals(expected, List.copyOf(variant.getAsJsonObject("properties").keySet()), type.id());
        }
    }

    private static List<String> strings(JsonElement array) {
        return StreamSupport.stream(array.getAsJsonArray().spliterator(), false).map(JsonElement::getAsString).toList();
    }
}
