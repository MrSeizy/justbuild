package dev.justbuild.quality;

import com.google.gson.*;
import dev.justbuild.plan.*;
import dev.justbuild.prompt.*;
import dev.justbuild.provider.*;
import dev.justbuild.relay.PromptSpec;
import dev.justbuild.world.*;
import java.nio.file.*;
import dev.justbuild.config.JustBuildConfig;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;

/** Fixed, inspectable quality cases. Default execution is deterministic and never calls a provider. */
public final class QualityGalleryGameTest implements FabricClientGameTest {
    private static final PlanLimits SMALL_LIMITS = new PlanLimits(100, 2500, 32);
    private static PlanLimits limits(Scenario scenario) {
        if (!scenario.id().equals("castle")) return SMALL_LIMITS;
        var normal = new JustBuildConfig().limits();
        int operations = Integer.parseInt(System.getenv().getOrDefault("JUSTBUILD_GALLERY_CASTLE_OPERATIONS", String.valueOf(normal.maxOperations())));
        if (operations < 1 || operations > normal.maxOperations()) throw new IllegalArgumentException("Castle operation cap must be 1–" + normal.maxOperations());
        return new PlanLimits(operations, normal.maxBlocks(), normal.maxSize());
    }
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    private record Scenario(String id, String prompt, BuildPlan reference, BuildPlan baseline, BlockPos min, BlockPos max) {}
    private record Evaluation(JsonObject report, Map<BlockPos, BlockState> changes) {}
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    @Override public void runTest(ClientGameTestContext context) {
        String providerId = System.getenv().getOrDefault("JUSTBUILD_GALLERY_PROVIDER", "fixture");
        String imported = System.getenv("JUSTBUILD_GALLERY_PLANS");
        if (imported != null && !providerId.equals("fixture")) throw new AssertionError("Choose imported plans or a live provider, not both");
        BuildProvider provider = switch (providerId) {
            case "fixture" -> null;
            case "claude" -> new ClaudeCliProvider("");
            case "codex" -> new CodexCliProvider("");
            case "grok" -> new dev.justbuild.provider.GrokCliProvider("");
            default -> throw new AssertionError("JUSTBUILD_GALLERY_PROVIDER must be fixture, claude, codex or grok");
        };
        String mode = imported != null ? "imported" : provider == null ? "deterministic fixture" : "live provider";
        String model = System.getenv().getOrDefault("JUSTBUILD_GALLERY_MODEL", "");
        String label = System.getenv().getOrDefault("JUSTBUILD_GALLERY_LABEL", providerId);
        if (!label.matches("[A-Za-z0-9_-]{1,60}")) throw new AssertionError("Gallery label must be 1–60 letters, digits, _ or -");
        Guidance.DetailLevel detail = Guidance.DetailLevel.parse(System.getenv().getOrDefault("JUSTBUILD_GALLERY_DETAIL", "quick"))
                .orElseThrow(() -> new AssertionError("Gallery detail must be quick, detailed or masterpiece"));
        int repeats = Integer.parseInt(System.getenv().getOrDefault("JUSTBUILD_GALLERY_REPEATS", "1"));
        if (repeats < 1 || repeats > 5) throw new AssertionError("Gallery repeats must be 1–5");
        if (imported != null && detail.reviewRounds() > 0) throw new AssertionError("Imported plans require quick detail; no provider is selected to review them");
        List<Scenario> selected = selectedScenarios();
        Path output = Path.of(System.getProperty("justbuild.galleryDir", "quality-gallery"))
                .resolve(label + "-" + Instant.now().toString().replaceAll("[:.]", "-"));
        JsonObject report = new JsonObject(); var cases = new JsonArray();
        report.addProperty("format", 1); report.addProperty("label", label); report.addProperty("mode", mode);
        report.addProperty("provider", providerId); report.addProperty("model", model.isEmpty() ? "CLI default / fixture" : model);
        report.addProperty("createdAt", Instant.now().toString()); report.addProperty("revision", System.getProperty("justbuild.galleryRevision", "unknown"));
        report.addProperty("minecraft", "26.3"); report.addProperty("detail", detail.id()); report.addProperty("reviewRounds", detail.reviewRounds());
        report.addProperty("repeats", repeats);
        int timeoutSeconds = Integer.parseInt(System.getenv().getOrDefault("JUSTBUILD_GALLERY_TIMEOUT_SECONDS",
                String.valueOf(new JustBuildConfig().timeout().toSeconds())));
        if (timeoutSeconds < 1 || timeoutSeconds > 3600) throw new IllegalArgumentException("Gallery timeout must be 1–3600 seconds");
        var timeout = Duration.ofSeconds(timeoutSeconds);
        report.addProperty("timeoutSeconds", timeout.toSeconds());
        report.addProperty("timeoutMode", provider == null ? "none" : "idle");
        report.addProperty("guideHash", hash(GuideLibrary.defaults().all().stream().map(g -> g.id() + "\n" + g.text()).reduce("", String::concat)));
        report.add("cases", cases);
        boolean failed = false;
        try {
            Files.createDirectories(output);
            try (var bytecode = QualityGalleryGameTest.class.getResourceAsStream("QualityGalleryGameTest.class")) {
                report.addProperty("harnessHash", hashBytes(Objects.requireNonNull(bytecode).readAllBytes()));
            }
            try (var world = context.worldBuilder().create()) {
                world.getConnection().waitForChunksRender();
                String player = context.computeOnClient(mc -> mc.player.getName().getString());
                BlockPos origin = context.computeOnClient(mc -> mc.player.blockPosition().above(12));
                world.getServer().runCommand("gamemode spectator " + player);
                world.getServer().runCommand("time set day"); world.getServer().runCommand("weather clear");
                for (int repeat = 1; repeat <= repeats; repeat++) for (Scenario scenario : selected) {
                    // Reuse the same camera and prepared source position for every attempt.
                    // Keep large scenery beyond the ordinary cases' camera range, including later repeats.
                    BlockPos at = scenario.id().equals("castle") ? origin.offset(160, -12, 0) : origin;
                    String key = scenario.id() + (repeats > 1 ? "-" + repeat : "");
                    System.out.println("Gallery case " + key + " (" + providerId + ", " + detail.id() + ")");
                    long started = System.nanoTime();
                    long planningMillis = 0, guideSelectionMillis = 0;
                    boolean guidesOffered = provider != null && detail != Guidance.DetailLevel.QUICK;
                    JsonArray selectedGuides = new JsonArray();
                    String planPromptHash = null;
                    JsonObject draft = null;
                    JsonArray reviews = new JsonArray();
                    world.getServer().runOnServer(server -> prepare(server.overworld(), scenario, at));
                    String sourceHash = world.getServer().computeOnServer(server -> sourceHash(server.overworld(), scenario, at));
                    camera(context, world, player, scenario, at, false);
                    Files.copy(context.takeScreenshot("gallery-" + key + "-before"), output.resolve(key + "-before.png"));
                    BuildPlan plan = scenario.reference();
                    JsonObject row;
                    String promptHash = null;
                    try {
                        PromptSpec spec = spec(scenario).withGuides(guidesOffered, List.of());
                        String system = spec.systemPrompt(limits(scenario), detail), prompt = spec.userContent();
                        promptHash = hash(system + "\n" + prompt);
                        Files.writeString(output.resolve(key + "-prompt.txt"), system + "\n\n" + prompt);
                        if (imported != null) {
                            Path file = Path.of(imported).resolve(scenario.id() + ".json");
                            if (Files.size(file) > 1_000_000) throw new IllegalArgumentException("Imported plan is too large");
                            plan = requirePlan(Files.readString(file));
                        } else if (provider != null) {
                            long callStarted = System.nanoTime();
                            String raw;
                            try {
                                raw = provider.complete(new PlanRequest(system, prompt, BuildPlanSchema.JSON, model, timeout, spec),
                                        new CancellationToken(), progress(key + " initial"));
                                Files.writeString(output.resolve(key + "-initial-response.txt"), raw);
                                var response = PlanParser.parse(raw);
                                if (response instanceof PlanResponse.Guides wanted) {
                                    if (!guidesOffered) throw new PlanException("Guides were not offered in this run");
                                    guideSelectionMillis = elapsedMillis(callStarted);
                                    var read = GuideLibrary.defaults().pick(wanted.ids());
                                    if (read.isEmpty()) throw new PlanException("The provider requested no known guides");
                                    read.forEach(g -> selectedGuides.add(g.id()));
                                    Files.writeString(output.resolve(key + "-guides.json"), JSON.toJson(selectedGuides));
                                    spec = spec.withGuides(false, read.stream().map(GuideLibrary.Guide::id).toList());
                                    system = spec.systemPrompt(limits(scenario), detail); prompt = spec.userContent();
                                    planPromptHash = hash(system + "\n" + prompt);
                                    Files.writeString(output.resolve(key + "-plan-prompt.txt"), system + "\n\n" + prompt);
                                    System.out.println("Gallery " + key + " selected guides: " + selectedGuides);
                                    raw = provider.complete(new PlanRequest(system, prompt, BuildPlanSchema.JSON, model, timeout, spec),
                                            new CancellationToken(), progress(key + " draft"));
                                } else {
                                    planPromptHash = promptHash;
                                }
                                Files.writeString(output.resolve(key + "-response.txt"), raw);
                                plan = requirePlan(raw);
                            } finally { planningMillis = elapsedMillis(callStarted); }
                        }
                        // The menu is a planning tool, never another selection round during review.
                        spec = spec.withGuides(false, spec.guides());
                        system = spec.systemPrompt(limits(scenario), detail);
                        if (detail.reviewRounds() > 0) {
                            Files.writeString(output.resolve(key + "-draft.json"), PlanWriter.write(plan));
                            draft = renderPlan(context, world, player, scenario, plan, at, output, key + "-draft");
                            // Review evidence must describe the untouched source, never the placed draft.
                            world.getServer().runOnServer(server -> prepare(server.overworld(), scenario, at));
                            String restored = world.getServer().computeOnServer(server -> sourceHash(server.overworld(), scenario, at));
                            if (!sourceHash.equals(restored)) throw new AssertionError("Draft rendering changed the source scene");
                            BuildPlan first = plan;
                            PlanningFeedback evidence = world.getServer().computeOnServer(server -> evidence(server.overworld(), scenario, first, at));
                            for (int round = 1; round <= detail.reviewRounds(); round++) {
                                JsonObject review = new JsonObject(); reviews.add(review);
                                review.addProperty("round", round);
                                review.addProperty("mode", provider == null ? "fixture replay" : "live provider");
                                long reviewStarted = System.nanoTime();
                                String prefix = key + "-review-" + round;
                                try {
                                    PromptSpec reviewSpec = spec.review(PlanWriter.write(plan), round, detail.reviewRounds()).withFeedback(evidence);
                                    String reviewPrompt = reviewSpec.userContent();
                                    Files.writeString(output.resolve(prefix + "-prompt.txt"), system + "\n\n" + reviewPrompt);
                                    Files.writeString(output.resolve(prefix + "-feedback.json"), JSON.toJson(evidence.toJson()));
                                    review.addProperty("promptHash", hash(system + "\n" + reviewPrompt));
                                    review.addProperty("inputPlanHash", hash(PlanWriter.write(plan)));
                                    System.out.println("Gallery " + key + " review " + round);
                                    String raw = provider == null ? PlanWriter.write(plan)
                                            : provider.complete(new PlanRequest(system, reviewPrompt, BuildPlanSchema.JSON, model, timeout, reviewSpec), new CancellationToken(), progress(prefix));
                                    Files.writeString(output.resolve(prefix + "-response.txt"), raw);
                                    BuildPlan revised = requirePlan(raw);
                                    Files.writeString(output.resolve(prefix + ".json"), PlanWriter.write(revised));
                                    limits(scenario).check(revised);
                                    if (PlanSize.blocks(revised, limits(scenario).maxBlocks()) == PlanSize.OVER) throw new PlanException("Review exceeded the block limit");
                                    BuildPlan current = plan;
                                    var checked = world.getServer().computeOnServer(server -> reviewCheck(server.overworld(), scenario, current, revised, at));
                                    review.addProperty("technicalFailuresBefore", evidence.technicalFailures());
                                    review.addProperty("technicalFailuresAfter", checked.feedback().technicalFailures());
                                    review.addProperty("outputPlanHash", hash(PlanWriter.write(revised)));
                                    if (!checked.verdict().accepted()) {
                                        review.addProperty("status", "rejected: " + checked.verdict().reason());
                                    } else {
                                        review.addProperty("status", PlanWriter.write(plan).equals(PlanWriter.write(revised)) ? "unchanged" : "accepted");
                                        plan = revised; evidence = checked.feedback();
                                    }
                                } catch (Exception e) {
                                    review.addProperty("status", "failed; kept previous draft");
                                    review.addProperty("error", failureMessage(e, timeout));
                                } finally { review.addProperty("elapsedMillis", elapsedMillis(reviewStarted)); }
                                // Preserve completed calls even if a later review or screenshot fails.
                                Files.writeString(output.resolve(key + "-reviews.json"), JSON.toJson(reviews));
                            }
                        }
                        Files.writeString(output.resolve(key + ".json"), PlanWriter.write(plan));
                        row = renderPlan(context, world, player, scenario, plan, at, output, key);
                    } catch (Exception e) {
                        row = new JsonObject(); row.add("checks", new JsonArray());
                        check(row, "Plan response", false, failureMessage(e, timeout));
                        capture(context, world, player, scenario, at, output, key, row);
                    }
                    row.addProperty("maxOperations", limits(scenario).maxOperations()); row.addProperty("maxBlocks", limits(scenario).maxBlocks()); row.addProperty("maxSize", limits(scenario).maxSize());
                    if (scenario.id().equals("castle")) row.addProperty("siteSize", CastleScene.SIZE);
                    row.addProperty("id", key); row.addProperty("scenario", scenario.id()); row.addProperty("repeat", repeat);
                    row.addProperty("request", scenario.prompt()); row.addProperty("promptHash", promptHash);
                    row.addProperty("before", key + "-before.png");
                    row.addProperty("sourceHash", sourceHash);
                    row.addProperty("planningMillis", planningMillis); row.addProperty("elapsedMillis", elapsedMillis(started));
                    row.addProperty("guidesOffered", guidesOffered); row.add("selectedGuides", selectedGuides);
                    row.addProperty("guideSelectionMillis", guideSelectionMillis); row.addProperty("planPromptHash", planPromptHash);
                    row.add("reviews", reviews); if (draft != null) row.add("draft", draft);
                    boolean ok = passed(row);
                    row.addProperty("passed", ok); failed |= !ok; cases.add(row);
                    Files.writeString(output.resolve("results.json"), JSON.toJson(report));
                    System.out.println("Gallery " + key + ": " + (ok ? "PASS" : "NEEDS ATTENTION") + "; " + elapsedMillis(started) / 1000 + " s");
                }
            } finally { context.runOnClient(mc -> dev.justbuild.client.TestHud.hidden(mc, false)); }
            System.out.println("Quality gallery: " + output.toAbsolutePath());
            if (failed) throw new AssertionError("Quality acceptance failed; inspect " + output.resolve("results.json"));
        } catch (java.io.IOException e) { throw new AssertionError("Could not write quality gallery", e); }
    }

