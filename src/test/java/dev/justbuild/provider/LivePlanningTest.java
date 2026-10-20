package dev.justbuild.provider;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import dev.justbuild.plan.*;
import dev.justbuild.prompt.*;
import dev.justbuild.relay.PromptSpec;
import dev.justbuild.world.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Explicitly opt in: JUSTBUILD_LIVE_PROVIDERS=1 ./gradlew test --tests '*LivePlanningTest' --rerun-tasks. */
@EnabledIfEnvironmentVariable(named = "JUSTBUILD_LIVE_PROVIDERS", matches = "1")
class LivePlanningTest {
    @BeforeAll static void bootstrap() { McBootstrap.init(); }
    @Test void claudeSeesImagesAndRepairsDraft() throws Exception { exercise(new ClaudeCliProvider("")); }
    @Test void codexSeesImagesAndRepairsDraft() throws Exception { exercise(new CodexCliProvider("")); }

    private void exercise(BuildProvider provider) throws Exception {
        Path output = Path.of("build", "live-planning", provider.id()); Files.createDirectories(output);
        var box = new InspectionRequest(new Cell(0, 0, 0), new Cell(8, 0, 8));
        var source = VoxelVolume.capture(box, 4096, true, p -> p.x() == 2 && p.z() == 2 || p.x() == 6 && p.z() == 2
                || p.x() == 4 && p.z() == 6 ? "minecraft:gold_block" : "minecraft:air");
        var spec = PromptSpec.newBuild("minecraft:overworld", "minecraft:plains", PromptSpec.Placement.FRONT,
                null, null, "Count the isolated gold blocks in the attached source image, counting each block once across all views.",
                List.of(), 0, null, 1000).withFeedback(new PlanningFeedback(null, List.of(), source, null, null, null));
        Files.write(output.resolve("source.png"), PlanImages.render(source, "ORIGINAL source"));
        String observed = provider.complete(new PlanRequest("Read the attached image and return JSON with the number of distinct gold cubes. The four views show the same scene.",
                spec.request(), "{\"type\":\"object\",\"properties\":{\"count\":{\"type\":\"integer\"}},\"required\":[\"count\"],\"additionalProperties\":false}",
                "", Duration.ofMinutes(3), spec), new CancellationToken());
        Files.writeString(output.resolve("vision.json"), observed);
        assertEquals(3, JsonParser.parseString(observed).getAsJsonObject().get("count").getAsInt(), provider.id() + " image-only count");

        BuildPlan broken = new BuildPlan("Minimal column", "", List.of(Operation.set("minecraft:stone", new Cell(0, 4, -5))));
        var limits = new PlanLimits(200, 1000, 32);
        var height = LevelHeightAccessor.create(-64, 384);
        java.util.function.Function<BlockPos, net.minecraft.world.level.block.state.BlockState> world =
                p -> p.getY() < 0 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
        var check = PlanningCheck.check(broken, BlockPos.ZERO, Direction.NORTH, height, world, Map.of(), Map.of(), false,
                p -> true, null, BuildConstraints.NONE, limits);
        assertFalse(check.feedback().problems().isEmpty(), "The fixture must need repair");
        var review = PromptSpec.newBuild("minecraft:overworld", "minecraft:plains", PromptSpec.Placement.FRONT,
                null, null, "Exactly one plain stone column at x=0,z=-5, from y=0 through y=4 inclusive. No roofs, rooms, lights or decoration. Ground is y=-1.",
                List.of(), 0, null, 1000).review(PlanWriter.write(broken), 1, 1).withFeedback(check.feedback());
        Files.write(output.resolve("broken.png"), PlanImages.render(check.feedback().candidate(), "CANDIDATE draft"));
        String raw = provider.complete(new PlanRequest(review.systemPrompt(limits, Guidance.DetailLevel.QUICK), review.userContent(),
                BuildPlanSchema.JSON, "", Duration.ofMinutes(4), review), new CancellationToken());
        Files.writeString(output.resolve("repaired.json"), raw);
        var response = assertInstanceOf(PlanResponse.Build.class, PlanParser.parse(raw));
        var repaired = PlanningCheck.check(response.plan(), BlockPos.ZERO, Direction.NORTH, height, world, Map.of(), Map.of(), false,
                p -> true, null, BuildConstraints.NONE, limits);
        assertEquals(0, repaired.feedback().technicalFailures());
        assertEquals(5, repaired.changes().values().stream().filter(s -> !s.isAir()).count());
        for (int y = 0; y <= 4; y++) assertEquals(Blocks.STONE.defaultBlockState(), repaired.changes().get(new BlockPos(0, y, -5)));
        assertTrue(repaired.feedback().problems().isEmpty(), repaired.feedback().format());
        Files.write(output.resolve("repaired.png"), PlanImages.render(repaired.feedback().candidate(), "CANDIDATE repaired"));
        System.out.println(provider.id() + ": image-only count=3, floating draft repaired into exact minimalist 5-block column.");
    }
}
