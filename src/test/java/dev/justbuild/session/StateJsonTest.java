package dev.justbuild.session;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.justbuild.config.JustBuildConfig;
import java.util.List;
import org.junit.jupiter.api.Test;

class StateJsonTest {
    static JustBuildConfig config() {
        JustBuildConfig config = JustBuildConfig.defaults();
        config.activate("Codex");
        config.setModel("gpt-6-sol");
        config.profile("OpenAI-compatible").orElseThrow().apiKey = "sk-secret-123";
        config.profile("Anthropic-compatible").orElseThrow().apiKey = "sk-secret-anthropic";
        config.profile("Anthropic-compatible").orElseThrow().maxTokens = 24000;
        config.style = "nordic";
        return config;
    }

    @Test
    void carriesSettingsChoicesAndRecentBuilds() {
        JsonObject state = JsonParser.parseString(StateJson.build(config(), List.of(
                new StateJson.BuildSummary("e4c91fbd", "Nordic Fishing Hut", 2, "a small fishing hut"),
                new StateJson.BuildSummary("0a1b2c3d", "Tower", 1, "a tower")), true, StateJson.Ai.SERVER)).getAsJsonObject();
        assertEquals("codex", state.get("provider").getAsString());
        assertEquals("Codex", state.get("profile").getAsString());
        assertEquals("gpt-6-sol", state.get("model").getAsString());
        assertEquals("detailed", state.get("detail").getAsString());
        assertEquals("nordic", state.get("style").getAsString());
        assertEquals(5, state.getAsJsonArray("profileNames").size());
        assertEquals(3, state.getAsJsonArray("details").size());
        assertEquals(12, state.getAsJsonArray("styles").size());
        assertEquals("e4c91fbd", state.get("latest").getAsString());
        JsonObject first = state.getAsJsonArray("builds").get(0).getAsJsonObject();
        assertEquals("Nordic Fishing Hut", first.get("title").getAsString());
        assertEquals(2, first.get("versions").getAsInt());
        assertEquals("a small fishing hut", first.get("request").getAsString());
    }

    @Test
    void stateNamesTheReviewerAndItsChoices() {
        JustBuildConfig config = config();
        JsonObject same = JsonParser.parseString(StateJson.build(config, List.of(), true, StateJson.Ai.SERVER)).getAsJsonObject();
        assertEquals("same", same.get("reviewer").getAsString());
        assertEquals("same", same.getAsJsonArray("reviewerChoices").get(0).getAsString());
        assertEquals(config.profileNames().size() + 1, same.getAsJsonArray("reviewerChoices").size());
        config.reviewerProfile = "Codex";
        assertEquals("Codex", JsonParser.parseString(StateJson.build(config, List.of(), true, StateJson.Ai.SERVER))
                .getAsJsonObject().get("reviewer").getAsString());
    }

    @Test
    void reviewerChoicesLeaveOutAProfileNamedSame() {
        JustBuildConfig config = config();
        config.profiles.add(new JustBuildConfig.ModelProfile("SAME", "claude", ""));
        JsonArray choices = JsonParser.parseString(StateJson.build(config, List.of(), true, StateJson.Ai.SERVER))
                .getAsJsonObject().getAsJsonArray("reviewerChoices");
        assertEquals(config.profileNames().size(), choices.size());
        assertFalse(choices.toString().contains("SAME"));
    }

    @Test
    void namesTheDimensionOfBuildsElsewhere() {
        JsonArray builds = JsonParser.parseString(StateJson.build(config(), List.of(
                new StateJson.BuildSummary("e4c91fbd", "Basalt Hut", 1, "a hut", "minecraft:the_nether"),
                new StateJson.BuildSummary("0a1b2c3d", "Tower", 1, "a tower")), true, StateJson.Ai.SERVER))
                .getAsJsonObject().getAsJsonArray("builds");
        assertEquals("minecraft:the_nether", builds.get(0).getAsJsonObject().get("elsewhere").getAsString());
        assertFalse(builds.get(1).getAsJsonObject().has("elsewhere"), "builds here carry no dimension");
    }