    private static List<Scenario> selectedScenarios() {
        String value = System.getenv("JUSTBUILD_GALLERY_CASES");
        if (value == null) return scenarios();
        var names = new LinkedHashSet<>(Arrays.asList(value.split(",", -1)));
        var available = new ArrayList<>(scenarios());
        available.add(new Scenario("castle", CastleScene.PROMPT, CastleScene.fixture(), null, CastleScene.MIN, CastleScene.MAX));
        List<Scenario> selected = available.stream().filter(s -> names.remove(s.id())).toList();
        if (!names.isEmpty() || selected.isEmpty()) throw new AssertionError("Unknown or empty gallery case selection: " + value);
        return selected;
    }

    private static long elapsedMillis(long started) { return (System.nanoTime() - started) / 1_000_000; }
    private static ProgressListener progress(String label) {
        return new ProgressListener() {
            private int reported;
            @Override public void phase(Phase phase) { System.out.println("Gallery " + label + ": " + phase); }
            @Override public void operations(int count) {
                if (count >= reported + 25) { reported = count; System.out.println("Gallery " + label + ": " + count + " operations written"); }
            }
            @Override public void tokens(long input, long output) { System.out.println("Gallery " + label + ": " + input + " input / " + output + " output tokens"); }
        };
    }
    private static boolean passed(JsonObject row) {
        return row.getAsJsonArray("checks").asList().stream().allMatch(c -> c.getAsJsonObject().get("passed").getAsBoolean());
    }
    private static String failureMessage(Exception error, Duration timeout) {
        return error instanceof PlanException || error instanceof IllegalArgumentException
                ? "The response was not a valid bounded build plan."
                : error instanceof ProviderException && ("No CLI output for " + timeout.toSeconds() + " s").equals(error.getMessage())
                    ? "The CLI produced no output for " + timeout.toSeconds() + " seconds."
                : error instanceof ProviderException && ("No response after " + timeout.toSeconds() + " s").equals(error.getMessage())
                    ? "The provider exceeded the " + timeout.toSeconds() + "-second request allowance."
                    : "The provider or fixture could not complete this case.";
    }

