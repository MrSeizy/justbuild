package dev.justbuild.plan;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.Objects;

/**
 * A critique a request carries: the one a repair applies ({@code outcome} null), or, in a second review, the earlier
 * round's critique and what its repair did.
 */
public record CritiqueNote(Critique critique, RepairOutcome outcome) {
    public CritiqueNote {
        Objects.requireNonNull(critique, "critique");
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.add("critique", critique.toJson());
        if (outcome == null) o.add("outcome", JsonNull.INSTANCE);
        else o.addProperty("outcome", outcome.name());
        return o;
    }

    /** Unchecked, for PromptSpec.parse: a malformed note makes the whole request unreadable. */
    public static CritiqueNote parse(JsonObject o) {
        try {
            JsonElement outcome = o.get("outcome");
            return new CritiqueNote(CritiqueParser.fromJson(o.getAsJsonObject("critique")),
                    outcome == null || outcome.isJsonNull() ? null : RepairOutcome.valueOf(outcome.getAsString()));
        } catch (PlanException e) {
            throw new IllegalArgumentException(e.getMessage());
        }
    }
}