    @Test
    void managersSeeProfileDetailsButNeverTheKey() {
        String json = StateJson.build(config(), List.of(), true, StateJson.Ai.SERVER);
        assertFalse(json.contains("sk-secret"));
        JsonObject state = JsonParser.parseString(json).getAsJsonObject();
        assertTrue(state.get("manage").getAsBoolean());
        JsonArray profiles = state.getAsJsonArray("profiles");
        JsonObject openai = profiles.get(2).getAsJsonObject();
        assertEquals("OpenAI-compatible", openai.get("name").getAsString());
        assertEquals("openai", openai.get("provider").getAsString());
        assertEquals("https://api.openai.com/v1", openai.get("baseUrl").getAsString());
        assertTrue(openai.get("hasKey").getAsBoolean());
        assertFalse(profiles.get(0).getAsJsonObject().get("hasKey").getAsBoolean());
        assertFalse(openai.has("apiKey"));
        JsonObject anthropic = profiles.get(4).getAsJsonObject();
        assertEquals("anthropic", anthropic.get("provider").getAsString());
        assertEquals(24000, anthropic.get("maxTokens").getAsInt());
        assertTrue(anthropic.get("hasKey").getAsBoolean());
        assertFalse(anthropic.has("apiKey"));
        assertFalse(anthropic.has("reasoningEffort"));
    }

    @Test
    void otherPlayersSeeOnlyNames() {
        JsonObject state = JsonParser.parseString(StateJson.build(config(), List.of(), false, StateJson.Ai.SERVER)).getAsJsonObject();
        assertFalse(state.get("manage").getAsBoolean());
        JsonObject openai = state.getAsJsonArray("profiles").get(2).getAsJsonObject();
        assertEquals("OpenAI-compatible", openai.get("name").getAsString());
        assertFalse(openai.has("baseUrl"));
        assertFalse(openai.has("hasKey"));
        JsonObject anthropic = state.getAsJsonArray("profiles").get(4).getAsJsonObject();
        assertFalse(anthropic.has("baseUrl"));
        assertFalse(anthropic.has("hasKey"));
        assertFalse(anthropic.has("maxTokens"));
    }

    @Test
    void saysWhichAiBuildsUse() {
        JsonObject state = JsonParser.parseString(StateJson.build(config(), List.of(), false,
                new StateJson.Ai("player", "Claude (Claude CLI)"))).getAsJsonObject();
        assertEquals("require", state.get("playerAi").getAsString());
        assertEquals("player", state.get("aiSource").getAsString());
        assertEquals("Claude (Claude CLI)", state.get("aiLabel").getAsString());
        JsonObject server = JsonParser.parseString(StateJson.build(config(), List.of(), false, StateJson.Ai.SERVER))
                .getAsJsonObject();
        assertEquals("server", server.get("aiSource").getAsString());
        assertEquals("Codex", server.get("aiLabel").getAsString());
    }

    @Test
    void noBuildsMeansNoLatest() {
        JsonObject state = JsonParser.parseString(StateJson.build(JustBuildConfig.defaults(), List.of(), true, StateJson.Ai.SERVER))
                .getAsJsonObject();
        assertFalse(state.has("latest"));
        assertEquals(0, state.getAsJsonArray("builds").size());
    }

    @Test void selectionCheckpointsAndRetainedPlansAreExplicit() {
        var workspace = new StateJson.Workspace("abc12345", "Lakeside House", List.of("Original"),
                List.of(new StateJson.DesignSummary("def12345", "Cottage")), "Bedroom extension");
        JsonObject state = JsonParser.parseString(StateJson.build(config(), List.of(), true, StateJson.Ai.SERVER,
                null, false, workspace)).getAsJsonObject();
        assertEquals("abc12345", state.get("selectedBuild").getAsString());
        assertEquals("Lakeside House", state.get("selectedTitle").getAsString());
        assertEquals("Original", state.getAsJsonArray("checkpoints").get(0).getAsString());
        assertEquals("Cottage", state.getAsJsonArray("designs").get(0).getAsJsonObject().get("name").getAsString());
        assertEquals("Bedroom extension", state.get("retainedPlan").getAsString());
        assertFalse(state.toString().contains("sk-secret"));
    }

    @Test void firstPointHasAnOutlineButIsNotAnEditablePart() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        var point = dev.justbuild.world.BuildRegion.between(net.minecraft.world.level.Level.OVERWORLD,
                new net.minecraft.core.BlockPos(4, 70, 8), new net.minecraft.core.BlockPos(4, 70, 8));
        var tools = new StateJson.Tools(null, true, true, List.of(), null, null, List.of(), null, null, point);
        var workspace = new StateJson.Workspace(null, null, List.of(), List.of(), null, tools);
        JsonObject state = JsonParser.parseString(StateJson.build(config(), List.of(), true, StateJson.Ai.SERVER,
                null, false, workspace)).getAsJsonObject();
        assertTrue(state.get("selectingPart").getAsBoolean());
        assertEquals(point.toJson(), state.getAsJsonObject("partOutline"));
        assertFalse(state.has("part"));
        assertFalse(state.has("partLabel"));
    }
}