    private static PlanningFeedback evidence(net.minecraft.server.level.ServerLevel level, Scenario scenario, BuildPlan plan, BlockPos at) {
        return planningCheck(level, scenario, plan, at).feedback();
    }

    private record ReviewedCandidate(PlanningFeedback feedback, ReviewCheck.Verdict verdict) { }

    private static ReviewedCandidate reviewCheck(net.minecraft.server.level.ServerLevel level, Scenario scenario,
            BuildPlan draft, BuildPlan revised, BlockPos at) {
        var before = planningCheck(level, scenario, draft, at);
        var after = planningCheck(level, scenario, revised, at);
        return new ReviewedCandidate(after.feedback(), ReviewCheck.compare(before, after,
                sourcePlan(level, scenario, at), Level.OVERWORLD, level::getBlockState));
    }

    private static Map<BlockPos, BlockState> sourcePlan(net.minecraft.server.level.ServerLevel level, Scenario scenario, BlockPos at) {
        try {
            return scenario.baseline() == null ? Map.of()
                    : PlanPipeline.run(scenario.baseline(), at, Direction.NORTH, level, level::getBlockState, limits(scenario).maxBlocks()).placements();
        } catch (PlanException error) { throw new AssertionError(error); }
    }

    private static PlanningCheck.Result planningCheck(net.minecraft.server.level.ServerLevel level, Scenario scenario, BuildPlan plan, BlockPos at) {
        Map<BlockPos, BlockState> before = sourcePlan(level, scenario, at);
        Map<BlockPos, BlockState> baseline = new LinkedHashMap<>();
        before.keySet().forEach(p -> baseline.put(p, Blocks.AIR.defaultBlockState()));
        BuildZone zone = BuildZone.between(Level.OVERWORLD, at.offset(scenario.min()), at.offset(scenario.max()))
                .height(at.getY() + scenario.min().getY(), at.getY() + scenario.max().getY());
        return PlanningCheck.check(plan, at, Direction.NORTH, level, level::getBlockState, baseline, before,
                scenario.baseline() != null, p -> level.getBlockEntity(p) == null, zone, BuildConstraints.NONE, limits(scenario));
    }

