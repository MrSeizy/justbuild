package dev.justbuild.relay;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import dev.justbuild.plan.BuildPlan;
import dev.justbuild.plan.PlanException;
import dev.justbuild.plan.PlanParser;
import dev.justbuild.plan.PlanResponse;
import dev.justbuild.plan.PlanSanitizer;
import dev.justbuild.plan.PlanWriter;
import dev.justbuild.prompt.GuideLibrary;
import java.util.List;

/**
 * What the player's game sends back: the AI's answer parsed and written again, so only a build plan (real block ids and
 * shape words, a short title and summary), a short question with options, a short decline, guide ids, or numeric
 * inspection corners reaches the server. {@code planKey} identifies a returned plan for later reviews and shrinks.
 * {@code localMessage} is only for this player's chat: it must never be serialized into a network response.
 */
public record RelayOutput(String json, String question, String planKey, String localMessage) {
    public RelayOutput(String json, String question, String planKey) {
        this(json, question, planKey, null);
    }

    static final int MAX_TITLE = 60;
    static final int MAX_SUMMARY = 240;
    static final int MAX_QUESTION = 240;
    static final int MAX_OPTION = 60;
    /** A decline has no building data the server needs; keep its explanation in the player's game. */
    static final String DECLINED = "The player's AI declined this build request.";

    public static RelayOutput normalize(String raw) throws PlanException {
        PlanResponse response = parse(raw);
        return switch (response) {
            case PlanResponse.Blocks b -> {
                JsonObject o = base("blocks"); o.add("blockQuery", b.query().toJson());
                yield new RelayOutput(o.toString(), null, null);
            }
            case PlanResponse.Inspect i -> {
                JsonObject o = base("inspect");
                o.add("inspection", i.inspection().toJson());
                yield new RelayOutput(o.toString(), null, null);
            }
            case PlanResponse.Build build -> {
                BuildPlan plan = PlanSanitizer.sanitize(build.plan(), PromptCheck::knownBlock);
                String title = PlanParser.visible(plan.title(), MAX_TITLE);
                String json = PlanWriter.write(new BuildPlan(title.isEmpty() ? "Untitled build" : title,
                        PlanParser.visible(plan.summary(), MAX_SUMMARY), plan.operations(), plan.texture()));
                yield new RelayOutput(json, null, PromptCheck.planKey(json));
            }
            case PlanResponse.Question q -> {
                JsonObject o = base("question");
                String question = PlanParser.visible(q.question(), MAX_QUESTION);
                if (question.isEmpty()) throw new PlanException("the AI returned an empty question");
                o.addProperty("question", question);
                JsonArray options = new JsonArray();
                q.options().stream().map(option -> PlanParser.visible(option, MAX_OPTION)).filter(option -> !option.isEmpty())
                        .forEach(options::add);
                o.add("options", options);
                yield new RelayOutput(o.toString(), question, null);
            }
            case PlanResponse.Declined d -> {
                JsonObject o = base("decline");
                o.add("question", JsonNull.INSTANCE);
                o.add("options", JsonNull.INSTANCE);
                o.addProperty("summary", DECLINED);
                yield new RelayOutput(o.toString(), null, null, "Your AI declined this build: "
                        + PlanParser.visible(d.reason(), 400));
            }
            case PlanResponse.Guides g -> {
                JsonObject o = base("guides");
                o.add("question", JsonNull.INSTANCE);
                o.add("options", JsonNull.INSTANCE);
                o.add("summary", JsonNull.INSTANCE);
                // Unknown ids have no use to the server and must not become an extra free-text response channel.
                List<String> known = GuideLibrary.defaults().pick(g.ids()).stream().map(GuideLibrary.Guide::id).toList();
                if (known.isEmpty()) throw new PlanException("the AI requested no known building guides");
                JsonArray ids = new JsonArray();
                known.forEach(ids::add);
                o.add("guides", ids);
                yield new RelayOutput(o.toString(), null, null);
            }
        };
    }

    /** Parser diagnostics can quote arbitrary response fields; only the private cause retains those details. */
    private static PlanResponse parse(String raw) throws PlanException {
        try {
            return PlanParser.parse(raw);
        } catch (PlanException | RuntimeException e) {
            PlanException failure = new PlanException("the AI response is not a usable build plan");
            failure.initCause(e);
            throw failure;
        }
    }

    private static JsonObject base(String kind) {
        JsonObject o = new JsonObject();
        o.addProperty("kind", kind);
        o.add("title", JsonNull.INSTANCE);
        o.add("operations", JsonNull.INSTANCE);
        return o;
    }
}
