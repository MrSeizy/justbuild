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
        if (!(o.get("critique") instanceof JsonObject critique)) throw new IllegalArgumentException("a note needs a critique");
        JsonElement outcome = o.get("outcome");
        RepairOutcome parsed = null;
        if (outcome != null && !outcome.isJsonNull()) {
            if (!outcome.isJsonPrimitive() || !outcome.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException("a note's outcome must be text");
            }
            try {
                parsed = RepairOutcome.valueOf(outcome.getAsString());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("a note has an unknown outcome");
            }
        }
        try {
            return new CritiqueNote(CritiqueParser.fromJson(critique), parsed);
        } catch (PlanException e) {
            throw new IllegalArgumentException(e.getMessage());
        }
    }
}