    private static JsonObject renderPlan(ClientGameTestContext context, TestSingleplayerContext world, String player,
            Scenario scenario, BuildPlan plan, BlockPos at, Path output, String key) throws java.io.IOException {
        JsonObject row;
        try {
            Evaluation evaluation = world.getServer().computeOnServer(server -> evaluate(server.overworld(), scenario, plan, at));
            row = evaluation.report();
            world.getServer().runOnServer(server -> {
                place(server.overworld(), evaluation.changes());
                inspectPlaced(server.overworld(), scenario, at, evaluation.report());
            });
        } catch (Exception error) {
            row = new JsonObject(); row.add("checks", new JsonArray());
            check(row, "Plan validity", false, "The plan could not be placed within the gallery's safe limits.");
        }
        row.addProperty("planHash", hash(PlanWriter.write(plan))); row.addProperty("passed", passed(row));
        capture(context, world, player, scenario, at, output, key, row);
        return row;
    }

    private static void capture(ClientGameTestContext context, TestSingleplayerContext world, String player,
            Scenario scenario, BlockPos at, Path output, String key, JsonObject row) throws java.io.IOException {
        camera(context, world, player, scenario, at, false); context.waitTicks(8);
        Files.copy(context.takeScreenshot("gallery-" + key + "-after"), output.resolve(key + "-after.png"));
        row.addProperty("after", key + "-after.png");
        if (scenario.id().equals("castle")) CastleScene.capture(context, world, player, at, output, key, row);
        if (scenario.id().equals("interior")) {
            camera(context, world, player, scenario, at, true);
            Files.copy(context.takeScreenshot("gallery-" + key + "-room"), output.resolve(key + "-room.png"));
            row.addProperty("detailImage", key + "-room.png");
        }
    }
    private static BuildPlan requirePlan(String text) throws PlanException {
        var response = PlanParser.parse(text);
        if (response instanceof PlanResponse.Build build) return build.plan();
        throw new IllegalArgumentException("Expected a build response");
    }
    private static PromptSpec spec(Scenario scenario) {
        String request = scenario.prompt() + " Coordinates are local: north is negative Z; ground is y=-1 unless the scenario says otherwise. Return the complete build plan after any offered guide-selection round; no questions or world-inspection requests."
                + " Keep all changes inside " + scenario.min().toShortString() + " through " + scenario.max().toShortString() + ".";
        var spec = scenario.baseline() == null
                ? PromptSpec.newBuild("minecraft:overworld", "minecraft:plains", PromptSpec.Placement.FRONT, null, null, request, List.of(), 0, null, limits(scenario).maxBlocks())
                : PromptSpec.edit("minecraft:overworld", "minecraft:plains", "A small cottage", List.of(), PlanWriter.write(scenario.baseline()), request, List.of(), 0, limits(scenario).maxBlocks());
        return spec;
    }
    private static void prepare(net.minecraft.server.level.ServerLevel level, Scenario scenario, BlockPos at) {
        if (scenario.id().equals("castle")) { CastleScene.prepare(level, at); return; }
        for (BlockPos p : BlockPos.betweenClosed(at.offset(-8, -5, -8), at.offset(20, 15, 16))) {
            int x = p.getX() - at.getX(), y = p.getY() - at.getY(), z = p.getZ() - at.getZ();
            BlockState state = y < -1 ? Blocks.STONE.defaultBlockState() : y == -1 ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.AIR.defaultBlockState();
            if (scenario.id().equals("bridge") && (x < 3 || x > 9) && y == 0) state = Blocks.GRASS_BLOCK.defaultBlockState();
            if (scenario.id().equals("bridge") && x >= 3 && x <= 9 && z >= -6 && z <= 6 && y == -1) state = Blocks.WATER.defaultBlockState();
            if (scenario.id().equals("terrain") && x >= 4 && x <= 12 && z >= -2 && z <= 10 && y >= 0 && y <= 1)
                state = y == 1 ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.DIRT.defaultBlockState();
            level.setBlock(p, state, FLAGS);
        }
        if (scenario.baseline() != null) try {
            place(level, PlanPipeline.run(scenario.baseline(), at, Direction.NORTH, level, level::getBlockState, limits(scenario).maxBlocks()).placements());
        } catch (PlanException e) { throw new AssertionError(e); }
        level.setBlock(at.offset(16, 0, 10), Blocks.DIAMOND_BLOCK.defaultBlockState(), FLAGS);
        if (scenario.id().equals("interior") || scenario.id().equals("editing")) {
            BlockPos chest = at.offset(1, 1, 4); level.setBlock(chest, Blocks.CHEST.defaultBlockState(), FLAGS);
            ((Container) level.getBlockEntity(chest)).setItem(0, new ItemStack(Items.DIAMOND, 7));
        }
    }
    private static Evaluation evaluate(net.minecraft.server.level.ServerLevel level, Scenario scenario, BuildPlan plan, BlockPos at) throws PlanException {
        JsonObject row = new JsonObject(); row.add("checks", new JsonArray());
        limits(scenario).check(plan);
        var result = PlanPipeline.run(plan, at, Direction.NORTH, level, level::getBlockState, limits(scenario).maxBlocks());
        Map<BlockPos, BlockState> ours = scenario.baseline() == null ? Map.of()
                : PlanPipeline.run(scenario.baseline(), at, Direction.NORTH, level, level::getBlockState, limits(scenario).maxBlocks()).placements();
        Map<BlockPos, BlockState> changes = new LinkedHashMap<>(result.placements());
        ours.keySet().stream().filter(p -> !changes.containsKey(p)).forEach(p -> changes.put(p, Blocks.AIR.defaultBlockState()));
        changes.entrySet().removeIf(e -> e.getValue().equals(level.getBlockState(e.getKey())));
        BuildRegion scope = new BuildRegion(Level.OVERWORLD, at.offset(scenario.min()), at.offset(scenario.max()));
        check(row, "Limits and block validity", result.invalid().isEmpty() && result.outsideWorld() == 0 && changes.size() <= limits(scenario).maxBlocks(),
                plan.operations().size() + "/" + limits(scenario).maxOperations() + " operations; " + changes.size() + "/" + limits(scenario).maxBlocks() + " changed cells; maximum axis " + limits(scenario).maxSize() + ".");
        check(row, "Selected area", changes.keySet().stream().allMatch(scope::contains), "Every requested change must stay inside the scenario bounds.");
        if (scenario.id().equals("interior")) {
            var inside = new BuildRegion(Level.OVERWORLD, at.offset(1, 1, 1), at.offset(7, 3, 5));
            check(row, "Preserve shell", changes.keySet().stream().allMatch(inside::contains), "Furnishing must leave every floor, wall, window, door and roof cell unchanged.");
        }
        if (scenario.id().equals("editing")) {
            check(row, "Preserve geometry", ours.keySet().equals(result.placements().keySet()) && ours.entrySet().stream().allMatch(e ->
                    e.getValue().is(Blocks.OAK_PLANKS) ? result.placements().get(e.getKey()).is(Blocks.BRICKS) : e.getValue().equals(result.placements().get(e.getKey()))),
                    "Only oak wall planks become bricks; all other block states and the footprint remain exact.");
        }
        if (!scenario.id().equals("castle")) check(row, "Requested materials", switch (scenario.id()) {
            case "cottage" -> count(result.placements(), Blocks.OAK_PLANKS) >= 40 && count(result.placements(), Blocks.SPRUCE_STAIRS) >= 20;
            case "interior" -> count(result.placements(), Blocks.BED.pick(net.minecraft.world.item.DyeColor.RED)) >= 2 && count(result.placements(), Blocks.CRAFTING_TABLE) >= 1 && count(result.placements(), Blocks.BOOKSHELF) >= 2;
            case "editing" -> count(result.placements(), Blocks.BRICKS) >= 40 && count(result.placements(), Blocks.OAK_PLANKS) == 0;
            case "bridge" -> count(result.placements(), Blocks.OAK_PLANKS) >= 39 && count(result.placements(), Blocks.OAK_FENCE) >= 20;
            default -> count(result.placements(), Blocks.STONE_BRICKS) >= 63;
        }, "Required structural and furnishing materials must be present in useful amounts.");
        var warnings = new JsonArray(); BuildQuality.details(changes, level::getBlockState).forEach(i -> warnings.add(i.message())); row.add("advisories", warnings);
        // Bad imported/live plans remain reviewable, but never apply out-of-scope cells in the fixture world.
        changes.keySet().removeIf(p -> !scope.contains(p));
        var protectedResult = Protection.filter(changes, ours, level::getBlockEntity, level::getBlockState);
        row.addProperty("protectedCellsKept", protectedResult.kept()); row.addProperty("changedCells", protectedResult.placements().size());
        return new Evaluation(row, protectedResult.placements());
    }
    private static void inspectPlaced(net.minecraft.server.level.ServerLevel level, Scenario s, BlockPos at, JsonObject row) {
        if (s.id().equals("castle")) { CastleScene.inspect(level, at, row); return; }
        boolean preserved = level.getBlockState(at.offset(16, 0, 10)).is(Blocks.DIAMOND_BLOCK);
        if (s.id().equals("interior") || s.id().equals("editing")) {
            var entity = level.getBlockEntity(at.offset(1, 1, 4));
            preserved &= entity instanceof Container c && c.getItem(0).is(Items.DIAMOND) && c.getItem(0).getCount() == 7;
        }
        check(row, "Protected world data", preserved, "The external marker and any existing chest inventory remain intact.");
        boolean cottage = Set.of("cottage", "interior", "editing").contains(s.id());
        if (cottage) {
            BlockPos door = at.offset(4, 1, 0);
            check(row, "Usable entrance", level.getBlockState(door).is(Blocks.OAK_DOOR) && level.getBlockState(door.above()).is(Blocks.OAK_DOOR)
                    && clear(level, door.north()) && clear(level, door.south()), "A north-facing door has a clear two-block approach on both sides.");
        }
        BuildRegion walking = cottage ? new BuildRegion(Level.OVERWORLD, at, at.offset(8, 2, 6))
                : s.id().equals("bridge") ? new BuildRegion(Level.OVERWORLD, at.offset(0, 0, -1), at.offset(12, 4, 1))
                : new BuildRegion(Level.OVERWORLD, at.offset(0, 2, 0), at.offset(8, 6, 6));
        var route = Walkability.inspect(walking, level::getBlockState);
        check(row, "Walkability", route.spaces() > 0 && route.unreachable() == 0 && route.markers().isEmpty(),
                route.reachable() + "/" + route.spaces() + " standing spaces on the walking floor reachable; " + route.markers().size() + " problem markers.");
        if (s.id().equals("bridge")) {
            boolean continuous = true;
            for (int x = 0; x <= 12; x++) for (int z = -1; z <= 1; z++) continuous &= level.getBlockState(at.offset(x, 0, z)).is(Blocks.OAK_PLANKS) && clear(level, at.offset(x, 1, z));
            check(row, "Full-width crossing", continuous && level.getBlockState(at.offset(6, -1, 5)).is(Blocks.WATER), "A clear three-block-wide deck crosses the stream; water outside the crossing stays intact.");
        }
        if (s.id().equals("terrain")) {
            boolean supported = true;
            for (int x = 0; x <= 8; x++) for (int z = 0; z <= 6; z++) supported &= level.getBlockState(at.offset(x, 2, z)).is(Blocks.STONE_BRICKS)
                    && !level.getBlockState(at.offset(x, 1, z)).isAir();
            check(row, "Slope support", supported && level.getBlockState(at.offset(11, 1, 8)).is(Blocks.GRASS_BLOCK), "Every terrace cell is supported; the hillside outside the work area remains grass.");
        }
    }
    private static void place(net.minecraft.server.level.ServerLevel level, Map<BlockPos, BlockState> changes) {
        var access = BlockAccess.of(level, true);
        PlacementOrder.order(changes).forEach(p -> access.set(p.pos(), p.state()));
        changes.keySet().forEach(access::refresh);
    }
    private static boolean clear(net.minecraft.server.level.ServerLevel level, BlockPos at) { return !level.getBlockState(at).isSolidRender() && !level.getBlockState(at.above()).isSolidRender(); }
    private static long count(Map<BlockPos, BlockState> values, Block block) { return values.values().stream().filter(s -> s.is(block)).count(); }
    private static void check(JsonObject row, String name, boolean passed, String detail) { var c = new JsonObject(); c.addProperty("name", name); c.addProperty("passed", passed); c.addProperty("detail", detail); row.getAsJsonArray("checks").add(c); }
    private static void camera(ClientGameTestContext c, TestSingleplayerContext world, String player, Scenario scenario, BlockPos at, boolean inside) {
        if (scenario.id().equals("castle")) { CastleScene.frontCamera(c, world, player, at); return; }
        double x = at.getX() + (inside ? 6.5 : -8), y = at.getY() + (inside ? 1.1 : 11), z = at.getZ() + (inside ? 1.5 : -10);
        world.getServer().runCommand("time set day"); world.getServer().runCommand("weather clear");
        world.getServer().runCommand("tp " + player + " " + x + " " + y + " " + z + (inside ? " 40 14" : " -42 30"));
        c.runOnClient(mc -> { mc.gui.setScreen(null); dev.justbuild.client.TestHud.hidden(mc, true); }); c.waitTicks(12);
        world.getConnection().waitForChunksRender();
    }
    private static String sourceHash(net.minecraft.server.level.ServerLevel level, Scenario scenario, BlockPos at) {
        StringBuilder source = new StringBuilder();
        for (BlockPos relative : BlockPos.betweenClosed(scenario.id().equals("castle") ? CastleScene.PREP_MIN : new BlockPos(-8, -5, -8),
                scenario.id().equals("castle") ? CastleScene.PREP_MAX : new BlockPos(20, 15, 16))) {
            BlockPos pos = at.offset(relative);
            source.append(relative.toShortString()).append('=').append(level.getBlockState(pos)).append('\n');
            if (level.getBlockEntity(pos) instanceof Container inventory) for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                var item = inventory.getItem(slot);
                if (!item.isEmpty()) source.append(slot).append(':').append(BuiltInRegistries.ITEM.getKey(item.getItem())).append('x').append(item.getCount()).append('\n');
            }
        }
        return hash(source.toString());
    }
    private static String hash(String value) {
        return hashBytes(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    private static String hashBytes(byte[] value) {
        try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    private static List<Operation> cottage(String wall) {
        var ops = new ArrayList<Operation>();
        ops.add(Operation.fill("stone_bricks", new Cell(0, 0, 0), new Cell(8, 0, 6), false));
        ops.add(Operation.fill(wall, new Cell(0, 1, 0), new Cell(8, 3, 0), false));
        ops.add(Operation.fill(wall, new Cell(0, 1, 6), new Cell(8, 3, 6), false));
        ops.add(Operation.fill(wall, new Cell(0, 1, 1), new Cell(0, 3, 5), false));
        ops.add(Operation.fill(wall, new Cell(8, 1, 1), new Cell(8, 3, 5), false));
        ops.add(Operation.set("oak_door[facing=north]", new Cell(4, 1, 0)));
        ops.add(Operation.set("oak_door[facing=north,half=upper]", new Cell(4, 2, 0)));
        for (int x : new int[] {2, 6}) ops.add(Operation.set("glass", new Cell(x, 2, 0)));
        ops.add(Operation.roof("spruce_stairs", "spruce_planks", new Cell(-1, 4, -1), new Cell(9, 4, 7), "gable", "z"));
        ops.add(Operation.fill("stone_bricks", new Cell(3, 0, -5), new Cell(5, 0, -1), false));
        return ops;
    }
    private static List<Scenario> scenarios() {
        BuildPlan shell = new BuildPlan("Oak cottage", "", cottage("oak_planks"));
        var interior = new ArrayList<>(shell.operations());
        interior.add(Operation.set("red_bed[facing=east]", new Cell(2, 1, 4)));
        interior.add(Operation.set("crafting_table", new Cell(6, 1, 4)));
        interior.add(Operation.fill("bookshelf", new Cell(7, 1, 2), new Cell(7, 1, 3), false));
        var bridge = new ArrayList<Operation>();
        bridge.add(Operation.fill("oak_planks", new Cell(0, 0, -1), new Cell(12, 0, 1), false));
        for (int z : new int[] {-2, 2}) {
            bridge.add(Operation.fill("oak_planks", new Cell(0, 0, z), new Cell(12, 0, z), false));
            bridge.add(Operation.fill("oak_fence", new Cell(0, 1, z), new Cell(12, 1, z), false));
        }
        for (int x : new int[] {1, 6, 11}) bridge.add(Operation.fill("oak_log", new Cell(x, -2, -2), new Cell(x, -1, 2), false));
        return List.of(
            new Scenario("cottage", "Build a 9 by 7 oak-plank cottage: stone-brick floor y=0, walls y=1..3, spruce gable roof starting y=4. A two-block-high north door at (4,1,0), glass windows at (2,2,0) and (6,2,0), and a three-wide straight stone approach x=3..5,z=-5..-1. Keep the room walkable.", shell, null, new BlockPos(-1, -2, -5), new BlockPos(9, 12, 7)),
            new Scenario("interior", "Furnish this cottage with a red bed, crafting table and two bookshelves. Preserve its exact shell, approach and windows. Keep the chest at (1,1,4), including its diamonds. Only change interior x=1..7,y=1..3,z=1..5. Keep the doorway and circulation clear.", new BuildPlan("Furnished cottage", "", interior), shell, new BlockPos(-1, -2, -5), new BlockPos(9, 12, 7)),
            new Scenario("bridge", "Build an oak bridge with a flat, clear three-wide deck x=0..12,y=0,z=-1..1, fence rails at z=-2 and z=2, and support piers. Banks reach y=0. Water fills x=3..9,y=-1,z=-6..6 above stone y=-2. Preserve water outside the crossing.", new BuildPlan("Oak bridge", "", bridge), null, new BlockPos(0, -2, -2), new BlockPos(12, 4, 2)),
            new Scenario("terrain", "Make a level stone-brick terrace x=0..8,y=2,z=0..6. Ground is y=-1 except the raised hillside x=4..12,z=-2..10 which reaches y=1. Fill supports down to the existing ground. Add a three-wide stair approach x=3..5: south-facing steps at z=-2,y=0 and z=-1,y=1. Preserve the hillside outside the terrace.", new BuildPlan("Supported terrace", "", List.of(Operation.foundation("stone_bricks", new Cell(0, 1, 0), new Cell(8, 1, 6)), Operation.fill("stone_bricks", new Cell(0, 2, 0), new Cell(8, 2, 6), false), Operation.fill("stone_brick_stairs[facing=south]", new Cell(3, 0, -2), new Cell(5, 0, -2), false), Operation.fill("stone_brick_stairs[facing=south]", new Cell(3, 1, -1), new Cell(5, 1, -1), false))), null, new BlockPos(0, -3, -2), new BlockPos(8, 4, 6)),
            new Scenario("editing", "Replace only the cottage's oak-plank walls with bricks. Preserve exact geometry and all other block states, including spruce roof, door, windows, floor and approach. Keep the existing chest at (1,1,4) and its diamonds.", new BuildPlan("Brick cottage", "", cottage("bricks")), shell, new BlockPos(-1, -2, -5), new BlockPos(9, 12, 7)));
    }
}
