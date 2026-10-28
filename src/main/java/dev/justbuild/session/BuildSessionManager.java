package dev.justbuild.session;

import dev.justbuild.session.PanelStateBuilder.RouteReport;

import dev.justbuild.command.Chat;
import dev.justbuild.config.JustBuildConfig;
import dev.justbuild.config.ConfigException;
import dev.justbuild.config.ConfigStore;
import dev.justbuild.config.ProfileEdits;
import dev.justbuild.command.JustBuildCommand;
import dev.justbuild.mixin.common.NameCacheAccessor;
import dev.justbuild.mixin.common.NameCacheEntryAccessor;
import dev.justbuild.net.AiProgressPayload;
import dev.justbuild.net.AiRequestPayload;
import dev.justbuild.net.AiResponsePayload;
import dev.justbuild.net.ModelsPayload;
import dev.justbuild.net.PreviewPayload;
import dev.justbuild.net.GhostPreviewPayload;
import dev.justbuild.world.ChangePreview;
import dev.justbuild.world.LocalEdits;
import dev.justbuild.world.PlanningCheck;
import dev.justbuild.prompt.PlanningFeedback;
import dev.justbuild.prompt.VoxelVolume;
import dev.justbuild.world.ProjectStore;
import dev.justbuild.world.Walkability;
import dev.justbuild.prompt.ProjectTheme;
import dev.justbuild.prompt.InteriorRequests;
import dev.justbuild.world.BuildRegion;
import dev.justbuild.world.BuilderWorksite;
import dev.justbuild.world.BuildQuality;
import dev.justbuild.world.PartSelections;
import dev.justbuild.world.RegionStore;
import dev.justbuild.world.DesignThumbnail;
import dev.justbuild.world.DesignTransfer;
import dev.justbuild.prompt.PlanningAreas;
import dev.justbuild.prompt.AlternativeRequests;
import dev.justbuild.net.ProfilesPayload;
import dev.justbuild.net.StatePayload;
import dev.justbuild.plan.BuildPlan;
import dev.justbuild.plan.BuildPlanSchema;
import dev.justbuild.plan.Cell;
import dev.justbuild.plan.InspectionRequest;
import dev.justbuild.prompt.WorldSnapshot;
import dev.justbuild.world.BuildZone;
import dev.justbuild.world.Ground;
import dev.justbuild.world.WorldInspector;
import dev.justbuild.plan.PlanException;
import dev.justbuild.plan.PlanParser;
import dev.justbuild.plan.PlanResponse;
import dev.justbuild.plan.PlanRotation;
import dev.justbuild.plan.PlanSize;
import dev.justbuild.plan.PlanTooLargeException;
import dev.justbuild.plan.PlanWriter;
import dev.justbuild.prompt.Exchange;
import dev.justbuild.prompt.GuideLibrary;
import dev.justbuild.prompt.Guidance;
import dev.justbuild.prompt.TemplateLibrary;
import dev.justbuild.provider.BuildProvider;
import dev.justbuild.provider.CancellationToken;
import dev.justbuild.provider.CancelledException;
import dev.justbuild.provider.PlanRequest;
import dev.justbuild.provider.ProviderException;
import dev.justbuild.provider.Providers;
import dev.justbuild.relay.AiSource;
import dev.justbuild.relay.PromptCheck;
import dev.justbuild.relay.PromptSpec;
import dev.justbuild.relay.RelayJson;
import dev.justbuild.world.ActionHistory;
import dev.justbuild.world.BlockAccess;
import dev.justbuild.world.BuildRecord;
import dev.justbuild.world.BuildRegistry;
import dev.justbuild.world.BuildStore;
import dev.justbuild.world.DesignStore;
import dev.justbuild.plan.PlanMaterials;
import dev.justbuild.world.Diff;
import dev.justbuild.world.Placement;
import dev.justbuild.world.PlacementJob;
import dev.justbuild.world.PlacementOrder;
import dev.justbuild.world.PlacementScheduler;
import dev.justbuild.world.PlanPipeline;
import dev.justbuild.world.PlanSnapshot;
import dev.justbuild.world.PlacementSafety;
import dev.justbuild.world.Protection;
import dev.justbuild.world.TerrainSampler;
import dev.justbuild.world.ZoneWand;
import java.io.IOException;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.Comparator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns conversations, build records, the placement queue, and undo history for one server. All public methods run
 * on the server thread; AI calls run on worker threads and return via server.execute.
 */
public final class BuildSessionManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("JustBuild");
    private static final String CONSOLE = "console";
    private static final double EDIT_REACH = ZoneWand.REACH;
    /** Look at a spot at least this far away and the build goes there; closer, it goes in front of the player. */
    private static final double MIN_TARGET_DISTANCE = 6;
    private static final Duration ROUTE_REPORT_TTL = Duration.ofMinutes(2);
    private static final Duration UNDO_CONFIRM_TTL = Duration.ofSeconds(60);

    private final MinecraftServer server;
    private final dev.justbuild.construction.ConstructionManager construction;
    private final ConfigStore configStore;
    private volatile JustBuildConfig config;
    private final PlanStore planStore;
    private final PreviewStore previewStore;
    private final BuildStore store;
    private final DesignStore designStore;
    private final DesignTransfer designTransfer;
    private final RegionStore regionStore;
    private final ProjectStore projectStore;
    private final Map<String, ProjectStore.Project> projects = new LinkedHashMap<>();
    private String projectLoadProblem;
    private final List<RegionStore.Locked> lockedAreas = new java.util.ArrayList<>();
    private String lockLoadProblem;
    private String buildLoadProblem;
    private final Map<String, DesignStore.Design> designs = new LinkedHashMap<>();
    private final Path historyFile;
    private final PlayerPrefs prefs;
    private final ProfileTestService profileTests = new ProfileTestService(this::onServer);
    private final ExecutorService workers =
            Executors.newFixedThreadPool(2, Thread.ofPlatform().daemon().name("JustBuild worker-", 1).factory());
    /** Requests planned in players' games only wait, so each gets its own virtual thread instead of a worker. */
    private final ExecutorService relayWorkers =
            Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("JustBuild relay-", 1).factory());
    private final RelayHub relay;
    /** {@code fromBuild}: loaded by selecting a build (its saved area), not drawn by the player. */
    private record ZoneSelection(ResourceKey<Level> dimension, BlockPos first, BuildZone zone, boolean fromBuild) {
        ZoneSelection(ResourceKey<Level> dimension, BlockPos first, BuildZone zone) {
            this(dimension, first, zone, false);
        }
    }

    private record PolygonDraft(ResourceKey<Level> dimension, int y, List<dev.justbuild.prompt.Footprint.Point> points,
            ZoneSelection previous) { }
    private record PartSelection(String buildId, ResourceKey<Level> dimension, BlockPos first, BuildRegion region) {
        BuildRegion outline() {
            return region != null ? region : BuildRegion.between(dimension, first, first);
        }
        String nextInstruction() {
            return "Right-click another block with the Builder Wand in Box mode to include it, or clear the box.";
        }
    }
    private record RetainedPlan(ActiveRequest request, BuildPlan plan, BuildRecord.Version original, Instant expires) { }
    private final Map<PlacementJob, JobInfo> jobs = new HashMap<>();
    private final Map<UUID, dev.justbuild.net.BuilderBotsPayload> lastBuilderBots = new HashMap<>();
    /** Build ids with an edit request in flight or a job queued; they refuse another edit or undo. */
    private final Set<String> busy = new HashSet<>();
    /** Server-thread generation for cached live placement ownership checks. */
    private long buildAccessRevision;
    private static final class Alternatives {
        final String base;
        final int count;
        final List<PendingPlacement> choices = new java.util.ArrayList<>();
        ActiveRequest current;
        int index = 1;
        int selected;
        boolean collecting = true;
        Alternatives(String base, int count) { this.base = base; this.count = count; }
    }
    private record QualityInfo(PendingPlacement preview, List<BuildQuality.Issue> issues) { }
    private record QualityUndo(PendingPlacement repaired, PendingPlacement original) { }
    private final Set<String> completedThumbnails = new HashSet<>();
    private final VersionThumbnails versionThumbnails = new VersionThumbnails(this::onServer, completedThumbnails::add);
    /** Server thread: notice keys already posted this session (each plan-limit level is announced once). */
    private final Set<String> noticesShown = new HashSet<>();
    private final BuildRegistry registry;
    private final ActionHistory history;
    private boolean historyWritable = true;
    private final PlacementScheduler scheduler;

    /** Live state for an actor. Completed previews also have an independent on-disk snapshot. */
    private final Map<String, PlayerSession> playerSessions = new HashMap<>();
    private final class PlayerSession {
        String activeProject;
        Map<String, Integer> materialPalette;
        String sampledMaterial;
        RouteReport routeReport;
        RelayJson.Hello hello;
        String pendingHello;
        long nextHelloTick;
        ZoneSelection zone;
        PolygonDraft polygonDraft;
        String selectedBuild;
        PartSelection part;
        String[] connections;
        RetainedPlan retainedPlan;
        PreviewStore.Saved savedPreview;
        String previewLoadProblem;
        boolean resumingPreview;
        PendingUndo pendingUndo;
        Alternatives alternatives;
        QualityInfo qualityInfo;
        QualityUndo qualityUndo;
        boolean starterWand;
        final dev.justbuild.provider.UsageTracker usage = new dev.justbuild.provider.UsageTracker();
        final PlanningLifecycle planning = new PlanningLifecycle();
        final PreviewLifecycle preview = new PreviewLifecycle();
        String[] connections() { if (connections == null) connections = new String[2]; return connections; }
    }
    private PlayerSession playerSession(String actor) {
        return playerSessions.computeIfAbsent(actor, id -> {
            PlayerSession session = new PlayerSession();
            try { session.savedPreview = previewStore.load(id); }
            catch (IOException e) {
                session.previewLoadProblem = e.getMessage();
                LOGGER.warn("Could not load saved preview for {}: {}", id, e.toString());
            }
            return session;
        });
    }
    private List<ActiveRequest> planningRequests() {
        return playerSessions.values().stream().map(s -> s.planning.current).filter(java.util.Objects::nonNull).toList();
    }
    private List<PendingPlacement> waitingPreviews() {
        return playerSessions.values().stream().map(s -> s.preview.current).filter(java.util.Objects::nonNull).toList();
    }
    /** One planning conversation, with token identity preventing stale completions after replacement. */
    private final class PlanningLifecycle {
        ActiveRequest current;
        void start(ActiveRequest next) {
            if (current != null && current != next) end(current, true);
            current = next;
        }
        boolean isCurrent(ActiveRequest request, CancellationToken token) {
            return !token.isCancelled() && current == request && request.inFlight == token;
        }
        void end(ActiveRequest request, boolean cancel) {
            if (request == null) return;
            if (current == request) current = null;
            if (cancel && request.inFlight != null) request.inFlight.cancel();
            request.inFlight = null;
            if (request.target != null && !request.handedOff) busy.remove(request.target.id());
        }
    }
    /** A preview is consumed only after confirmation checks; replacement returns the old reservation for disposal. */
    private static final class PreviewLifecycle {
        PendingPlacement current;
        PendingPlacement replace(PendingPlacement next) { PendingPlacement old = current; current = next; return old; }
        PendingPlacement take() { PendingPlacement old = current; current = null; return old; }
    }

    /** A conversation plus where its build goes; {@code target} is set for edits. */
    private final class ActiveRequest {
        final String actor;
        final Conversation conversation;
        final ServerLevel level;
        final BlockPos origin;
        final Direction facing;
        final BuildRecord target;
        /** Prompt text saying where the origin is (null for edits) and whether terrain is sampled around it. */
        final PromptSpec.Placement placement;
        final boolean centered;
        /** Creative direction drawn for a new build (null for edits or when turned off). */
        final String direction;
        CommandSourceStack source;
        CancellationToken inFlight;
        RequestStatus status;
        boolean handedOff;
        boolean forcePreview;
        boolean savedPlan;
        boolean restoreLive;
        /** The waiting preview this request revises (compared by identity), or null. */
        PendingPlacement revising;
        int toolReads;
        Map<BlockPos, BlockState> candidateChanges;
        PlanningFeedback feedback = PlanningFeedback.EMPTY;
        boolean projectCaptured;
        String projectId;
        ProjectTheme theme = ProjectTheme.EMPTY;
        dev.justbuild.prompt.BuildConstraints constraints;
        String typedRequest;
        /** The guides this request carries (attached by the mod, plus any the AI asked for); null before its first call. */
        List<GuideLibrary.Guide> guides;
        /** Whether the AI has had its one guide round. */
        boolean guideRound;
        BuildZone zone;
        BuildRegion focus;
        List<BuildRegion> guards = List.of();
        List<BuildRegion> connectionAreas = List.of();
        /** Requested boxes survive clarifying questions; their samples are refreshed on each subsequent call. */

        ActiveRequest(String actor, Conversation conversation, ServerLevel level, BlockPos origin, Direction facing,
                BuildRecord target, PromptSpec.Placement placement, boolean centered, String direction,
                CommandSourceStack source) {
            this.actor = actor;
            this.placement = placement;
            this.centered = centered;
            this.direction = direction;
            this.conversation = conversation;
            this.level = level;
            this.origin = origin;
            this.facing = facing;
            this.target = target;
            this.constraints = target == null ? prefs.constraints(actor) : target.constraints();
            this.source = source;
        }
    }

    private record PendingUndo(String buildId, String versionId, Instant expires) {
    }

    private record UndoPreview(BuildRecord record, int versionCount, Map<BlockPos, BlockState> diff,
            int changedSince, int kept) {
    }

    /**
     * A plan ready to place; durable recipes are rebuilt through current checks after reconnect. {@code revisions} is
     * the session-only history that Revise and Previous use.
     */
    private record PendingPlacement(String actor, CommandSourceStack source, ServerLevel level, BuildRecord record,
            boolean isNew, BuildRecord.Version version, Map<BlockPos, BlockState> placements,
            Map<BlockPos, BlockState> ours, String title, String summary, String ready, String aiSummary,
            Instant requestStarted, BlockPos min, BlockPos max, BuildZone zone,
            BuildRegion focus, List<BuildRegion> guards, List<String> quality, BuildPlan sourcePlan, boolean restoreLive, String projectId,
            ChangePreview.Counts counts, PreviewRevisions revisions) {
        PendingPlacement(String actor, CommandSourceStack source, ServerLevel level, BuildRecord record,
                boolean isNew, BuildRecord.Version version, Map<BlockPos, BlockState> placements,
                Map<BlockPos, BlockState> ours, String title, String summary, String ready, String aiSummary,
                Instant requestStarted, BlockPos min, BlockPos max, BuildZone zone,
                BuildRegion focus, List<BuildRegion> guards, List<String> quality, BuildPlan sourcePlan, boolean restoreLive, String projectId,
                PreviewRevisions revisions) {
            this(actor, source, level, record, isNew, version, placements, ours, title, summary, ready, aiSummary,
                    requestStarted, min, max, zone, focus, guards, quality, sourcePlan, restoreLive, projectId,
                    ChangePreview.counts(Protection.filter(placements, owned(record, ours), level::getBlockEntity,
                            level::getBlockState).placements(), pos -> readLoaded(level, pos)), revisions);
        }
        PendingPlacement withSource(CommandSourceStack currentSource) {
            return new PendingPlacement(actor, currentSource, level, record, isNew, version, placements, ours,
                    title, summary, ready, aiSummary, requestStarted, min, max,
                    zone, focus, guards, quality, sourcePlan, restoreLive, projectId, counts, revisions);
        }
        /** The same preview after an AI revision, with its history and ready text. */
        PendingPlacement withRevisions(PreviewRevisions value, String readyText) {
            return new PendingPlacement(actor, source, level, record, isNew, version, placements, ours,
                    title, summary, readyText, aiSummary, requestStarted, min, max,
                    zone, focus, guards, quality, sourcePlan, restoreLive, projectId, counts, value);
        }
    }

    /** What a placement job is for, so it can be finished or cancelled correctly. */
    private record JobInfo(CommandSourceStack source, BuildRecord record, BuildRecord.Version newVersion,
            Map<BlockPos, BlockState> before, Instant requestStarted, String aiSummary, boolean restoreLive, JobProgress progress) {
        JobInfo(CommandSourceStack source, BuildRecord record, BuildRecord.Version newVersion,
                Map<BlockPos, BlockState> before, Instant requestStarted, String aiSummary) {
            this(source, record, newVersion, before, requestStarted, aiSummary, false, new JobProgress());
        }
    }

    /** Server thread only: when a job started and which quarter mark chat reports next. */
    private static final class JobProgress {
        final Instant started = Instant.now();
        int nextMilestone = 25;
    }

    public BuildSessionManager(MinecraftServer server, ConfigStore configStore, JustBuildConfig config,
            PlanStore planStore, Path worldData) {
        this.server = server;
        this.construction = new dev.justbuild.construction.ConstructionManager(server, worldData.resolve("construction"),
                new dev.justbuild.construction.ConstructionManager.Access() {
                    public int maxBlocks() { return BuildSessionManager.this.config.maxBlocks; }
                    public boolean allowed(ServerPlayer player) {
                        return allowedNow(player.createCommandSourceStack()) && lockLoadProblem == null && buildLoadProblem == null;
                    }
                    public boolean cell(ServerPlayer player, BlockPos pos) {
                        if (locked(player.level().dimension(), pos)) return false;
                        CommandSourceStack source = player.createCommandSourceStack();
                        if (isAdmin(source)) return true;
                        return registry.all().stream().noneMatch(r -> r.dimension().equals(player.level().dimension())
                                && !canModify(source, r) && registry.current(r).containsKey(pos)
                                && !registry.current(r).get(pos).isAir());
                    }
                });
        this.relay = new RelayHub(server);
        this.configStore = configStore;
        this.config = config;
        this.planStore = planStore;
        this.previewStore = new PreviewStore(worldData.resolve("previews"));
        this.store = new BuildStore(worldData.resolve("builds"));
        this.designStore = new DesignStore(worldData.resolve("designs"));
        this.designTransfer = new DesignTransfer(net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("justbuild/library"));
        this.projectStore = new ProjectStore(worldData.resolve("projects.json"));
        try { projectStore.load().forEach(p -> projects.put(p.id(), p)); }
        catch (IOException e) { projectLoadProblem = e.getMessage(); LOGGER.error("Could not load projects: {}", projectLoadProblem); }
        this.regionStore = new RegionStore(worldData.resolve("locked-areas.json"));
        try { lockedAreas.addAll(regionStore.load()); }
        catch (IOException e) {
            lockLoadProblem = e.getMessage();
            LOGGER.error("AI placement is disabled until locked areas can be read: {}", lockLoadProblem);
        }
        designStore.loadAll().forEach(d -> designs.put(d.id(), d));
        this.historyFile = worldData.resolve("history.json");
        this.prefs = PlayerPrefs.load(worldData.resolve("players.json"));
        this.registry = new BuildRegistry(new BuildRegistry.Worlds() {
            @Override
            public LevelHeightAccessor height(ResourceKey<Level> dimension) {
                return level(dimension);
            }

            @Override
            public BlockState live(ResourceKey<Level> dimension, BlockPos pos) {
                ServerLevel world = level(dimension);
                return world.hasChunk(pos.getX() >> 4, pos.getZ() >> 4) ? world.getBlockState(pos) : null;
            }
        });
        store.loadAll().forEach(registry::add);
        if (!store.loadProblems().isEmpty()) {
            buildLoadProblem = PlanParser.clean(String.join(", ", store.loadProblems().stream().limit(5).toList()), 240);
            LOGGER.error("AI placement disabled because saved builds could not be read: {}", buildLoadProblem);
        }
        ActionHistory loaded;
        try {
            loaded = ActionHistory.load(historyFile, () -> this.config.undoHistory);
        } catch (IOException | RuntimeException e) {
            // Set the unreadable file aside, so the fresh history saved next doesn't silently replace it.
            Path aside = historyFile.resolveSibling("history.json.unreadable-" + System.currentTimeMillis());
            try {
                java.nio.file.Files.move(historyFile, aside);
                LOGGER.warn("Could not read {} ({}); kept it as {} and started a new undo history", historyFile,
                        e.toString(), aside.getFileName());
            } catch (IOException moveFailed) {
                historyWritable = false;
                LOGGER.warn("Could not read {} ({}) or set it aside: {}. History writes and version trimming are disabled "
                        + "to preserve the original file.", historyFile, e.toString(), moveFailed.toString());
            }
            loaded = new ActionHistory(() -> this.config.undoHistory);
        }
        this.history = loaded;
        saveHistory();
        this.scheduler = new PlacementScheduler(() -> this.config.blocksPerTick, this::onJobFinished);
        LOGGER.info("Loaded {} saved AI builds", registry.all().size());
    }

    public static String ownerOf(CommandSourceStack source) {
        Entity entity = source.getEntity();
        return entity == null ? CONSOLE : entity.getUUID().toString();
    }

    // ---- who may change a build ----

    /** A command source captures permissions; delayed work must consult the player's current permissions. */
    private static CommandSourceStack currentSource(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return source;
        return currentPlayer(source).createCommandSourceStack();
    }

    private static ServerPlayer currentPlayer(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        ServerPlayer live = source.getServer().getPlayerList().getPlayer(player.getUUID());
        return live != null ? live : player;
    }

    private static boolean allowedNow(CommandSourceStack source) {
        CommandSourceStack current = currentSource(source);
        return (current.getPlayer() == null || !current.getPlayer().hasDisconnected()) && JustBuildCommand.allowed(current);
    }

    /** Console, RCON, command blocks, a level-4 operator, or the singleplayer host. */
    private static boolean isAdmin(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer() == null ? null : currentPlayer(source);
        return source.getEntity() == null
                || (player == null ? source.permissions() : player.permissions())
                        .hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_OWNER)
                || player != null && source.getServer().isSingleplayerOwner(player.nameAndId());
    }

    /**
     * Anyone allowed to use /justbuild may select and view a build. Changing it takes its owner, the owner or a
     * collaborator of a project it belongs to (co-building), or an admin.
     */
    private boolean canModify(CommandSourceStack source, BuildRecord record) {
        return isAdmin(source) || ProjectStore.mayModify(ownerOf(source), record, projects.values());
    }

    private boolean mayModify(CommandSourceStack source, BuildRecord record) {
        if (canModify(source, record)) return true;
        send(source, Chat.error("“" + record.title() + "” belongs to " + playerName(record.owner())
                + ". Only they, their project's team, or an admin can change it."));
        return false;
    }

    /** Its owner, the owner of a project it belongs to, or an admin may stop someone else's change to a build. */
    private boolean canRelease(CommandSourceStack source, BuildRecord record) {
        String actor = ownerOf(source);
        return isAdmin(source) || record.owner().equals(actor)
                || projects.values().stream().anyMatch(p -> p.owner().equals(actor) && p.builds().contains(record.id()));
    }

    private String playerName(String actor) {
        if (CONSOLE.equals(actor)) return "the console";
        try {
            ServerPlayer player = server.getPlayerList().getPlayer(UUID.fromString(actor));
            if (player != null) return player.getName().getString();
        } catch (IllegalArgumentException ignored) {
            // not a player id
        }
        return projects.values().stream().map(p -> p.collaborators().get(actor)).filter(java.util.Objects::nonNull)
                .findFirst().orElse("another player");
    }

    /** Who holds a busy build: the player whose request, preview, or placement is on it (null if none). */
    private String holderOf(String buildId) {
        for (ActiveRequest r : planningRequests()) if (r.target != null && r.target.id().equals(buildId)) return r.actor;
        for (PendingPlacement p : waitingPreviews()) if (p.record().id().equals(buildId)) return p.actor();
        for (var e : jobs.entrySet()) if (e.getValue().record().id().equals(buildId)) return e.getKey().owner();
        return null;
    }

    private String busyMessage(CommandSourceStack source, BuildRecord record) {
        String holder = holderOf(record.id());
        String actor = ownerOf(source);
        if (holder == null || holder.equals(actor)) {
            return "“" + record.title() + "” is busy with your own change. Wait for it to finish, or cancel it.";
        }
        return "“" + record.title() + "” is busy: " + playerName(holder) + " is changing it. Wait for them to finish"
                + (canRelease(source, record) ? ", or stop their change with /justbuild release #" + record.id() + "." : ".");
    }

    /** Stops another player's waiting request or preview on a build (not a placement under way). */
    public void release(CommandSourceStack source, String build) {
        String id = build.strip().replaceFirst("^#", "");
        BuildRecord record = registry.get(id).orElse(null);
        if (record == null) { send(source, Chat.error("No saved build has id #" + id + ".")); return; }
        if (!canRelease(source, record)) {
            send(source, Chat.error("Only “" + record.title() + "”'s owner, its project's owner, or an admin can release it."));
            return;
        }
        String holder = holderOf(id);
        if (holder == null) { send(source, Chat.info("“" + record.title() + "” isn't busy.")); return; }
        if (holder.equals(ownerOf(source))) {
            send(source, Chat.with(Chat.error("That's your own change."), "Cancel", "/justbuild cancel", "Stop your change"));
            return;
        }
        if (jobs.values().stream().anyMatch(info -> info.record().id().equals(id))) {
            send(source, Chat.error("“" + record.title() + "” is being placed right now; wait for it to finish."));
            return;
        }
        ActiveRequest request = playerSession(holder).planning.current;
        if (request != null && request.target != null && request.target.id().equals(id)) {
            playerSession(holder).alternatives = null;
            endRequest(request, true);
        }
        PendingPlacement preview = playerSession(holder).preview.current;
        if (preview != null && preview.record().id().equals(id)) {
            playerSession(holder).preview.take();
            playerSession(holder).alternatives = null;
            discard(preview);
        }
        RetainedPlan retained = playerSession(holder).retainedPlan;
        if (retained != null && retained.request().target != null && retained.request().target.id().equals(id)) {
            playerSession(holder).retainedPlan = null;
        }
        busy.remove(id);
        try {
            ServerPlayer player = server.getPlayerList().getPlayer(UUID.fromString(holder));
            if (player != null) {
                player.sendSystemMessage(Chat.error(playerName(ownerOf(source)) + " released “" + record.title()
                        + "”; your change to it was discarded."));
                sendState(player);
            }
        } catch (IllegalArgumentException ignored) {
            // the console holds no previews
        }
        send(source, Chat.info("Released “" + record.title() + "”."));
        sendStateTo(source);
    }

    /** Cells of other builds near {@code area} the actor may not change; new builds and edits leave them as they are. */
    private java.util.Set<BlockPos> othersCells(CommandSourceStack source, ResourceKey<Level> dimension, BuildZone area,
            String exceptId) {
        if (isAdmin(source)) return java.util.Set.of();
        java.util.Set<BlockPos> cells = new java.util.HashSet<>();
        for (BuildRecord r : registry.all()) {
            if (!r.dimension().equals(dimension) || r.id().equals(exceptId) || canModify(source, r)) continue;
            // The editable zone is not the saved building's footprint: it can be moved after placement.
            registry.current(r).forEach((pos, state) -> {
                if (!state.isAir() && (area == null || area.contains(pos))) cells.add(pos);
            });
        }
        return cells;
    }

    /** Refresh once per membership/build change, not once per placed block, including jobs already in the queue. */
    private java.util.function.Predicate<BlockPos> liveBuildAccess(CommandSourceStack source, BuildRecord record,
            Iterable<BlockPos> positions) {
        BuildZone area = footprint(record, positions);
        return new java.util.function.Predicate<>() {
            private long revision = -1;
            private boolean admin;
            private Set<BlockPos> protectedCells = Set.of();
            @Override public boolean test(BlockPos pos) {
                boolean currentAdmin = isAdmin(source);
                if (revision != buildAccessRevision || admin != currentAdmin) {
                    protectedCells = othersCells(source, record.dimension(), area, record.id());
                    revision = buildAccessRevision;
                    admin = currentAdmin;
                }
                return !protectedCells.contains(pos);
            }
        };
    }

    private static void reportKept(CommandSourceStack source, int kept) {
        if (kept > 0) send(source, Chat.info("Kept " + count(kept, "block") + " of other players' buildings unchanged."));
    }

    private static int keepOthers(Map<BlockPos, BlockState> changes, java.util.Set<BlockPos> others) {
        int before = changes.size();
        changes.keySet().removeIf(others::contains);
        return before - changes.size();
    }

    // ---- world tools ----

    private BuildZone zoneFor(String actor, ResourceKey<Level> dimension) throws PlanException {
        if (playerSession(actor).polygonDraft != null) throw new PlanException("Finish or cancel the polygon in World → Polygon first.");
        ZoneSelection selected = playerSession(actor).zone;
        if (selected == null) {
            return null;
        }
        if (selected.zone() == null) {
            throw new PlanException("Select zone corner 2 first, or clear the zone with /justbuild zone clear.");
        }
        selected.zone().check(dimension, List.of());
        return selected.zone();
    }

    /** Select the block under the crosshair, falling back to the block underfoot. */
    public void zoneCorner(CommandSourceStack source, int corner) {
        BlockPos pos = BlockPos.containing(source.getPosition()).below();
        if (source.getPlayer() != null && source.getPlayer().pick(EDIT_REACH, 0, false) instanceof BlockHitResult hit
                && hit.getType() == HitResult.Type.BLOCK) {
            pos = hit.getBlockPos();
        }
        zoneCorner(source, corner, pos);
    }

    public void zoneCorner(CommandSourceStack source, int corner, BlockPos pos) {
        if (!canChangeZone(source)) {
            return;
        }
        String actor = ownerOf(source);
        ResourceKey<Level> dimension = source.getLevel().dimension();
        PolygonDraft draft = playerSession(actor).polygonDraft;
        if (draft != null) {
            if (!draft.dimension().equals(dimension)) { send(source, Chat.error("Return to the polygon's dimension first.")); return; }
            var points = new java.util.ArrayList<>(draft.points());
            if (corner == 1) points.clear();
            if (points.size() >= 32) { send(source, Chat.error("A polygon can have at most 32 corners.")); return; }
            var point = new dev.justbuild.prompt.Footprint.Point(pos.getX(), pos.getZ());
            if (points.contains(point)) { send(source, Chat.error("That corner is already selected. Finish the polygon in World → Polygon.")); return; }
            points.add(point);
            playerSession(actor).polygonDraft = new PolygonDraft(dimension, points.size() == 1 ? pos.getY() + 1 : draft.y(),
                    List.copyOf(points), draft.previous());
            playerSession(actor).zone = new ZoneSelection(dimension, pos.immutable(), null);
            send(source, Chat.info("Polygon: " + points.size() + " corners. Right-click the next corner; finish in World → Polygon."));
            sendStateTo(source); return;
        }
        if (corner == 1) {
            playerSession(actor).zone = new ZoneSelection(dimension, pos.immutable(), null);
            send(source, Chat.info("Zone corner 1: " + pos.getX() + ", " + pos.getZ()
                    + ". Look at the opposite corner, then choose corner 2. Height is automatic."));
        } else {
            ZoneSelection first = playerSession(actor).zone;
            if (first == null || !first.dimension().equals(dimension)) {
                send(source, Chat.error("Select corner 1 in this dimension first."));
                return;
            }
            try {
                BuildZone zone = BuildZone.between(dimension, first.first(), pos);
                playerSession(actor).zone = new ZoneSelection(dimension, first.first(), zone);
                saveSelectedZone(source, zone);
                send(source, Chat.info("Build zone: " + zone.description() + ". New builds, edits, and undo stay inside it."));
            } catch (IllegalArgumentException e) {
                send(source, Chat.error(e.getMessage()));
                return;
            }
        }
        playerSession(actor).pendingUndo = null;
        sendStateTo(source);
    }

    public void clearZone(CommandSourceStack source) {
        if (!canChangeZone(source)) {
            return;
        }
        playerSession(ownerOf(source)).zone = null;
        playerSession(ownerOf(source)).polygonDraft = null;
        saveSelectedZone(source, null);
        playerSession(ownerOf(source)).pendingUndo = null;
        send(source, Chat.info("Build zone cleared."));
        sendStateTo(source);
    }

    public void expandZone(CommandSourceStack source, int blocks) {
        expandZone(source, zone -> zone.expandedBy(blocks));
    }

    public void adjustZone(CommandSourceStack source, String arguments) {
        if (!canChangeZone(source)) return;
        String actor = ownerOf(source);
        String[] args = arguments.strip().split("\\s+");
        try {
            if (args[0].equals("polygon")) {
                String action = args.length > 1 ? args[1] : "start";
                PolygonDraft draft = playerSession(actor).polygonDraft;
                if (action.equals("start")) {
                    playerSession(actor).polygonDraft = new PolygonDraft(source.getLevel().dimension(),
                            BlockPos.containing(source.getPosition()).getY(), List.of(), draft == null ? playerSession(actor).zone : draft.previous());
                    playerSession(actor).zone = null; playerSession(actor).pendingUndo = null;
                    send(source, Chat.info("Polygon started. Click each corner with the Zone wand, then finish in World → Polygon."));
                    sendStateTo(source); return;
                }
                if (draft == null || !draft.dimension().equals(source.getLevel().dimension()))
                    throw new IllegalArgumentException("Start a polygon in this dimension first.");
                if (action.equals("add")) { zoneCorner(source, 2); return; }
                if (action.equals("cancel")) {
                    restorePolygonSelection(actor);
                    send(source, Chat.info("Polygon cancelled; the previous selection is restored."));
                    sendStateTo(source); return;
                }
                if (action.equals("undo")) {
                    if (!draft.points().isEmpty()) playerSession(actor).polygonDraft = new PolygonDraft(draft.dimension(), draft.y(),
                            List.copyOf(draft.points().subList(0, draft.points().size() - 1)), draft.previous());
                } else if (action.equals("finish")) {
                    BuildZone previous = draft.previous() == null ? null : draft.previous().zone();
                    if (previous != null && !previous.dimension().equals(draft.dimension())) previous = null;
                    BuildZone zone = BuildZone.polygon(draft.dimension(), draft.y(), draft.points(),
                            previous == null ? null : previous.minY(), previous == null ? null : previous.maxY());
                    playerSession(actor).polygonDraft = null;
                    playerSession(actor).zone = new ZoneSelection(zone.dimension(), new BlockPos(zone.minX(), zone.displayY() - 1, zone.minZ()), zone);
                    saveSelectedZone(source, zone); playerSession(actor).pendingUndo = null;
                    send(source, Chat.info("Polygon zone: " + zone.description()));
                } else throw new IllegalArgumentException("Choose polygon start, add, undo, finish, or cancel.");
                sendStateTo(source); return;
            }
            BuildZone zone = zoneFor(actor, source.getLevel().dimension());
            if (zone == null) throw new IllegalArgumentException("Select a build zone first.");
            BuildZone next;
            if (args[0].equals("move") && args.length == 4) {
                int x = step(args[1]), y = step(args[2]), z = step(args[3]); next = zone.shifted(x, y, z);
            } else if (args[0].equals("height") && args.length == 2 && args[1].equals("auto")) next = zone.height(null, null);
            else if (args[0].equals("height") && args.length == 3) next = zone.height(Integer.parseInt(args[1]), Integer.parseInt(args[2]));
            else if (args[0].equals("corner") && args.length == 4)
                next = zone.corner(Integer.parseInt(args[1]), Integer.parseInt(args[2]), Integer.parseInt(args[3]));
            else throw new IllegalArgumentException("Choose move <x y z>, height <min max|auto>, corner <index x z>, or polygon.");
            playerSession(actor).zone = new ZoneSelection(next.dimension(), new BlockPos(next.minX(), next.displayY() - 1, next.minZ()), next);
            saveSelectedZone(source, next); playerSession(actor).pendingUndo = null; sendStateTo(source);
        } catch (IllegalArgumentException | ArithmeticException | PlanException e) { send(source, Chat.error(e.getMessage())); }
    }

    private void restorePolygonSelection(String actor) {
        PolygonDraft draft = playerSession(actor).polygonDraft;
        playerSession(actor).polygonDraft = null;
        if (draft == null) return;
        if (draft.previous() == null) playerSession(actor).zone = null; else playerSession(actor).zone = draft.previous();
    }

    private static int step(String value) {
        int step = Integer.parseInt(value);
        if (Math.abs((long) step) > 16) throw new IllegalArgumentException("Move at most 16 blocks at a time.");
        return step;
    }

    public void adjustPart(CommandSourceStack source, String arguments) {
        if (!canChangeZone(source)) return;
        String actor = ownerOf(source);
        PartSelection part = playerSession(actor).part;
        if (part == null || part.region() == null || !part.dimension().equals(source.getLevel().dimension())) {
            send(source, Chat.error("Finish selecting a part in this dimension first.")); return;
        }
        try {
            String[] args = arguments.strip().split("\\s+");
            BuildRegion region;
            if (args.length == 5 && args[0].equals("corner")) {
                region = part.region().corner(Integer.parseInt(args[1]),
                        new BlockPos(Integer.parseInt(args[2]), Integer.parseInt(args[3]), Integer.parseInt(args[4])));
            } else {
                if (args.length != 4) throw new IllegalArgumentException("Use move|min|max <x y z> or corner <index x y z>.");
                BlockPos a = part.region().min(), b = part.region().max();
                if (args[0].equals("move")) {
                    int x = step(args[1]), y = step(args[2]), z = step(args[3]); a = a.offset(x, y, z); b = b.offset(x, y, z);
                } else {
                    BlockPos p = new BlockPos(Integer.parseInt(args[1]), Integer.parseInt(args[2]), Integer.parseInt(args[3]));
                    if (args[0].equals("min")) a = p; else if (args[0].equals("max")) b = p;
                    else throw new IllegalArgumentException("Choose move, min, max, or corner <index x y z>.");
                }
                region = new BuildRegion(part.dimension(), a, b);
            }
            playerSession(actor).part = new PartSelection(part.buildId(), part.dimension(), region.min(), region);
            sendStateTo(source);
        } catch (IllegalArgumentException e) { send(source, Chat.error(e.getMessage())); }
    }

    public void includeZoneTarget(CommandSourceStack source) {
        BlockPos pos = BlockPos.containing(source.getPosition()).below();
        if (source.getPlayer() != null && source.getPlayer().pick(EDIT_REACH, 0, false) instanceof BlockHitResult hit
                && hit.getType() == HitResult.Type.BLOCK) {
            pos = hit.getBlockPos();
        }
        BlockPos target = pos;
        expandZone(source, zone -> zone.including(List.of(target)));
    }

    private void expandZone(CommandSourceStack source, java.util.function.UnaryOperator<BuildZone> expansion) {
        if (!canChangeZone(source)) {
            return;
        }
        String actor = ownerOf(source);
        try {
            BuildZone current = zoneFor(actor, source.getLevel().dimension());
            if (current == null) {
                send(source, Chat.error("Select both build-zone corners before expanding it."));
                return;
            }
            BuildZone expanded = expansion.apply(current);
            if (expanded.equals(current)) {
                send(source, Chat.info("That target is already inside your build zone."));
                return;
            }
            playerSession(actor).zone = new ZoneSelection(expanded.dimension(),
                    new BlockPos(expanded.minX(), expanded.displayY() - 1, expanded.minZ()), expanded);
            saveSelectedZone(source, expanded);
            playerSession(actor).pendingUndo = null;
            send(source, Chat.info("Expanded build zone: " + expanded.description() + "."));
            sendStateTo(source);
        } catch (PlanException | IllegalArgumentException e) {
            send(source, Chat.error(e.getMessage()));
        }
    }

    public void zoneInfo(CommandSourceStack source) {
        ZoneSelection selection = playerSession(ownerOf(source)).zone;
        send(source, Chat.info(selection == null ? "No build zone selected. Use /justbuild zone 1, then /justbuild zone 2."
                : selection.zone() == null ? "Corner 1 selected; choose corner 2 to finish the ground footprint."
                : "Build zone in " + selection.dimension().identifier() + ": " + selection.zone().description() + "."));
    }

    private boolean canChangeZone(CommandSourceStack source) {
        String actor = ownerOf(source);
        if (playerSession(actor).planning.current != null || scheduler.busy(actor) || playerSession(actor).preview.current != null) {
            send(source, Chat.error("Finish or cancel your current request, preview, or placement before changing the zone."));
            return false;
        }
        return true;
    }

    private Optional<BuildRecord> selected(CommandSourceStack source) {
        String id = playerSession(ownerOf(source)).selectedBuild;
        return id == null ? Optional.empty() : registry.get(id)
                .filter(r -> r.dimension().equals(source.getLevel().dimension()));
    }

    private void saveSelectedZone(CommandSourceStack source, BuildZone zone) {
        // The area stays this player's own when they may not change the selected build.
        selected(source).filter(record -> canModify(source, record)).ifPresent(record -> {
            record.zone(zone);
            if (!saveRecord(record)) send(source, Chat.error("The area is active, but could not be saved to disk."));
        });
    }

    /** Explicit selection loads the saved area; edits that name an id can keep a freshly drawn local area. */
    public void selectBuild(CommandSourceStack source, String id) {
        selectBuild(source, id, null);
    }

    /** A wand selection may attach an unassigned, freshly drawn zone to the building it hits. */
    private void selectBuild(CommandSourceStack source, String id, BuildZone drawnZone) {
        if (!canChangeZone(source)) return;
        Optional<BuildRecord> found = registry.get(id.strip().replaceFirst("^#", ""));
        if (found.isEmpty()) {
            send(source, Chat.error("No saved build with that id."));
            return;
        }
        if (!found.get().dimension().equals(source.getLevel().dimension())) {
            send(source, Chat.error("“" + found.get().title() + "” is in " + found.get().dimension().identifier()
                    + ". Travel there to select it."));
            return;
        }
        BuildRecord record = found.get();
        if (busy.contains(record.id())) {
            send(source, Chat.error(busyMessage(source, record)));
            return;
        }
        playerSession(ownerOf(source)).materialPalette = null;
        playerSession(ownerOf(source)).polygonDraft = null;
        playerSession(ownerOf(source)).selectedBuild = record.id();
        PartSelection part = playerSession(ownerOf(source)).part;
        if (part != null && !record.id().equals(part.buildId())) playerSession(ownerOf(source)).part = null;
        BuildZone zone = drawnZone != null ? drawnZone : record.zone();
        if (drawnZone != null) {
            saveSelectedZone(source, drawnZone);
        } else if (zone == null && !record.hasSavedZone()) {
            // A record without a saved area (edited by hand) derives one from recorded positions, without world reads.
            try {
                zone = BuildZone.between(record.dimension(), record.origin(), record.origin()).including(record.baseline().keySet());
                record.zone(zone);
                saveRecord(record);
            } catch (IllegalArgumentException ignored) { /* The recorded positions may exceed the current footprint limit. */ }
        }
        if (zone == null) playerSession(ownerOf(source)).zone = null;
        else playerSession(ownerOf(source)).zone = new ZoneSelection(zone.dimension(),
                new BlockPos(zone.minX(), zone.displayY() - 1, zone.minZ()), zone, drawnZone == null);
        playerSession(ownerOf(source)).pendingUndo = null;
        send(source, Chat.info("Editing “" + record.title() + "”. "
                + (zone == null ? "It has no saved area." : drawnZone != null ? "Kept the zone you drew."
                        : "Loaded its saved zone. Use the wand corners to resize it.")));
        sendStateTo(source);
    }

    /** Select a saved building under the crosshair, including the wand's extended reach. */
    public void selectBuildAt(CommandSourceStack source) {
        if (source.getPlayer() != null && source.getPlayer().pick(EDIT_REACH, 1, false) instanceof BlockHitResult hit
                && hit.getType() == HitResult.Type.BLOCK) selectBuildAt(source, hit.getBlockPos());
        else send(source, Chat.error("Point at a saved building within " + (int) EDIT_REACH + " blocks."));
    }

    public void selectBuildAt(CommandSourceStack source, BlockPos pos) {
        Optional<BuildRecord> found = registry.at(source.getLevel().dimension(), pos)
                .or(() -> registry.atOutsideZone(source.getLevel().dimension(), pos));
        if (found.isPresent()) {
            String actor = ownerOf(source);
            ZoneSelection active = playerSession(actor).zone;
            // Do not carry another building's area, an unfinished selection, or a distant zone to this build.
            BuildZone drawnZone = selected(source).isEmpty() && playerSession(actor).polygonDraft == null
                    && active != null && !active.fromBuild() && active.zone() != null
                    && active.dimension().equals(source.getLevel().dimension()) && active.zone().contains(pos)
                    ? active.zone() : null;
            selectBuild(source, found.get().id(), drawnZone);
            return;
        }
        send(source, Chat.error("That block is not part of a saved AI build."));
    }

    public void deselectBuild(CommandSourceStack source) {
        if (!canChangeZone(source)) return;
        restorePolygonSelection(ownerOf(source));
        playerSession(ownerOf(source)).selectedBuild = null;
        playerSession(ownerOf(source)).part = null;
        // A zone that came with the build selected last would put the next new build in its footprint.
        ZoneSelection zone = playerSession(ownerOf(source)).zone;
        if (zone != null && zone.fromBuild()) {
            playerSession(ownerOf(source)).zone = null;
            send(source, Chat.info("New build. The last build's area is no longer your zone."));
        } else {
            send(source, Chat.info(zone == null ? "New build." : "New build. The zone you drew is kept."));
        }
        sendStateTo(source);
    }

    public void renameBuild(CommandSourceStack source, String name) {
        if (!canChangeZone(source)) return;
        Optional<BuildRecord> found = selected(source);
        if (found.isEmpty()) { send(source, Chat.error("Select a build first.")); return; }
        if (!mayModify(source, found.get())) return;
        try {
            found.get().rename(name);
            if (saveRecord(found.get())) send(source, Chat.info("Build renamed to “" + found.get().title() + "”."));
            else send(source, Chat.error("The name changed for this session, but could not be saved to disk."));
            sendStateToAll();
        } catch (IllegalArgumentException e) { send(source, Chat.error(e.getMessage())); }
    }

    public void wandCorner(CommandSourceStack source, int corner, BlockPos pos, boolean partWand) {
        if (!partWand) { zoneCorner(source, corner, pos); return; }
        partCorner(source, corner, pos);
    }

    public void partWand(CommandSourceStack source, boolean enabled) {
        if (!canChangeZone(source)) return;
        if (!ZoneWand.give(source, enabled)) return;
        sendStateTo(source);
    }

    public void partCorner(CommandSourceStack source, int corner) {
        BlockPos pos = BlockPos.containing(source.getPosition()).below();
        if (source.getPlayer() != null && source.getPlayer().pick(EDIT_REACH, 0, false) instanceof BlockHitResult hit
                && hit.getType() == HitResult.Type.BLOCK) pos = hit.getBlockPos();
        partCorner(source, corner, pos);
    }

    public void partCorner(CommandSourceStack source, int corner, BlockPos pos) {
        if (!canChangeZone(source)) return;
        String actor = ownerOf(source);
        ResourceKey<Level> dimension = source.getLevel().dimension();
        String build = selected(source).map(BuildRecord::id).orElse(null);
        try {
            if (corner == 1) {
                BuildRegion.between(dimension, pos, pos);
                playerSession(actor).part = new PartSelection(build, dimension, pos.immutable(), null);
                send(source, Chat.info("Box corner 1: " + pos.toShortString()
                        + ". Right-click to include the opposite footprint corner, then the roof or any other point."));
            } else if (corner == 2) {
                PartSelection first = playerSession(actor).part;
                if (first == null || !first.dimension().equals(dimension)) {
                    send(source, Chat.error("Start a box in this dimension first.")); return;
                }
                BuildRegion region = first.outline().including(pos);
                playerSession(actor).part = new PartSelection(build, dimension, first.first(), region);
                send(source, Chat.info("Box: " + region.description()
                        + ". Right-click to grow the purple box; left-click to start again."));
            }
        } catch (IllegalArgumentException e) { send(source, Chat.error(e.getMessage())); }
        sendStateTo(source);
    }

    public void clearPart(CommandSourceStack source) {
        if (!canChangeZone(source)) return;
        playerSession(ownerOf(source)).part = null;
        send(source, Chat.info("Box cleared. Edits can use the whole build area."));
        sendStateTo(source);
    }

    public void partInfo(CommandSourceStack source) {
        PartSelection part = playerSession(ownerOf(source)).part;
        send(source, Chat.info(part == null ? "No box selected. Use Editing tools or /justbuild box wand."
                : part.region() == null ? part.nextInstruction() : "Selected part: " + part.region().description()));
    }

    public void namedPart(CommandSourceStack source, String action, String name) {
        if (!canChangeZone(source)) return;
        BuildRecord record = selected(source).orElse(null);
        if (record == null || !mayModify(source, record)) { send(source, Chat.error("Select a building you can edit first.")); return; }
        if (busy.contains(record.id())) { send(source, Chat.error(busyMessage(source, record))); return; }
        try {
            String label = BuildRecord.label(name);
            var old = record.namedParts().entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(label))
                    .map(e -> Map.entry(e.getKey(), e.getValue())).findFirst();
            if (action.equals("select")) {
                BuildRegion region = old.orElseThrow(() -> new IllegalArgumentException("No saved part has that name.")).getValue();
                playerSession(ownerOf(source)).part = new PartSelection(record.id(), record.dimension(), region.min(), region);
            } else {
                if (action.equals("save")) {
                    PartSelection part = playerSession(ownerOf(source)).part;
                    if (part == null || part.region() == null || !record.id().equals(part.buildId()))
                        throw new IllegalArgumentException("Select this building's part first, including its height.");
                    record.savePart(label, part.region());
                } else if (action.equals("delete")) {
                    if (old.isEmpty()) throw new IllegalArgumentException("No saved part has that name.");
                    record.removePart(label);
                } else throw new IllegalArgumentException("Choose save, select, or delete.");
                if (!saveRecord(record)) {
                    record.removePart(label); old.ifPresent(e -> record.savePart(e.getKey(), e.getValue()));
                    throw new IllegalArgumentException("Could not save named parts; the change was undone.");
                }
            }
            send(source, Chat.info("Part “" + label + "”: " + action + "."));
            sendStateToAll();
        } catch (IllegalArgumentException e) { send(source, Chat.error(e.getMessage())); }
    }

    /** Structured controls: no arbitrary instructions are added to the relay prompt. */
    public void usage(CommandSourceStack source, String arguments) {
        if (arguments.strip().equals("show")) { sendStateTo(source); return; }
        if (!canChangeZone(source)) return;
        var tracker = playerSession(ownerOf(source)).usage;
        try {
            String[] args = arguments.strip().split("\\s+");
            if (args.length == 1 && args[0].equals("reset")) tracker.reset();
            else if (args.length == 2) tracker.limits(new dev.justbuild.provider.UsageTracker.Limits(Integer.parseInt(args[0]), Long.parseLong(args[1])));
            else throw new IllegalArgumentException("Use usage <max calls> <max reported tokens>, or usage reset. Zero means unlimited.");
            sendStateTo(source);
        } catch (IllegalArgumentException | IllegalStateException e) { send(source, Chat.error(e.getMessage())); }
    }

    public void constraints(CommandSourceStack source, String arguments) {
        if (!canChangeZone(source)) return;
        BuildRecord record = selected(source).orElse(null);
        if (record != null && !mayModify(source, record)) return;
        if (record != null && busy.contains(record.id())) { send(source, Chat.error(busyMessage(source, record))); return; }
        var old = record == null ? prefs.constraints(ownerOf(source)) : record.constraints();
        try {
            String[] args = arguments.strip().split("\\s+", 2);
            String value = args.length == 2 ? args[1] : "";
            var next = switch (args[0]) {
                case "height" -> new dev.justbuild.prompt.BuildConstraints(Integer.parseInt(value), old.targetFloors(), old.materials());
                case "floors" -> new dev.justbuild.prompt.BuildConstraints(old.maxHeight(), Integer.parseInt(value), old.materials());
                case "materials" -> new dev.justbuild.prompt.BuildConstraints(old.maxHeight(), old.targetFloors(),
                        value.equals("any") ? List.of() : java.util.Arrays.stream(value.split(","))
                            .map(String::strip).map(id -> id.contains(":") ? id : "minecraft:" + id).toList());
                case "clear" -> dev.justbuild.prompt.BuildConstraints.NONE;
                default -> throw new IllegalArgumentException("Use constraints height <0–256>, floors <0–64>, materials <ids|any>, or clear.");
            };
            for (String id : next.materials()) if (!dev.justbuild.relay.PromptCheck.knownBlock(id))
                throw new IllegalArgumentException("Unknown material: " + id);
            next = new dev.justbuild.prompt.BuildConstraints(next.maxHeight(), next.targetFloors(),
                    next.materials().stream().map(id -> net.minecraft.core.registries.BuiltInRegistries.BLOCK
                            .getKey(dev.justbuild.world.BlockResolver.parse(id).orElseThrow().getBlock()).toString()).toList());
            if (record == null) prefs.constraints(ownerOf(source), next);
            else {
                record.constraints(next);
                if (!saveRecord(record)) { record.constraints(old); throw new IOException("Could not save constraints."); }
            }
            send(source, Chat.info("Requirements saved for " + (record == null ? "your next builds" : "“" + record.title() + "”")
                    + ". Floor count is a design target; height and materials are enforced."));
            sendStateToAll();
        } catch (IOException | IllegalArgumentException e) { send(source, Chat.error(e.getMessage())); }
    }

    public void lockedInfo(CommandSourceStack source) {
        var mine = lockedAreas.stream().filter(l -> l.owner().equals(ownerOf(source))).toList();
        if (mine.isEmpty()) send(source, Chat.info("No locked areas. Select part corners, then /justbuild lock <name>."));
        for (var lock : mine) send(source, Chat.info(lock.name() + " — " + lock.id() + " — " + lock.region().description()));
    }

    public void selectPart(CommandSourceStack source, String kind) {
        if (!canChangeZone(source)) return;
        BuildRecord record = selected(source).orElse(null);
        if (record == null) { send(source, Chat.error("Select a build first.")); return; }
        try {
            Map<BlockPos, BlockState> blocks = registry.current(record);
            BuildRegion bounds = BuildRegion.enclosing(record.dimension(), blocks.entrySet().stream()
                    .filter(e -> !e.getValue().isAir()).map(Map.Entry::getKey).toList());
            ensureLoaded(source.getLevel(), blocks.keySet());
            BuildRegion region;
            if (kind.equals("roof")) region = PartSelections.roof(record.dimension(), blocks);
            else {
                if (source.getPlayer() == null || !(source.getPlayer().pick(EDIT_REACH, 0, false) instanceof BlockHitResult hit)
                        || hit.getType() != HitResult.Type.BLOCK) throw new IllegalArgumentException("Point at the selected building first.");
                region = kind.equals("wall") ? PartSelections.wall(bounds, hit.getBlockPos(), hit.getDirection())
                        : PartSelections.room(bounds, hit.getBlockPos().relative(hit.getDirection()), p -> readLoaded(source.getLevel(), p));
            }
            playerSession(ownerOf(source)).part = new PartSelection(record.id(), record.dimension(), region.min(), region);
            send(source, Chat.info("Selected " + kind + ": " + region.description()
                    + ". Check the purple box; right-click with the Builder Wand in Box mode to grow it, or left-click to start again."));
            sendStateTo(source);
        } catch (IllegalArgumentException | PlanException e) { send(source, Chat.error(e.getMessage())); }
    }

    public void lockPart(CommandSourceStack source, String name) {
        if (!canPlace(source) || !canChangeZone(source)) return;
        if (lockLoadProblem != null) { send(source, Chat.error("Locked areas could not be loaded. Repair the file and reload first.")); return; }
        String actor = ownerOf(source);
        PartSelection part = playerSession(actor).part;
        if (part == null || part.region() == null || !part.dimension().equals(source.getLevel().dimension())) {
            send(source, Chat.error("Use the Builder Wand in Box mode to select the whole area to protect, including its height.")); return;
        }
        try {
            for (BuildRecord record : registry.all()) {
                if (!record.dimension().equals(part.dimension()) || canModify(source, record)) continue;
                if (registry.current(record).entrySet().stream().anyMatch(e -> !e.getValue().isAir() && part.region().contains(e.getKey()))) {
                    throw new IllegalArgumentException("You cannot lock another player's building without permission to edit it.");
                }
            }
            if (lockedAreas.size() >= RegionStore.MAX_REGIONS || lockedAreas.stream().filter(l -> l.owner().equals(actor)).count() >= 32) {
                throw new IllegalArgumentException("The limit is 32 locked areas per player and 128 per world.");
            }
            String label = BuildRecord.label(name);
            if (lockedAreas.stream().anyMatch(l -> l.owner().equals(actor) && l.name().equalsIgnoreCase(label))) {
                throw new IllegalArgumentException("You already have a locked area with that name.");
            }
            var lock = new RegionStore.Locked(UUID.randomUUID().toString().replace("-", ""), actor, label, part.region());
            var next = new java.util.ArrayList<>(lockedAreas); next.add(lock);
            regionStore.save(next);
            lockedAreas.add(lock);
            playerSession(actor).part = null;
            send(source, Chat.info("Locked “" + label + "”. AI builds, edits, and undo leave this area intact. "
                    + "The box is cleared so you can edit the surrounding build."));
            sendStateToAll();
        } catch (IOException | IllegalArgumentException e) { send(source, Chat.error("Could not lock the area: " + e.getMessage())); }
    }

    public void unlockPart(CommandSourceStack source, String id) {
        if (!canPlace(source) || !canChangeZone(source)) return;
        var found = lockedAreas.stream().filter(l -> (l.owner().equals(ownerOf(source)) || isAdmin(source)) && l.id().equals(id))
                .findFirst();
        if (found.isEmpty()) { send(source, Chat.error("Choose one of your locked areas in Editing tools.")); return; }
        try {
            var next = new java.util.ArrayList<>(lockedAreas); next.remove(found.get());
            regionStore.save(next);
            lockedAreas.remove(found.get());
            send(source, Chat.info("Unlocked “" + found.get().name() + "”."));
            sendStateToAll();
        } catch (IOException e) { send(source, Chat.error("Could not save the unlocked area: " + e.getMessage())); }
    }

    private boolean locked(ResourceKey<Level> dimension, BlockPos pos) {
        return lockLoadProblem != null || buildLoadProblem != null || lockedAreas.stream().anyMatch(l -> l.region().dimension().equals(dimension)
                && l.region().contains(pos));
    }

    private boolean mayChange(ActiveRequest request, BlockPos pos) {
        return (request.focus == null || request.focus.contains(pos)) && !locked(request.level.dimension(), pos)
                && request.guards.stream().noneMatch(r -> r.contains(pos));
    }

    private boolean mayChange(PendingPlacement preview, BlockPos pos) {
        return (preview.focus() == null || preview.focus().contains(pos)) && !locked(preview.level().dimension(), pos)
                && preview.guards().stream().noneMatch(r -> r.contains(pos));
    }

    private static void ensureLoaded(ServerLevel level, Iterable<BlockPos> positions) throws PlanException {
        for (BlockPos pos : positions) if (!level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
            throw new PlanException("Move closer so the complete build area is loaded.");
        }
    }

    private PlanningAreas planningAreas(ActiveRequest request) {
        List<PlanningAreas.Box> locks = new java.util.ArrayList<>();
        for (RegionStore.Locked lock : lockedAreas) {
            if (!lock.region().dimension().equals(request.level.dimension()) || locks.size() >= 30) continue;
            try { locks.add(lock.region().relativeTo(request.origin, request.facing)); }
            catch (IllegalArgumentException ignored) { /* A distant locked area is still enforced at placement. */ }
        }
        request.guards.forEach(r -> locks.add(r.relativeTo(request.origin, request.facing)));
        return new PlanningAreas(request.focus == null ? null : request.focus.relativeTo(request.origin, request.facing), locks,
                request.connectionAreas.stream().map(r -> r.relativeTo(request.origin, request.facing)).toList());
    }

    public void linkBuild(CommandSourceStack source, int slot, String id) {
        if (!canChangeZone(source)) return;
        if (slot < 0 || slot > 1) { playerSession(ownerOf(source)).connections = null; sendStateTo(source); return; }
        BuildRecord record = id == null ? selected(source).orElse(null) : registry.get(id.replaceFirst("^#", "")).orElse(null);
        if (record == null || !canModify(source, record) || !record.dimension().equals(source.getLevel().dimension())) {
            send(source, Chat.error("Choose one of your (or your team's) buildings in this dimension.")); return;
        }
        String[] pair = playerSession(ownerOf(source)).connections();
        if (record.id().equals(pair[1 - slot])) { send(source, Chat.error("Choose two different buildings.")); return; }
        pair[slot] = record.id();
        send(source, Chat.info("Connection " + (slot == 0 ? "A" : "B") + ": “" + record.title() + "”."));
        sendStateTo(source);
    }

    public void connectBuilds(CommandSourceStack source, String description) {
        if (!canStartPlan(source) || !canChangeZone(source)) return;
        String actor = ownerOf(source);
        String[] pair = playerSession(actor).connections;
        if (pair == null || pair[0] == null || pair[1] == null) {
            send(source, Chat.error("Choose connection buildings A and B in Editing tools first.")); return;
        }
        BuildRecord a = registry.get(pair[0]).orElse(null), b = registry.get(pair[1]).orElse(null);
        if (a == null || b == null || a == b || !canModify(source, a) || !canModify(source, b)
                || !a.dimension().equals(source.getLevel().dimension()) || !b.dimension().equals(a.dimension())) {
            send(source, Chat.error("Both connection buildings must still exist in this dimension.")); return;
        }
        if (busy.contains(a.id()) || busy.contains(b.id())) {
            send(source, Chat.error(busyMessage(source, busy.contains(a.id()) ? a : b))); return;
        }
        try {
            List<BuildRegion> ends = new java.util.ArrayList<>();
            for (BuildRecord r : List.of(a, b)) {
                var blocks = registry.current(r);
                ensureLoaded(source.getLevel(), blocks.keySet());
                ends.add(BuildRegion.enclosing(r.dimension(), blocks.entrySet().stream().filter(e -> !e.getValue().isAir())
                        .map(Map.Entry::getKey).toList()));
            }
            // Room to turn a 3–5-block-wide path toward an entrance, with landings and verges.
            int margin = 12;
            BlockPos low = new BlockPos(Math.min(ends.get(0).min().getX(), ends.get(1).min().getX()) - margin,
                    Math.min(ends.get(0).min().getY(), ends.get(1).min().getY()),
                    Math.min(ends.get(0).min().getZ(), ends.get(1).min().getZ()) - margin);
            BlockPos high = new BlockPos(Math.max(ends.get(0).max().getX(), ends.get(1).max().getX()) + margin, low.getY(),
                    Math.max(ends.get(0).max().getZ(), ends.get(1).max().getZ()) + margin);
            BuildZone zone = BuildZone.between(a.dimension(), low, high);
            int x = low.getX() + (high.getX() - low.getX()) / 2, z = low.getZ() + (high.getZ() - low.getZ()) / 2;
            if (!source.getLevel().hasChunk(x >> 4, z >> 4)) throw new PlanException("Move closer to load the ground between the buildings.");
            int y = Ground.standingY(source.getLevel(), x, z, zone.displayY());
            ActiveRequest request = new ActiveRequest(actor, new Conversation(description.strip()), source.getLevel(),
                    new BlockPos(x, y, z), Direction.fromYRot(source.getRotation().y), null, PromptSpec.Placement.FRONT,
                    true, null, source);
            request.zone = zone; request.guards = List.copyOf(ends); request.connectionAreas = List.copyOf(ends);
            request.forcePreview = true;
            playerSession(actor).retainedPlan = null; playerSession(actor).alternatives = null; playerSession(actor).selectedBuild = null; playerSession(actor).part = null;
            playerSession(actor).zone = new ZoneSelection(zone.dimension(), low, zone);
            playerSession(actor).planning.start(request);
            send(source, Chat.progress("Connecting “" + a.title() + "” and “" + b.title()
                    + "” with 12 blocks of room around them. Both buildings are protected; the connector will be a separate, undoable build."));
            ask(request);
            sendStateTo(source);
        } catch (PlanException | IllegalArgumentException e) { send(source, Chat.error("Could not connect these buildings: " + e.getMessage())); }
    }

    /** A local summary, with no AI call. New-build and edit prompts receive snapshots automatically. */
    public void inspect(CommandSourceStack source) {
        BlockPos origin;
        Direction facing;
        InspectionRequest box;
        BlockPos pointed = BlockPos.containing(source.getPosition());
        if (source.getPlayer() != null && source.getPlayer().pick(EDIT_REACH, 0, false) instanceof BlockHitResult hit
                && hit.getType() == HitResult.Type.BLOCK) {
            pointed = hit.getBlockPos();
        }
        BlockPos at = pointed;
        Optional<BuildRecord> target = registry.all().stream()
                .filter(r -> r.dimension().equals(source.getLevel().dimension()) && r.baseline().containsKey(at))
                .max(java.util.Comparator.comparingLong(BuildRecord::lastChanged));
        ZoneSelection selected = playerSession(ownerOf(source)).zone;
        BuildZone zone = selected == null ? null : selected.zone();
        if (zone != null && zone.dimension().equals(source.getLevel().dimension())) {
            int x = zone.minX() + (zone.maxX() - zone.minX()) / 2;
            int z = zone.minZ() + (zone.maxZ() - zone.minZ()) / 2;
            int y = source.getLevel().hasChunk(x >> 4, z >> 4) ? Ground.standingY(source.getLevel(), x, z, zone.displayY())
                    : zone.displayY();
            origin = new BlockPos(x, y, z);
            facing = Direction.fromYRot(source.getRotation().y);
            box = WorldInspector.site(zone.relativeTo(origin, facing));
        } else if (target.isPresent()) {
            BuildRecord build = target.get();
            origin = build.origin();
            facing = build.facing();
            box = WorldInspector.around(build.baseline().keySet(), origin, facing);
        } else {
            origin = pointed.above();
            facing = Direction.fromYRot(source.getRotation().y);
            box = WorldInspector.site(true);
        }
        WorldSnapshot snapshot = WorldInspector.inspect(source.getLevel(), origin, facing, box);
        String blocks = snapshot.counts().entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(8).map(e -> e.getKey() + " × " + e.getValue()).collect(java.util.stream.Collectors.joining(", "));
        long unknown = snapshot.rows().stream().flatMapToInt(String::chars).filter(c -> c == '?').count();
        send(source, Chat.info("Read-only inspection at " + origin.toShortString() + ", sampling every " + snapshot.step()
                + " blocks: " + (blocks.isEmpty() ? "no loaded samples" : blocks) + ". " + unknown
                + " samples unavailable. Builds and edits use live inspections automatically."));
    }

    // ---- requests ----

    public void editorRequest(ServerPlayer player, dev.justbuild.net.BuildRequestPayload payload) {
        CommandSourceStack source = player.createCommandSourceStack();
        if (!JustBuildCommand.allowed(source)) { send(source, Chat.error("You cannot use JustBuild here.")); return; }
        String text = (payload.target().isEmpty() ? "" : "#" + payload.target() + " ") + payload.text();
        if (payload.action().equals("answer")) {
            ActiveRequest active = playerSession(ownerOf(source)).planning.current;
            if (active == null || !active.conversation.hasPendingQuestion() || !payload.question().equals(
                    dev.justbuild.net.BuildRequestPayload.questionKey(active.conversation.pendingQuestion()))) {
                send(source, Chat.error("That question is no longer waiting. Reopen the editor for the current request."));
                return;
            }
            answer(source, payload.text());
        } else if (payload.action().equals("revise")) revise(source, payload.text());
        else if (payload.options() > 1) startAlternatives(source, payload.action().equals("edit"), payload.options(), text);
        else if (payload.action().equals("edit")) edit(source, text);
        else start(source, text);
    }

    public void start(CommandSourceStack source, String description) {
        String actor = ownerOf(source);
        if (!canStartPlan(source)) return;
        BuildZone zone;
        try {
            zone = zoneFor(actor, source.getLevel().dimension());
        } catch (PlanException e) {
            send(source, Chat.error(e.getMessage()));
            return;
        }
        forgetEstablishedAlternatives(actor);
        replaceRequest(source, actor);
        playerSession(actor).retainedPlan = null;
        playerSession(actor).selectedBuild = null;
        playerSession(actor).part = null;
        sendStateTo(source);
        Direction facing = Direction.fromYRot(source.getRotation().y);
        BlockPos feet = BlockPos.containing(source.getPosition());
        BlockPos origin = feet.relative(facing, 2);
        PromptSpec.Placement placement = PromptSpec.Placement.FRONT;
        boolean centered = false;
        ServerPlayer player = source.getPlayer();
        if (zone != null) {
            int x = zone.minX() + (zone.maxX() - zone.minX()) / 2;
            int z = zone.minZ() + (zone.maxZ() - zone.minZ()) / 2;
            if (!source.getLevel().hasChunk(x >> 4, z >> 4)) {
                send(source, Chat.error("The center of your build zone is not loaded. Move closer and try again."));
                return;
            }
            origin = new BlockPos(x, Ground.standingY(source.getLevel(), x, z, zone.displayY()), z);
            centered = true;
            send(source, Chat.progress("Building inside your zone: " + zone.description() + "."));
        } else if (player != null) {
            HitResult hit = player.pick(EDIT_REACH, 0, false);
            double distance = hit.getLocation().distanceTo(player.getEyePosition());
            if (hit.getType() == HitResult.Type.BLOCK && hit instanceof BlockHitResult blockHit
                    && distance >= MIN_TARGET_DISTANCE) {
                origin = blockHit.getBlockPos().relative(blockHit.getDirection());
                Cell offset = PlanRotation.forFacing(facing.getName()).inverse().apply(new Cell(
                        feet.getX() - origin.getX(), feet.getY() - origin.getY(), feet.getZ() - origin.getZ()));
                placement = new PromptSpec.Placement((int) Math.round(distance), offset.x(), offset.y(), offset.z());
                centered = true;
                send(source, Chat.progress("Building at the spot you're looking at, " + Math.round(distance)
                        + " blocks away."));
            }
        }
        String direction = config.creativeDirection ? Guidance.direction(ThreadLocalRandom.current()) : null;
        ActiveRequest request = new ActiveRequest(actor, new Conversation(description.trim()), source.getLevel(),
                origin, facing, null, placement, centered, direction, source);
        request.zone = zone;
        playerSession(actor).planning.start(request);
        ask(request);
        sendStateTo(source);
    }

    /** Ends the actor's request in flight, if any, and says so: a new one replaces it. */
    private void replaceRequest(CommandSourceStack source, String actor) {
        ActiveRequest old = playerSession(actor).planning.current;
        if (old == null) return;
        endRequest(old, true);
        send(source, Chat.info("Stopped your earlier request “" + clip(old.conversation.request(), 60) + "” to start this one."));
    }

    public void edit(CommandSourceStack source, String text) {
        String actor = ownerOf(source);
        if (!canStartPlan(source)) return;
        EditTarget parsed = EditTarget.parse(text);
        String change = parsed.change();
        if (change.isBlank()) {
            send(source, Chat.error("Say what to change, e.g. /justbuild edit make the towers taller."));
            return;
        }
        Optional<BuildRecord> found = parsed.buildId().isPresent() ? registry.get(parsed.buildId().get())
                : findTarget(source);
        if (found.isEmpty()) {
            send(source, Chat.error(parsed.buildId().isPresent() ? "No saved build has id #" + parsed.buildId().get() + "."
                    : "Select a build in History or sneak-right-click it with the wand first."));
            return;
        }
        BuildRecord record = found.get();
        if (!mayModify(source, record)) return;
        if (busy.contains(record.id())) {
            send(source, Chat.error(busyMessage(source, record)));
            return;
        }
        if (server.getLevel(record.dimension()) == null) {
            send(source, Chat.error("“" + record.title() + "” is in a dimension that isn't loaded."));
            return;
        }
        if (!record.dimension().equals(source.getLevel().dimension())) {
            send(source, Chat.error("Travel to that build's dimension before editing it."));
            return;
        }
        BuildZone zone;
        BuildRegion focus = null;
        try {
            zone = zoneFor(actor, record.dimension());
            if (zone != null) {
                zone.relativeTo(record.origin(), record.facing());
            }
            PartSelection part = playerSession(actor).part;
            if (part != null && record.id().equals(part.buildId())) {
                if (part.region() == null) throw new PlanException(part.nextInstruction());
                focus = part.region();
                focus.relativeTo(record.origin(), record.facing());
            }
        } catch (PlanException | IllegalArgumentException e) {
            send(source, Chat.error(e.getMessage()));
            return;
        }
        forgetEstablishedAlternatives(actor);
        replaceRequest(source, actor);
        playerSession(actor).retainedPlan = null;
        playerSession(actor).selectedBuild = record.id();
        sendStateTo(source);
        ActiveRequest request = new ActiveRequest(actor, new Conversation(change.trim()), level(record.dimension()),
                record.origin(), record.facing(), record, null, false, null, source);
        request.zone = zone;
        request.focus = focus;
        playerSession(actor).planning.start(request);
        busy.add(record.id());
        request.forcePreview = true;
        send(source, Chat.progress("Editing “" + record.title() + "” (version " + record.current().number() + ")…"));
        ask(request);
        sendStateTo(source);
    }

    /**
     * Asks the AI to change the waiting new-build or design-copy preview. The preview stays until the revised plan
     * passes the usual checks; nothing is placed until the player chooses Instant Build or Guided Build.
     */
    public void revise(CommandSourceStack source, String text) {
        String actor = ownerOf(source);
        if (!canPlace(source)) return;
        if (construction.active(source.getPlayer())) {
            send(source, Chat.error("Close your survival construction project before revising a plan.")); return;
        }
        PlayerSession session = playerSession(actor);
        PendingPlacement preview = session.preview.current;
        String change = text.strip();
        if (preview == null || !preview.isNew()) {
            send(source, Chat.error("Revise changes a waiting new-build or design-copy preview. Send a request first.")); return;
        }
        if (!preview.level().dimension().equals(source.getLevel().dimension())) {
            send(source, Chat.error("Return to the preview's dimension first.")); return;
        }
        // An undo can still run while a preview waits; a revision on top of it would leave cancel() stopping only one.
        if (session.planning.current != null || scheduler.busy(actor)
                || session.alternatives != null && session.alternatives.collecting) {
            send(source, Chat.error("Wait for the current request or placement to finish, or stop it first.")); return;
        }
        if (change.isEmpty() || change.length() > dev.justbuild.net.BuildRequestPayload.MAX_TEXT) {
            send(source, Chat.error("Say what to change, e.g. /justbuild revise make the roof steeper.")); return;
        }
        ActiveRequest request = new ActiveRequest(actor, new Conversation(change), preview.level(), preview.record().origin(),
                preview.record().facing(), null, PromptSpec.Placement.FRONT, true, null, source);
        request.revising = preview;
        request.forcePreview = true;
        request.zone = preview.zone();
        request.guards = preview.guards();
        request.constraints = preview.record().constraints();
        request.projectId = preview.projectId();
        request.projectCaptured = true;
        ProjectStore.Project project = preview.projectId() == null ? null : projects.get(preview.projectId());
        if (project != null) request.theme = project.theme();
        session.planning.start(request);
        send(source, Chat.progress("Revising “" + preview.title() + "”…"));
        ask(request);
        sendStateTo(source);
    }

    public void startAlternatives(CommandSourceStack source, boolean edit, int count, String text) {
        String actor = ownerOf(source);
        if (!canStartPlan(source)) return;
        if (playerSession(actor).planning.current != null) { send(source, Chat.error("Finish or cancel your current AI request first.")); return; }
        EditTarget target = edit ? EditTarget.parse(text) : null;
        String base = edit ? target.change() : text.strip();
        try {
            String variant = AlternativeRequests.variant(base, 1, count);
            Alternatives batch = new Alternatives(base, count);
            playerSession(actor).alternatives = batch;
            if (edit) edit(source, target.buildId().map(id -> "#" + id + " ").orElse("") + variant);
            else start(source, variant);
            ActiveRequest request = playerSession(actor).planning.current;
            if (request == null) { playerSession(actor).alternatives = null; return; }
            batch.current = request;
            request.forcePreview = true;
            send(source, Chat.info("Planning " + count + " alternatives in order. This uses separate AI calls; nothing is placed until you choose and confirm."));
            sendStateTo(source);
        } catch (IllegalArgumentException e) { send(source, Chat.error(e.getMessage())); }
    }

    private void forgetEstablishedAlternatives(String actor) {
        Alternatives batch = playerSession(actor).alternatives;
        if (batch != null && batch.current != null) playerSession(actor).alternatives = null;
    }

    private void advanceAlternatives(ActiveRequest previous) {
        Alternatives batch = playerSession(previous.actor).alternatives;
        if (batch == null || batch.current != previous || !batch.collecting) return;
        if (batch.choices.size() < batch.index || batch.index >= batch.count) {
            finishAlternatives(previous.actor);
            return;
        }
        batch.index++;
        ActiveRequest next = new ActiveRequest(previous.actor,
                new Conversation(AlternativeRequests.variant(batch.base, batch.index, batch.count)), previous.level,
                previous.origin, previous.facing, previous.target, previous.placement, previous.centered,
                previous.target == null && config.creativeDirection ? Guidance.direction(ThreadLocalRandom.current()) : null,
                previous.source);
        for (Exchange exchange : previous.conversation.exchanges()) {
            next.conversation.ask(exchange.question(), List.of(), Instant.now());
            next.conversation.answer(exchange.answer());
        }
        next.zone = previous.zone; next.focus = previous.focus; next.guards = previous.guards;
        next.connectionAreas = previous.connectionAreas; next.forcePreview = true;
        next.projectCaptured = previous.projectCaptured; next.projectId = previous.projectId; next.theme = previous.theme;
        batch.current = next;
        playerSession(next.actor).planning.start(next);
        if (next.target != null) busy.add(next.target.id());
        send(next.source, Chat.progress("Option " + (batch.index - 1) + " is ready. Planning option " + batch.index + " of " + batch.count + "…"));
        ask(next);
        if (playerSession(next.actor).planning.current != next) finishAlternatives(next.actor);
        sendStateTo(next.source);
    }

    private void finishAlternatives(String actor) {
        Alternatives batch = playerSession(actor).alternatives;
        if (batch == null || !batch.collecting) return;
        batch.collecting = false;
        if (batch.choices.isEmpty()) {
            playerSession(actor).alternatives = null;
            if (batch.current != null && batch.current.target != null) busy.remove(batch.current.target.id());
        } else {
            playerSession(actor).retainedPlan = null;
            CommandSourceStack source = batch.current.source;
            long distinct = batch.choices.stream().map(PendingPlacement::placements).distinct().count();
            if (distinct < batch.choices.size()) send(source, Chat.info("Some options have identical blocks. You can still compare their previews."));
            for (int i = 0; i < batch.choices.size(); i++) batch.choices.set(i, batch.choices.get(i).withSource(source));
            batch.selected = 0;
            PendingPlacement first = batch.choices.getFirst();
            if (!first.isNew()) busy.add(first.record().id());
            offer(first, true);
            if (playerSession(actor).preview.current == first) {
                send(source, Chat.info(batch.choices.size() == 1 ? "Only 1 option is ready; Instant Build places it."
                        : batch.choices.size() + " options ready. Use ◀ ▶ at the base of the preview (or /justbuild preview option <n>) to compare; Instant Build places only the displayed option."));
            }
        }
        if (batch.current != null) sendStateTo(batch.current.source);
    }

    public void chooseAlternative(CommandSourceStack source, int number) {
        String actor = ownerOf(source);
        Alternatives batch = playerSession(actor).alternatives;
        if (batch == null || batch.collecting || number < 1 || number > batch.choices.size()) {
            send(source, Chat.error("Wait for your alternatives, then choose one of the available options.")); return;
        }
        PendingPlacement current = playerSession(actor).preview.current;
        if (current == null) { cancel(source); return; }
        if (!current.level().dimension().equals(source.getLevel().dimension())) {
            send(source, Chat.error("Return to the preview's dimension first.")); return;
        }
        showPreview(current, false); playerSession(actor).preview.take();
        batch.selected = number - 1;
        PendingPlacement choice = batch.choices.get(batch.selected).withSource(source);
        batch.choices.set(batch.selected, choice);
        if (!choice.isNew()) busy.add(choice.record().id());
        offer(choice, true);
    }

    public boolean hasPendingQuestion(ServerPlayer player) {
        ActiveRequest request = playerSession(player.getUUID().toString()).planning.current;
        return request != null && request.conversation.hasPendingQuestion();
    }

    private boolean canStartPlan(CommandSourceStack source) {
        if (!canPlace(source)) return false;
        if (construction.active(source.getPlayer())) {
            send(source, Chat.error("Close your survival construction project before starting another plan.")); return false;
        }
        String actor = ownerOf(source);
        if (playerSession(actor).preview.current != null || scheduler.busy(actor)) {
            send(source, Chat.error("Finish or cancel your current preview or placement before starting another plan."));
            return false;
        }
        return true;
    }

    private boolean canPlace(CommandSourceStack source) {
        if (!allowedNow(source)) {
            send(source, Chat.error("You no longer have permission to build here. Nothing new was placed."));
            return false;
        }
        if (buildLoadProblem != null) {
            send(source, Chat.error("AI placement is disabled because saved builds could not be read: " + buildLoadProblem
                    + ". Restore or repair the affected files in this world's justbuild/builds folder, then restart the world."));
            return false;
        }
        if (lockLoadProblem != null) {
            send(source, Chat.error("AI placement is disabled because locked areas could not be read: " + lockLoadProblem
                    + ". Repair justbuild/locked-areas.json in this world and use /justbuild reload."));
            return false;
        }
        return true;
    }

    public void answer(CommandSourceStack source, String text) {
        String actor = ownerOf(source);
        ActiveRequest request = playerSession(actor).planning.current;
        if (request == null || !request.conversation.hasPendingQuestion()) {
            send(source, Chat.error("No question is waiting for an answer."));
            return;
        }
        if (request.conversation.isExpired(Instant.now())) {
            endRequest(request, false);
            send(source, Chat.error("That question expired. Start again."));
            finishAlternatives(actor);
            return;
        }
        request.conversation.answer(text);
        request.source = source;
        ask(request);
    }

    private void ask(ActiveRequest request) {
        captureProject(request);
        // The worker reads the settings for minutes; a copy keeps /justbuild detail or style from changing them midway.
        JustBuildConfig cfg = ConfigStore.copy(config);
        ServerPlayer player = request.source.getPlayer();
        AiSource aiSource = aiSource(request.source);
        if (aiSource == AiSource.REFUSE) {
            endRequest(request, false);
            send(request.source, Chat.error(NEED_OWN_AI));
            finishAlternatives(request.actor);
            return;
        }
        BuildProvider provider = aiSource == AiSource.PLAYER
                ? new RelayProvider(relay, player.getUUID(), playerSession(player.getUUID().toString()).hello.label(), cfg.limits(),
                        cfg.detailLevel())
                : Providers.create(cfg, cfg.active());
        ExecutorService pool = aiSource == AiSource.PLAYER ? relayWorkers : workers;
        int remaining = request.conversation.questionsRemaining(cfg.maxQuestions);
        boolean guidesOn = cfg.guides && cfg.detailLevel() != Guidance.DetailLevel.QUICK;
        if (request.guides == null) request.guides = List.of();
        List<GuideLibrary.Guide> guidesRead = request.guides;
        boolean roundUsed = request.guideRound;
        boolean offerGuides = guidesOn && !roundUsed;
        InspectionRequest initialBox = request.target != null
                ? WorldInspector.around(request.target.baseline().keySet(), request.origin, request.facing)
                : request.zone != null ? WorldInspector.site(request.zone.relativeTo(request.origin, request.facing))
                : request.revising != null ? WorldInspector.around(request.revising.placements().keySet(), request.origin, request.facing)
                : WorldInspector.site(request.centered);
        WorldSnapshot initial = WorldInspector.inspect(request.level, request.origin, request.facing, initialBox);
        List<WorldSnapshot> observations = List.of(initial);
        PlanningFeedback previousReads = PlanningFeedback.EMPTY;
        for (VoxelVolume read : request.feedback.inspections()) {
            if (read.box().target() != InspectionRequest.Target.SOURCE) continue;
            int cap = read.box().mode() == InspectionRequest.Mode.SURFACE ? VoxelVolume.MAX_CELLS : 4096;
            previousReads = previousReads.withRead(PlanningCheck.capture(read.box(), request.origin, request.facing,
                    p -> loadedState(request.level, p), cap, read.box().mode() != InspectionRequest.Mode.SAMPLE));
        }
        for (var query : request.feedback.blockQueries()) previousReads = previousReads.withQuery(query);
        PromptSpec spec = spec(request, remaining, cfg).withGuides(offerGuides, ids(guidesRead))
                .withWorld(request.zone == null ? null : request.zone.relativeTo(request.origin, request.facing),
                        observations, InspectionRequest.MAX_ROUNDS - request.toolReads)
                .withAreas(planningAreas(request)).withTheme(request.theme).withConstraints(request.constraints)
                .withFeedback(new PlanningFeedback(null, List.of(),
                        PlanningCheck.capture(initialBox, request.origin, request.facing, p -> loadedState(request.level, p),
                                VoxelVolume.MAX_CELLS, false), null, null, null).withSourceReads(previousReads));
        request.feedback = spec.feedback();
        String content;
        try {
            content = spec.userContent();
        } catch (PlanException e) {
            endRequest(request, false);
            send(request.source, Chat.error("Could not prepare the request: " + e.getMessage()));
            finishAlternatives(request.actor);
            return;
        }
        PlanRequest planRequest = new PlanRequest(spec.systemPrompt(cfg.limits(), cfg.detailLevel()), content,
                BuildPlanSchema.JSON, cfg.model(), cfg.timeout(), spec);
        CancellationToken token = new CancellationToken();
        request.inFlight = token;
        RequestStatus status = new RequestStatus(provider.displayName(), Instant.now(),
                playerSession(request.actor).usage);
        request.status = status;
        int reviewRounds = cfg.detailLevel().reviewRounds();
        send(request.source, Chat.working("Sent to " + provider.displayName() + " (AI review: "
                + cfg.detailLevel().reviewLabel() + "). This usually takes 20–90 seconds per step; I'll post updates here."));
        pool.execute(() -> {
            String raw = null;
            PlanRequest asked = planRequest;
            List<GuideLibrary.Guide> read = guidesRead;
            boolean round = roundUsed;
            try {
                raw = complete(provider, asked, token, status, false);
                PlanResponse response = PlanParser.parse(raw);
                PromptSpec current = spec;
                while (response instanceof PlanResponse.Guides || response instanceof PlanResponse.Inspect || response instanceof PlanResponse.Blocks) {
                    if (response instanceof PlanResponse.Guides wanted) {
                        if (!current.offerGuides() || round) {
                            throw new PlanException("it asked for guides when no guide round was available");
                        }
                        round = true;
                        read = GuideLibrary.defaults().pick(wanted.ids());
                        if (read.isEmpty()) throw new PlanException("the AI requested no known building guides");
                        String line = "The AI is reading guides: " + titles(read) + "…";
                        onServer(() -> {
                            if (isCurrent(request, token)) {
                                send(request.source, Chat.progress(line));
                            }
                        });
                        current = current.withGuides(false, ids(read));
                    } else {
                        current = inspectTool(request, token, current, response);
                    }
                    asked = new PlanRequest(current.systemPrompt(cfg.limits(), cfg.detailLevel()), current.userContent(),
                            planRequest.schemaJson(), planRequest.model(), planRequest.timeout(), current);
                    raw = complete(provider, asked, token, status, false);
                    response = PlanParser.parse(raw);
                }
                // Reviews keep the remaining shared read budget; size retries cannot ask for more reads.
                current = current.withGuides(false, ids(read));
                asked = new PlanRequest(current.systemPrompt(cfg.limits(), cfg.detailLevel()), current.userContent(),
                        asked.schemaJson(), asked.model(), asked.timeout(), current);
                if (response instanceof PlanResponse.Build over) {
                    Reviewed fitted = fitToLimit(provider, asked, over.plan(), raw, cfg, token, status,
                            request.source);
                    response = new PlanResponse.Build(fitted.plan());
                    raw = fitted.raw();
                }
                if (response instanceof PlanResponse.Build first) {
                    Reviewed reviewed = review(request, provider, asked, first.plan(), raw, reviewRounds, cfg, token,
                            status);
                    response = new PlanResponse.Build(reviewed.plan());
                    raw = reviewed.raw();
                }
                PlanResponse finalResponse = response;
                String finalRaw = raw;
                List<GuideLibrary.Guide> finalRead = read;
                boolean finalRound = round;
                onServer(() -> {
                    if (isCurrent(request, token)) {
                        request.guides = finalRead;
                        request.guideRound = finalRound;
                    }
                    onResponse(request, token, finalResponse, finalRaw);
                });
            } catch (CancelledException e) {
                // cancelled by the player or replaced by a newer request
            } catch (PlanException e) {
                saveInvalid(raw);
                onServer(() -> fail(request, token, "The AI returned an invalid plan: " + e.getMessage()));
            } catch (ProviderException e) {
                onServer(() -> fail(request, token, e.getMessage()));
            } catch (RuntimeException e) {
                LOGGER.error("JustBuild request failed", e);
                onServer(() -> fail(request, token, "Unexpected error: " + e));
            }
        });
    }

    private static BlockState loadedState(ServerLevel level, BlockPos pos) {
        return level.isOutsideBuildHeight(pos) || !level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)
                ? null : level.getBlockState(pos);
    }

    private <T> T planningRead(ActiveRequest request, CancellationToken token, java.util.concurrent.Callable<T> read)
            throws ProviderException {
        var future = new java.util.concurrent.CompletableFuture<T>();
        token.onCancel(() -> future.completeExceptionally(new CancelledException()));
        onServer(() -> {
            if (!isCurrent(request, token)) { future.completeExceptionally(new CancelledException()); return; }
            try { future.complete(read.call()); } catch (Exception e) { future.completeExceptionally(e); }
        });
        try { return future.get(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new CancelledException(); }
        catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof CancelledException cancelled) throw cancelled;
            throw new ProviderException("Planning check failed: " + e.getCause().getMessage());
        }
    }

    private PlanningFeedback checkForRequest(ActiveRequest request, CancellationToken token, BuildPlan plan,
            JustBuildConfig cfg) throws ProviderException {
        return planningRead(request, token, () -> {
            return retainCheck(request, planningCheck(request, plan, cfg, p -> loadedState(request.level, p)));
        });
    }

    private PlanningCheck.Result planningCheck(ActiveRequest request, BuildPlan plan, JustBuildConfig cfg,
            java.util.function.Function<BlockPos, BlockState> live) {
        return PlanningCheck.check(plan, request.origin, request.facing, request.level, live,
                request.target == null ? Map.of() : request.target.baseline(),
                request.target == null ? Map.of() : registry.current(request.target), request.target != null,
                // getBlockEntity would load an unloaded chunk, bringing back an area the player has left.
                p -> mayChange(request, p) && (!request.level.hasChunk(p.getX() >> 4, p.getZ() >> 4)
                        || request.level.getBlockEntity(p) == null),
                request.zone, request.constraints, cfg.limits());
    }

    private PlanningFeedback retainCheck(ActiveRequest request, PlanningCheck.Result result) {
        request.candidateChanges = result.changes();
        request.feedback = result.feedback().withSourceReads(request.feedback);
        return request.feedback;
    }

    private record CheckedReview(dev.justbuild.world.ReviewCheck.Verdict verdict, PlanningFeedback feedback) { }

    private CheckedReview checkReview(ActiveRequest request, CancellationToken token, BuildPlan draft, BuildPlan revised,
            JustBuildConfig cfg) throws ProviderException {
        return planningRead(request, token, () -> {
            // Compare both proposals against the same loaded world states in one server-thread read.
            Map<BlockPos, BlockState> cache = new HashMap<>();
            java.util.function.Function<BlockPos, BlockState> live = p -> {
                if (!cache.containsKey(p)) cache.put(p.immutable(), loadedState(request.level, p));
                return cache.get(p);
            };
            var before = planningCheck(request, draft, cfg, live);
            var after = planningCheck(request, revised, cfg, live);
            var verdict = dev.justbuild.world.ReviewCheck.compare(before, after,
                    request.target == null ? Map.of() : registry.current(request.target), request.level.dimension(), live);
            return new CheckedReview(verdict, retainCheck(request, verdict.accepted() ? after : before));
        });
    }

    private PromptSpec inspectTool(ActiveRequest request, CancellationToken token, PromptSpec spec, PlanResponse response)
            throws ProviderException {
        return planningRead(request, token, () -> {
            if (request.toolReads >= InspectionRequest.MAX_ROUNDS) throw new IllegalArgumentException("The inspection budget is exhausted.");
            request.toolReads++;
            PlanningFeedback feedback = spec.feedback();
            if (response instanceof PlanResponse.Blocks blocks) feedback = feedback.withQuery(blocks.query());
            else if (response instanceof PlanResponse.Inspect read) {
                InspectionRequest box = read.inspection();
                if (box.target() == InspectionRequest.Target.CANDIDATE && request.candidateChanges == null) {
                    feedback = feedback.withReadIssue(PlanningFeedback.ReadIssue.NO_CANDIDATE);
                } else {
                java.util.function.Function<BlockPos, BlockState> lookup = p -> box.target() == InspectionRequest.Target.CANDIDATE
                        && request.candidateChanges.containsKey(p) ? request.candidateChanges.get(p) : loadedState(request.level, p);
                boolean exact = box.mode() != InspectionRequest.Mode.SAMPLE;
                int cap = box.mode() == InspectionRequest.Mode.SURFACE ? VoxelVolume.MAX_CELLS : 4096;
                try { feedback = feedback.withRead(PlanningCheck.capture(box, request.origin, request.facing, lookup, cap, exact)); }
                catch (IllegalArgumentException tooLarge) { feedback = feedback.withReadIssue(PlanningFeedback.ReadIssue.REGION_TOO_LARGE); }
                }
            }
            request.feedback = feedback;
            send(request.source, Chat.progress("Inspecting build data (" + request.toolReads + " of " + InspectionRequest.MAX_ROUNDS + ")…"));
            return spec.withFeedback(feedback).withWorld(spec.zone(), spec.inspections(), InspectionRequest.MAX_ROUNDS - request.toolReads);
        });
    }

    private record Reviewed(BuildPlan plan, String raw) {
    }

    /** "1 spot", "3 spots". */
    static String count(int n, String noun) {
        return n == 1 ? "1 " + noun : n + " " + noun + "s";
    }

    private static String complete(BuildProvider provider, PlanRequest request, CancellationToken token,
            RequestStatus status, boolean review) throws ProviderException {
        token.throwIfCancelled();
        try (var call = status.begin(review)) { return provider.complete(request, token, call); }
    }

    private static String titles(List<GuideLibrary.Guide> guides) {
        return guides.stream().map(GuideLibrary.Guide::title).collect(java.util.stream.Collectors.joining(", "));
    }

    private static List<String> ids(List<GuideLibrary.Guide> guides) {
        return guides == null ? List.of() : guides.stream().map(GuideLibrary.Guide::id).toList();
    }

    /** Retries asking the AI for a plan under maxBlocks, each with the count it went over by. */
    static final int SHRINK_ROUNDS = 2;

    /**
     * Worker thread: while the plan is over the block limit (counted on flat ground), shows the AI the count and asks
     * for a smaller version, up to {@link #SHRINK_ROUNDS} times. An answer that is not a usable build keeps the
     * previous plan, and placement then reports the limit.
     */
    private Reviewed fitToLimit(BuildProvider provider, PlanRequest original, BuildPlan plan, String raw,
            JustBuildConfig cfg, CancellationToken token, RequestStatus status, CommandSourceStack source)
            throws ProviderException, PlanException {
        // Sizes and repeats are checked before anything is drawn (an absurd radius would never finish drawing).
        try { cfg.limits().check(plan); }
        catch (PlanException invalidGeometry) { return new Reviewed(plan, raw); } // Diagnostic review repairs bounded scalar/geometry failures.
        // Count past the limit so the AI learns how far over it is; capped to bound memory.
        int countCap = (int) Math.min(4L * cfg.maxBlocks, 1_000_000L);
        for (int round = 1; round <= SHRINK_ROUNDS; round++) {
            int blocks;
            try { blocks = PlanSize.blocks(plan, countCap); }
            catch (dev.justbuild.plan.PlanTooMuchWorkException tooMuchWork) { return new Reviewed(plan, raw); }
            if (blocks <= cfg.maxBlocks) {
                break;
            }
            String count = PlanSize.describe(blocks, countCap);
            int attempt = round;
            onServer(() -> { if (!token.isCancelled()) send(source, Chat.progress("The plan places " + count + " blocks; the limit is "
                    + String.format(java.util.Locale.ROOT, "%,d", cfg.maxBlocks) + ". Asking the AI for a smaller "
                    + "version (try " + attempt + " of " + SHRINK_ROUNDS + ")…")); });
            status.shrinking(round, SHRINK_ROUNDS);
            PromptSpec smallerSpec = original.spec().shrink(PlanWriter.write(plan));
            PlanRequest smaller = new PlanRequest(smallerSpec.systemPrompt(cfg.limits(), cfg.detailLevel()), smallerSpec.userContent(),
                    original.schemaJson(), original.model(), original.timeout(), smallerSpec);
            String smallerRaw = complete(provider, smaller, token, status, false);
            try {
                if (!(PlanParser.parse(smallerRaw) instanceof PlanResponse.Build build)) {
                    LOGGER.warn("Smaller-plan try {} did not return a build; keeping the previous plan", round);
                    break;
                }
                cfg.limits().check(build.plan());
                plan = build.plan();
                raw = smallerRaw;
            } catch (PlanException e) {
                saveInvalid(smallerRaw);
                LOGGER.warn("Smaller-plan try {} was invalid ({}); keeping the previous plan", round, e.getMessage());
                break;
            }
        }
        return new Reviewed(plan, raw);
    }

    /**
     * Worker thread: shows the AI its plan as top/front/side views and asks for an improved plan, up to
     * {@code rounds} times. A round that fails or returns something unusable keeps the previous plan.
     */
    private Reviewed review(ActiveRequest active, BuildProvider provider, PlanRequest original, BuildPlan plan, String raw, int rounds,
            JustBuildConfig cfg, CancellationToken token, RequestStatus status) throws ProviderException {
        PlanningFeedback evidence = checkForRequest(active, token, plan, cfg);
        if (rounds == 0 && evidence.needsCorrection()) rounds = 1;
        for (int round = 1; round <= rounds; round++) {
            status.reviewing(round, rounds);
            try {
                // The spec draws the views itself, on flat ground at y = -1, since the worker cannot read the world.
                PromptSpec reviewSpec = original.spec().review(PlanWriter.write(plan), round, rounds)
                        .withFeedback(evidence).withWorld(original.spec().zone(), original.spec().inspections(),
                                Math.max(0, InspectionRequest.MAX_ROUNDS - active.toolReads));
                PlanRequest reviewRequest = new PlanRequest(reviewSpec.systemPrompt(cfg.limits(), cfg.detailLevel()), reviewSpec.userContent(),
                        original.schemaJson(), original.model(), original.timeout(), reviewSpec);
                String reviewedRaw = complete(provider, reviewRequest, token, status, true);
                PlanResponse answer = PlanParser.parse(reviewedRaw);
                while (answer instanceof PlanResponse.Inspect || answer instanceof PlanResponse.Blocks) {
                    reviewSpec = inspectTool(active, token, reviewSpec, answer);
                    reviewRequest = new PlanRequest(reviewSpec.systemPrompt(cfg.limits(), cfg.detailLevel()),
                            reviewSpec.userContent(), original.schemaJson(), original.model(), original.timeout(), reviewSpec);
                    reviewedRaw = complete(provider, reviewRequest, token, status, true);
                    answer = PlanParser.parse(reviewedRaw);
                }
                if (answer instanceof PlanResponse.Build better) {
                    cfg.limits().check(better.plan());
                    if (PlanSize.blocks(better.plan(), cfg.maxBlocks) == PlanSize.OVER) {
                        LOGGER.warn("Review round {} went over {} blocks; keeping the previous plan", round, cfg.maxBlocks);
                        continue;
                    }
                    CheckedReview checked = checkReview(active, token, plan, better.plan(), cfg);
                    evidence = checked.feedback();
                    if (!checked.verdict().accepted()) {
                        LOGGER.warn("Review round {} {}; keeping the previous plan", round, checked.verdict().reason());
                        continue;
                    }
                    plan = better.plan();
                    raw = reviewedRaw;
                } else {
                    LOGGER.warn("Review round {} did not return a build; keeping the previous plan", round);
                }
            } catch (CancelledException e) {
                throw e;
            } catch (PlanException | ProviderException e) {
                LOGGER.warn("Review round {} failed ({}); keeping the previous plan", round, e.getMessage());
            }
        }
        return new Reviewed(plan, raw);
    }

    /**
     * What this request's AI call is made from. An edit carries the last {@link PromptCheck#MAX_HISTORY} history
     * texts, each cut to {@link PromptCheck#MAX_HISTORY_TEXT}, which is what a player's game accepts.
     */
    private PromptSpec spec(ActiveRequest request, int remaining, JustBuildConfig cfg) {
        String dimension = request.level.dimension().identifier().toString();
        String biome = request.level.getBiome(request.origin).unwrapKey()
                .map(key -> key.identifier().toString()).orElse("unknown");
        if (request.revising != null) {
            PendingPlacement preview = request.revising;
            PreviewRevisions.History past = preview.revisions().history(preview.record().current().request());
            return PromptSpec.revision(dimension, biome, cfg.styleChoice().id(), past.original(), past.changes(),
                    PlanWriter.write(preview.sourcePlan()), request.conversation.request(), request.conversation.exchanges(),
                    remaining, TerrainSampler.sample(request.level, request.origin, request.facing, true), cfg.maxBlocks);
        }
        if (request.target == null) {
            return PromptSpec.newBuild(dimension, biome, request.placement, cfg.styleChoice().id(), request.direction,
                    request.conversation.request(), request.conversation.exchanges(), remaining,
                    TerrainSampler.sample(request.level, request.origin, request.facing, request.centered),
                    cfg.maxBlocks);
        }
        List<BuildRecord.Version> versions = request.target.versions();
        List<String> earlier = versions.subList(Math.max(1, versions.size() - PromptCheck.MAX_HISTORY), versions.size())
                .stream().map(v -> clip(v.request(), PromptCheck.MAX_HISTORY_TEXT)).toList();
        return PromptSpec.edit(dimension, biome, clip(versions.get(0).request(), PromptCheck.MAX_HISTORY_TEXT), earlier,
                PlanWriter.write(request.target.current().plan()), request.conversation.request(),
                request.conversation.exchanges(), remaining, cfg.maxBlocks);
    }

    private static String clip(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }

    /** The request with its answers (and creative direction), as stored on the build version. */
    private static String describe(ActiveRequest request) {
        Conversation conversation = request.conversation;
        StringBuilder sb = new StringBuilder(conversation.request());
        if (request.direction != null) {
            sb.append(" (direction: ").append(request.direction).append(')');
        }
        for (Exchange e : conversation.exchanges()) {
            sb.append(" (").append(e.question()).append(" ").append(e.answer()).append(')');
        }
        return sb.toString();
    }

    private boolean isCurrent(ActiveRequest request, CancellationToken token) {
        PlayerSession session = playerSessions.get(request.actor);
        return session != null && session.planning.isCurrent(request, token);
    }

    private void endRequest(ActiveRequest request, boolean cancel) {
        if (request == null) return;
        PlayerSession session = playerSessions.get(request.actor);
        if (session != null) session.planning.end(request, cancel);
    }

    private void fail(ActiveRequest request, CancellationToken token, String message) {
        if (!isCurrent(request, token)) {
            return;
        }
        endRequest(request, false);
        send(request.source, Chat.error(message));
        finishAlternatives(request.actor);
        sendStateTo(request.source);
    }

    private void onResponse(ActiveRequest request, CancellationToken token, PlanResponse response, String raw) {
        if (!isCurrent(request, token)) {
            return;
        }
        if (!canPlace(request.source)) {
            endRequest(request, true);
            playerSession(request.actor).alternatives = null;
            sendStateTo(request.source);
            return;
        }
        request.inFlight = null;
        switch (response) {
            case PlanResponse.Question q -> {
                if (request.conversation.questionsRemaining(config.maxQuestions) <= 0) {
                    endRequest(request, false);
                    send(request.source, Chat.error("The AI kept asking questions. Try a more specific description."));
                    break;
                }
                request.conversation.ask(q.question(), q.options(), Instant.now());
                send(request.source, Chat.progress("The AI has a question (after " + elapsed(request) + "):"));
                send(request.source, Chat.question(q.question(), q.options()));
            }
            case PlanResponse.Declined d -> {
                endRequest(request, false);
                send(request.source, Chat.error("The AI declined: " + d.reason()));
            }
            case PlanResponse.Blocks b -> {
                endRequest(request, false);
                send(request.source, Chat.error("The AI requested a block lookup instead of finishing its plan."));
            }
            case PlanResponse.Inspect i -> {
                endRequest(request, false);
                send(request.source, Chat.error("The AI requested an inspection instead of finishing its plan."));
            }
            case PlanResponse.Guides g -> {
                // The worker reads guides; reaching here means the AI asked for them again instead of planning.
                endRequest(request, false);
                send(request.source, Chat.error("The AI asked for guides again instead of planning. Try again."));
            }
            case PlanResponse.Build b -> {
                if (request.status != null) request.status.finished();
                savePlan(raw, b.plan().title());
                if (request.target == null) {
                    placeNew(request, b.plan());
                } else {
                    placeEdit(request, b.plan());
                }
                advanceAlternatives(request);
            }
        }
        if (!(response instanceof PlanResponse.Build) && (! (response instanceof PlanResponse.Question)
                || playerSession(request.actor).planning.current != request)) finishAlternatives(request.actor);
        sendStateTo(request.source);
    }

    private void placeNew(ActiveRequest request, BuildPlan plan) {
        captureProject(request);
        endRequest(request, false);
        PendingPlacement revised = request.revising;
        // A revision replaces only the preview it started from; one discarded or replaced meanwhile drops the result.
        if (revised != null && playerSession(request.actor).preview.current != revised) return;
        String failure = revised != null ? "Preview unchanged: " : "The plan can't be built: ";
        JustBuildConfig cfg = config;
        try {
            cfg.limits().check(plan);
            PlanPipeline.Result result = PlanPipeline.run(plan, request.origin, request.facing, request.level,
                    pos -> readLoaded(request.level, pos), cfg.maxBlocks);
            Map<BlockPos, BlockState> placements = new LinkedHashMap<>(result.placements());
            placements.keySet().removeIf(pos -> !mayChange(request, pos));
            BuildZone area = request.zone != null ? request.zone
                    : footprintOf(request.level.dimension(), request.origin, placements.keySet());
            reportKept(request.source, keepOthers(placements, othersCells(request.source, request.level.dimension(), area, null)));
            placements = safe(request.source, request.level, placements, area, pos -> mayChange(request, pos));
            request.constraints.check(request.origin, placements, Map.of());
            if (revised != null) checkRevisionArea(request, placements.keySet());
            else if (!checkOrRetain(request, plan, placements.keySet())) return;
            if (placements.isEmpty()) {
                send(request.source, Chat.error(revised != null ? failure + "the revised plan has no blocks that can be placed here."
                        : "The plan has no blocks that can be placed here."));
                return;
            }
            BuildPlan finalPlan = placements.equals(result.placements()) ? plan
                    : PlanSnapshot.capture(plan.title(), request.origin, request.facing, placements);
            cfg.limits().check(finalPlan);
            String description = revised != null
                    ? PreviewRevisions.description(revised.record().current().request(), describe(request)) : describe(request);
            BuildRecord record = new BuildRecord(revised != null ? revised.record().id() : registry.newId(), request.actor,
                    request.level.dimension(), request.origin, request.facing,
                    List.of(new BuildRecord.Version(description, finalPlan, System.currentTimeMillis())), Map.of());
            record.zone(request.zone != null ? request.zone : footprint(record, placements.keySet()));
            record.constraints(request.constraints);
            if (revised != null) {
                // Only what the player typed first: a player's own AI recognizes it, and "again" reruns it.
                if (revised.record().typedRequest() != null) record.typedRequest(revised.record().typedRequest());
            } else if (request.typedRequest != null) record.typedRequest(request.typedRequest);
            else if (request.status != null) record.typedRequest(request.conversation.request()); // AI-planned, retried or not
            reportSkipped(request.source, result);
            if (placements.size() < result.placements().size()) send(request.source, Chat.info("Kept "
                    + (result.placements().size() - placements.size()) + " planned blocks inside protected areas unchanged."));
            PendingPlacement next = pending(request, record, true, null, placements, Map.of(), plan);
            if (revised != null) {
                PreviewRevisions revisions = revised.revisions().after(describe(request), new PreviewRevisions.Earlier(
                        revised.sourcePlan(), revised.title(), revised.summary(), revised.record().current().request()));
                send(request.source, Chat.info("Revised “" + next.title() + "” (revision " + revisions.count() + ")."));
                replacePreview(revised, next.withRevisions(revisions, "Revised (revision " + revisions.count() + "). "));
                return;
            }
            offer(next, request.forcePreview, autoBuilds(request));
        } catch (PlanTooLargeException e) {
            send(request.source, Chat.error(revised != null ? failure + e.getMessage() : tooLarge("The plan", e)));
        } catch (PlanException e) {
            send(request.source, Chat.error(failure + e.getMessage()));
        }
    }

    private void placeEdit(ActiveRequest request, BuildPlan plan) {
        BuildRecord record = request.target;
        if (registry.get(record.id()).orElse(null) != record || !mayModify(request.source, record)) {
            endRequest(request, true);
            return;
        }
        JustBuildConfig cfg = config;
        try {
            cfg.limits().check(plan);
            Map<BlockPos, BlockState> baseline = record.baseline();
            PlanPipeline.Result result = PlanPipeline.run(plan, record.origin(), record.facing(), request.level,
                    pos -> baseline.containsKey(pos) ? baseline.get(pos) : readLoaded(request.level, pos),
                    cfg.maxBlocks);
            Map<BlockPos, BlockState> before = new LinkedHashMap<>(registry.current(record));
            Map<BlockPos, BlockState> proposedDiff = Diff.between(before, result.placements(), baseline);
            int proposed = proposedDiff.size();
            Map<BlockPos, BlockState> diff = new LinkedHashMap<>(proposedDiff);
            diff.keySet().removeIf(pos -> !mayChange(request, pos));
            BuildZone area = safetyArea(record, request.zone, union(before.keySet(), diff.keySet()));
            reportKept(request.source, keepOthers(diff, othersCells(request.source, request.level.dimension(), area, record.id())));
            diff = safe(request.source, request.level, diff, area, pos -> mayChange(request, pos));
            request.constraints.check(record.origin(), diff, before);
            if (!checkOrRetain(request, plan, diff.keySet())) return;
            if (diff.isEmpty()) {
                endRequest(request, false);
                send(request.source, Chat.info("The AI's revision of “" + record.title() + "” didn't change any blocks."));
                return;
            }
            BuildPlan finalPlan = plan;
            if (!diff.equals(proposedDiff) || request.focus != null) {
                Map<BlockPos, BlockState> preserved = new LinkedHashMap<>(before);
                for (BlockPos pos : diff.keySet()) {
                    if (result.placements().containsKey(pos)) preserved.put(pos, result.placements().get(pos));
                    else preserved.remove(pos);
                }
                finalPlan = PlanSnapshot.capture(plan.title(), record.origin(), record.facing(), preserved);
                cfg.limits().check(finalPlan);
            }
            request.handedOff = true;
            endRequest(request, false);
            BuildRecord.Version version = new BuildRecord.Version(describe(request), finalPlan,
                    System.currentTimeMillis());
            reportSkipped(request.source, result);
            if (diff.size() < proposed) send(request.source, Chat.info("Kept " + (proposed - diff.size())
                    + " changes outside the selected part or inside locked areas unchanged."));
            offer(pending(request, record, false, version, diff, before, finalPlan), request.forcePreview, autoBuilds(request));
        } catch (PlanTooLargeException e) {
            endRequest(request, false);
            send(request.source, Chat.error(tooLarge("The revised plan", e)));
        } catch (PlanException e) {
            endRequest(request, false);
            send(request.source, Chat.error("The revised plan can't be built: " + e.getMessage()));
        }
    }

    private static String tooLarge(String what, PlanTooLargeException e) {
        return what + " can't be built: " + e.getMessage() + ", even after asking the AI for a smaller version. Ask "
                + "for something smaller, or raise maxBlocks in config/justbuild.json and run /justbuild reload.";
    }

    private static BlockState readLoaded(ServerLevel level, BlockPos pos) {
        return level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4) ? level.getBlockState(pos)
                : net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
    }

    /** Intended plans may mention protected positions that were skipped. Only captured positions establish ownership. */
    private static Map<BlockPos, BlockState> owned(BuildRecord record, Map<BlockPos, BlockState> intended) {
        Map<BlockPos, BlockState> owned = new HashMap<>();
        intended.forEach((pos, state) -> { if (record.baseline().containsKey(pos)) owned.put(pos, state); });
        return owned;
    }

    private static BuildZone footprint(BuildRecord record, Iterable<BlockPos> positions) {
        return footprintOf(record.dimension(), record.origin(), positions);
    }

    /** Edits may contain water within the complete saved building, including cells the edit leaves unchanged. */
    private static BuildZone safetyArea(BuildRecord record, BuildZone zone, Iterable<BlockPos> positions) {
        return zone != null ? zone : record.zone() != null ? record.zone() : footprint(record, positions);
    }

    private static BuildZone footprintOf(ResourceKey<Level> dimension, BlockPos origin, Iterable<BlockPos> positions) {
        try { return BuildZone.between(dimension, origin, origin).including(positions); }
        catch (IllegalArgumentException e) { return null; }
    }

    private static java.util.Set<BlockPos> union(java.util.Set<BlockPos> a, java.util.Set<BlockPos> b) {
        java.util.Set<BlockPos> all = new java.util.HashSet<>(a);
        all.addAll(b);
        return all;
    }

    /**
     * New plans only: leaves out blocks that act beyond the build (pistons, redstone, flowing water, falling sand,
     * golems) or can't be removed, and says which. {@code area} is the zone or footprint the build may affect.
     */
    private static Map<BlockPos, BlockState> safe(CommandSourceStack source, ServerLevel level,
            Map<BlockPos, BlockState> placements, BuildZone area, java.util.function.Predicate<BlockPos> mayChange) {
        PlacementSafety.Result safe = PlacementSafety.check(placements, pos -> readLoaded(level, pos),
                pos -> (area == null || area.contains(pos)) && mayChange.test(pos));
        if (!safe.dropped().isEmpty()) {
            send(source, Chat.info("Left out: " + String.join(", ", safe.dropped()) + "."));
        }
        return safe.placements();
    }

    /**
     * Keeps a finished plan for /justbuild retry when its area isn't loaded (the player moved away or changed dimension
     * while the AI worked) or it leaves the zone; true when it can go ahead.
     */
    private boolean checkOrRetain(ActiveRequest request, BuildPlan plan, Iterable<BlockPos> changes) {
        ResourceKey<Level> where = request.level.dimension();
        String problem = null, then = "Enlarge the area, then", again = " with a larger area";
        try {
            ensureLoaded(request.level, changes);
        } catch (PlanException e) {
            boolean away = !where.equals(dimensionOf(request.actor, request.source));
            problem = away ? "The plan is ready, but you left " + where.identifier() + ", so its area isn't loaded."
                    : "The plan is ready, but its area isn't loaded.";
            then = away ? "Return to " + where.identifier() + ", then" : "Move closer, then";
            again = "";
        }
        if (problem == null && request.zone != null) {
            try { request.zone.check(where, changes); }
            catch (PlanException e) { problem = e.getMessage(); }
        }
        if (problem == null) {
            playerSession(request.actor).retainedPlan = null;
            return true;
        }
        endRequest(request, false);
        Alternatives batch = playerSession(request.actor).alternatives;
        if (batch != null && batch.collecting && !batch.choices.isEmpty()) {
            send(request.source, Chat.error(problem + " This option was not kept; choose a completed option or start again" + again + "."));
            return false;
        }
        playerSession(request.actor).retainedPlan = new RetainedPlan(request, plan,
                request.target == null ? null : request.target.current(), Instant.MAX);
        if (!playerSession(request.actor).resumingPreview) {
            var choice = new PreviewStore.Choice(plan, describe(request), request.status == null ? request.typedRequest
                    : request.conversation.request(), request.level.dimension(), request.origin, request.facing,
                    request.target == null ? null : request.target.id(), request.target == null ? null : request.target.current().id(),
                    request.zone, request.focus, request.guards, request.constraints, request.projectId, null);
            savePreview(request.actor, request.source, new PreviewStore.Saved(List.of(choice), 0));
        }
        send(request.source, Chat.with(Chat.with(Chat.error(problem + " Plan kept. " + then
                + " retry it (no new AI call)."), "Retry", "/justbuild retry", "Preview the kept plan again"),
                "Discard", "/justbuild cancel", "Forget the kept plan"));
        sendStateTo(request.source);
        return false;
    }

    /** Area problems leave a revised preview as it was. Nothing is retained, since the zone is fixed while a preview waits. */
    private static void checkRevisionArea(ActiveRequest request, Iterable<BlockPos> changes) throws PlanException {
        ensureLoaded(request.level, changes);
        if (request.zone != null) request.zone.check(request.level.dimension(), changes);
    }

    /** Where the actor is now; a request's saved source still names the dimension it started in. */
    private ResourceKey<Level> dimensionOf(String actor, CommandSourceStack fallback) {
        ServerPlayer player = actor.equals(CONSOLE) ? null : server.getPlayerList().getPlayer(UUID.fromString(actor));
        return player != null ? player.level().dimension() : fallback.getLevel().dimension();
    }

    public void retry(CommandSourceStack source) {
        if (!canPlace(source) || !canChangeZone(source)) return;
        String actor = ownerOf(source);
        RetainedPlan retained = playerSession(actor).retainedPlan;
        if (retained == null && (playerSession(actor).savedPreview != null || playerSession(actor).previewLoadProblem != null)) {
            resumePreview(source);
            return;
        }
        if (retained == null || Instant.now().isAfter(retained.expires())) {
            playerSession(actor).retainedPlan = null;
            send(source, Chat.error("No retained plan is available. Start a new request."));
            sendStateTo(source);
            return;
        }
        ActiveRequest request = retained.request();
        if (!request.level.dimension().equals(source.getLevel().dimension())) {
            send(source, Chat.error("Return to " + request.level.dimension().identifier() + " before retrying the plan."));
            return;
        }
        if (request.target != null) {
            if (!mayModify(source, request.target)) return;
            if (registry.get(request.target.id()).orElse(null) != request.target || request.target.current() != retained.original()) {
                playerSession(actor).retainedPlan = null;
                send(source, Chat.error("The original build changed. Its retained plan was discarded; start a new request."));
                sendStateTo(source);
                return;
            }
            if (busy.contains(request.target.id())) {
                send(source, Chat.error("The original build is busy. Wait for it to finish before retrying."));
                return;
            }
        }
        try { request.zone = zoneFor(actor, source.getLevel().dimension()); }
        catch (PlanException e) { send(source, Chat.error(e.getMessage())); return; }
        playerSession(actor).retainedPlan = null;
        request.source = source;
        request.handedOff = false;
        request.forcePreview = true;
        request.savedPlan = true;
        if (request.target == null) playerSession(actor).selectedBuild = null;
        else playerSession(actor).selectedBuild = request.target.id();
        playerSession(actor).planning.start(request);
        if (request.target != null) { busy.add(request.target.id()); placeEdit(request, retained.plan()); }
        else placeNew(request, retained.plan());
        sendStateTo(source);
    }

    /** Snapshot only completed work. In-flight AI calls and connection consent never resume. */
    private void savePreview(String actor, CommandSourceStack source) {
        PlayerSession session = playerSession(actor);
        if (session.resumingPreview) return;
        Alternatives options = session.alternatives;
        List<PendingPlacement> previews = options != null && !options.choices.isEmpty() ? options.choices
                : session.preview.current == null ? List.of() : List.of(session.preview.current);
        if (previews.isEmpty()) return;
        try {
            List<PreviewStore.Choice> choices = new java.util.ArrayList<>();
            for (PendingPlacement p : previews) {
                BuildRecord r = p.record();
                choices.add(new PreviewStore.Choice(p.sourcePlan(), p.isNew() ? r.current().request() : p.version().request(),
                        r.typedRequest(), r.dimension(), r.origin(), r.facing(), p.isNew() ? null : r.id(),
                        p.isNew() ? null : r.current().id(), p.zone(), p.focus(), p.guards(), r.constraints(), p.projectId(),
                        p.restoreLive() ? PlanSnapshot.capture(p.title(), r.origin(), r.facing(), p.placements()) : null));
            }
            PreviewStore.Saved saved = new PreviewStore.Saved(choices, options == null ? 0 : options.selected);
            savePreview(actor, source, saved);
        } catch (PlanException | IllegalArgumentException e) {
            LOGGER.warn("Could not save preview for {}: {}", actor, e.toString());
            send(source, Chat.error("This preview could not be saved for reconnect: " + e.getMessage()));
        }
    }

    private void savePreview(String actor, CommandSourceStack source, PreviewStore.Saved saved) {
        try {
            previewStore.save(actor, saved);
            PlayerSession session = playerSession(actor); session.savedPreview = saved; session.previewLoadProblem = null;
        } catch (IOException e) {
            LOGGER.warn("Could not save preview for {}: {}", actor, e.toString());
            send(source, Chat.error("This plan could not be saved for reconnect: " + e.getMessage()));
        }
    }

    private boolean clearSavedPreview(CommandSourceStack source) {
        PlayerSession session = playerSession(ownerOf(source));
        if (session.savedPreview == null && session.previewLoadProblem == null) return true;
        try {
            previewStore.delete(ownerOf(source)); session.savedPreview = null; session.previewLoadProblem = null;
            return true;
        } catch (IOException e) {
            send(source, Chat.error("The saved preview could not be removed. Try again after checking the world's justbuild/previews folder."));
            return false;
        }
    }

    private static String retainedTitle(PlayerSession session) {
        if (session.retainedPlan != null) return session.retainedPlan.plan().title();
        if (session.preview.current != null || session.planning.current != null) return null;
        return session.savedPreview != null ? session.savedPreview.title()
                : session.previewLoadProblem != null ? "Unreadable saved preview" : null;
    }

    private void resumePreview(CommandSourceStack source) {
        String actor = ownerOf(source); PlayerSession session = playerSession(actor);
        PreviewStore.Saved saved = session.savedPreview;
        if (saved == null) {
            send(source, Chat.error("The saved preview could not be read. Its file was kept in justbuild/previews; repair it or use Cancel to remove it."));
            return;
        }
        // Validate every alternative before rebuilding any. A changed source cannot accept an old edit.
        for (var choice : saved.choices()) {
            if (!choice.dimension().equals(source.getLevel().dimension())) {
                send(source, Chat.error("Return to " + choice.dimension().identifier() + " to resume this preview.")); return;
            }
            if (choice.target() != null) {
                BuildRecord record = registry.get(choice.target()).orElse(null);
                if (record == null || !record.current().id().equals(choice.version())) {
                    send(source, Chat.error("The original build changed. Cancel this saved preview and start a new request.")); return;
                }
                if (!mayModify(source, record)) return;
                if (busy.contains(record.id())) { send(source, Chat.error("The original build is busy. Wait before resuming.")); return; }
            }
        }
        Alternatives batch = new Alternatives("Saved alternatives", saved.choices().size());
        session.alternatives = batch; session.resumingPreview = true;
        boolean ready = false;
        try {
            for (var choice : saved.choices()) {
                BuildRecord target = choice.target() == null ? null : registry.get(choice.target()).orElseThrow();
                ActiveRequest request = new ActiveRequest(actor, new Conversation(choice.request()), source.getLevel(),
                        choice.origin(), choice.facing(), target, PromptSpec.Placement.FRONT, false, null, source);
                request.zone = session.zone != null && !session.zone.fromBuild() ? zoneFor(actor, choice.dimension()) : choice.zone();
                request.focus = choice.focus(); request.guards = choice.guards();
                request.constraints = target == null ? choice.constraints() : target.constraints();
                request.projectId = choice.project(); request.projectCaptured = true; request.typedRequest = choice.typedRequest();
                request.savedPlan = true; request.forcePreview = true; request.restoreLive = choice.localChanges() != null;
                config.limits().check(choice.plan());
                var rendered = PlanPipeline.run(choice.localChanges() == null ? choice.plan() : choice.localChanges(),
                        choice.origin(), choice.facing(), source.getLevel(), p -> readLoaded(source.getLevel(), p), config.maxBlocks);
                ensureLoaded(source.getLevel(), rendered.placements().keySet());
                if (target != null) busy.add(target.id());
                int before = batch.choices.size();
                if (request.restoreLive) previewLocalChange(request, rendered.placements(), null, Map.of());
                else if (target == null) placeNew(request, choice.plan());
                else placeEdit(request, choice.plan());
                if (batch.choices.size() != before + 1) return;
            }
            batch.collecting = false; batch.selected = saved.selected();
            session.retainedPlan = null;
            PendingPlacement selected = batch.choices.get(batch.selected);
            session.selectedBuild = selected.isNew() ? null : selected.record().id();
            session.zone = selected.zone() == null ? null : new ZoneSelection(selected.zone().dimension(), selected.record().origin(), selected.zone());
            offer(selected, true);
            ready = session.preview.current == selected;
        } catch (PlanException | IllegalArgumentException e) {
            send(source, Chat.error("Saved preview kept: " + e.getMessage()));
        } finally {
            session.resumingPreview = false;
            if (!ready) {
                session.alternatives = null; session.retainedPlan = null;
                for (var choice : saved.choices()) if (choice.target() != null) busy.remove(choice.target());
            } else {
                savePreview(actor, source);
                send(source, Chat.info("Resumed " + count(saved.choices().size(), "saved option")
                        + " with fresh world checks. Review the preview, then Instant Build. No AI call was made."));
            }
            sendStateTo(source);
        }
    }

    private static PendingPlacement pending(ActiveRequest request, BuildRecord record, boolean isNew,
            BuildRecord.Version version, Map<BlockPos, BlockState> placements, Map<BlockPos, BlockState> ours,
            BuildPlan plan) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos pos : placements.keySet()) {
            minX = Math.min(minX, pos.getX());
            minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX());
            maxY = Math.max(maxY, pos.getY());
            maxZ = Math.max(maxZ, pos.getZ());
        }
        return new PendingPlacement(request.actor, request.source, request.level, record, isNew, version, placements,
                ours, record.title(), plan.summary(), request.savedPlan ? "Saved plan ready (no AI call). "
                        : planReady(request, plan), aiSummary(request),
                request.status == null ? Instant.now() : request.status.started,
                new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ), request.zone,
                request.focus, List.copyOf(request.guards), BuildQuality.inspect(placements, p -> readLoaded(request.level, p)), plan, request.restoreLive, request.projectId, PreviewRevisions.NONE);
    }

    /** Places the plan now, or holds it as a preview with an outline when it is larger than previewAbove. */
    private void offer(PendingPlacement p, boolean forcePreview) {
        offer(p, forcePreview, false);
    }

    /**
     * A fresh AI result, for a player who turned on Auto-build, outside alternatives (those wait for a choice). Saved
     * plans, design copies, and restores still preview. A revision always previews.
     */
    private boolean autoBuilds(ActiveRequest request) {
        return prefs.autoBuild(request.actor) && request.status != null && !request.savedPlan && request.revising == null
                && playerSession(request.actor).alternatives == null;
    }

    private void offer(PendingPlacement p, boolean forcePreview, boolean autoBuild) {
        if (construction.active(p.source().getPlayer())) {
            discard(p); keepDiscarded(p);
            send(p.source(), Chat.error("Plan kept. Close your survival construction project before opening another preview.")); return;
        }
        Alternatives batch = playerSession(p.actor()).alternatives;
        if (batch != null && batch.collecting) {
            batch.choices.add(p);
            savePreview(p.actor(), p.source());
            return;
        }
        try {
            ensureLoaded(p.level(), p.placements().keySet());
        } catch (PlanException e) {
            playerSession(p.actor()).alternatives = null;
            discard(p);
            keepDiscarded(p);
            send(p.source(), Chat.error("Plan kept without loading distant chunks. Move closer to the build in "
                    + p.level().dimension().identifier() + " and use Retry saved plan."));
            return;
        }
        if (autoBuild) {
            for (String warning : p.quality()) send(p.source(), Chat.info("Check: " + warning));
            commit(p, false);
            return;
        }
        int threshold = config.previewAbove;
        if (!forcePreview && (threshold < 0 || p.placements().size() <= threshold && p.quality().isEmpty())) {
            commit(p, false);
            return;
        }
        PendingPlacement replaced = playerSession(p.actor()).preview.replace(p);
        if (replaced != null) {
            discard(replaced);
        }
        savePreview(p.actor(), p.source());
        int width = p.max().getX() - p.min().getX() + 1;
        int height = p.max().getY() - p.min().getY() + 1;
        int depth = p.max().getZ() - p.min().getZ() + 1;
        String what = p.isNew() ? "“" + p.title() + "” would place " + count(p.placements().size(), "block")
                : "This change to “" + p.title() + "” would replace " + count(p.placements().size(), "block");
        send(p.source(), Chat.preview(p.ready() + what + " in a space " + width + " wide, " + height + " tall, and "
                + depth + " deep. Ghost blocks show additions; gold marks replacements, red crosses mark removals. Instant Build?"));
        for (String warning : p.quality()) send(p.source(), Chat.info("Check: " + warning));
        drawOutline(p);
        showPreview(p, true);
        ServerPlayer player = p.source().getPlayer();
        boolean large = player != null && ServerPlayNetworking.canSend(player, dev.justbuild.net.LargeGhostPreviewPayload.TYPE);
        if (player != null && (large || ServerPlayNetworking.canSend(player, GhostPreviewPayload.TYPE))) {
            // An older game refuses more than GhostPreviewPayload.MAX_CELLS cells, which would disconnect the player.
            int cells = large ? Math.max(config.maxBlocks, GhostPreviewPayload.MAX_CELLS) : GhostPreviewPayload.MAX_CELLS;
            Protection.Result visible = Protection.filter(p.placements(), owned(p.record(), p.ours()), p.level()::getBlockEntity,
                    p.level()::getBlockState);
            GhostPreviewPayload ghosts = ChangePreview.create(p.level().dimension().identifier().toString(),
                    visible.placements(), p.level()::getBlockState, cells);
            ServerPlayNetworking.send(player, large ? new dev.justbuild.net.LargeGhostPreviewPayload(ghosts) : ghosts);
            boolean largeSection = large && ServerPlayNetworking.canSend(player, dev.justbuild.net.LargeSectionPreviewPayload.TYPE);
            if (largeSection || ServerPlayNetworking.canSend(player, dev.justbuild.net.SectionPreviewPayload.TYPE)) {
                Map<BlockPos, BlockState> model = new LinkedHashMap<>();
                if (!p.isNew()) registry.current(p.record()).keySet().forEach(pos -> model.put(pos, readLoaded(p.level(), pos)));
                visible.placements().keySet().forEach(pos -> model.put(pos, readLoaded(p.level(), pos)));
                var before = ChangePreview.create(p.level().dimension().identifier().toString(), model,
                        pos -> net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), cells);
                model.putAll(visible.placements());
                var section = new dev.justbuild.net.SectionPreviewPayload(ChangePreview.create(
                        p.level().dimension().identifier().toString(), model, pos -> net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), cells), before);
                ServerPlayNetworking.send(player, largeSection ? new dev.justbuild.net.LargeSectionPreviewPayload(section) : section);
            }
            if (ghosts.total() > ghosts.cells().size()) send(p.source(), Chat.info("Showing " + ghosts.cells().size()
                    + " of " + ghosts.total() + " changes in the detailed preview; the outer box covers the full plan."));
        }
        sendStateTo(p.source());
    }

    /** Re-run the original plan at its new frame; failed adjustments leave the previous preview intact. */
    public void adjustPreview(CommandSourceStack source, int dx, int dy, int dz, int rotation, boolean snap) {
        rebuildPreview(source, dx, dy, dz, rotation, snap, null);
    }

    private void rebuildPreview(CommandSourceStack source, int dx, int dy, int dz, int rotation, boolean snap, BuildPlan texturePlan) {
        if (!canPlace(source)) return;
        String actor = ownerOf(source);
        PendingPlacement old = playerSession(actor).preview.current;
        if (old == null) { send(source, Chat.error("There is no waiting preview to move.")); return; }
        if (!old.isNew()) { send(source, Chat.error("Edits stay anchored to their building. Save a design to move or rotate a copy.")); return; }
        if (!old.level().dimension().equals(source.getLevel().dimension())) {
            send(source, Chat.error("Return to the preview's dimension first.")); return;
        }
        if (Math.abs(dx) > 16 || Math.abs(dy) > 16 || Math.abs(dz) > 16 || rotation % 90 != 0 || rotation < 0 || rotation > 270) {
            send(source, Chat.error("Move at most 16 blocks per axis, or rotate by 0, 90, 180, or 270 degrees.")); return;
        }
        BuildPlan sourcePlan = texturePlan == null ? old.sourcePlan() : texturePlan;
        try {
            BlockPos origin = old.record().origin().offset(dx, dy, dz);
            if (Math.abs((long) origin.getX()) > 29_999_000 || Math.abs((long) origin.getZ()) > 29_999_000) {
                throw new PlanException("That preview is outside the supported world coordinates.");
            }
            if (!old.level().hasChunk(origin.getX() >> 4, origin.getZ() >> 4)) throw new PlanException("Move closer to load that position.");
            if (snap) origin = new BlockPos(origin.getX(), Ground.standingY(old.level(), origin.getX(), origin.getZ(), origin.getY()),
                    origin.getZ());
            Direction facing = Direction.fromYRot(old.record().facing().toYRot() + rotation);
            config.limits().check(sourcePlan);
            var result = PlanPipeline.run(sourcePlan, origin, facing, old.level(), p -> readLoaded(old.level(), p), config.maxBlocks);
            if (result.outsideWorld() != 0) throw new PlanException("That move would put blocks outside the world's build height.");
            ensureLoaded(old.level(), result.placements().keySet());
            Map<BlockPos, BlockState> placements = new LinkedHashMap<>(result.placements());
            placements.keySet().removeIf(p -> !mayChange(old, p));
            BuildZone area = old.zone() != null ? old.zone() : footprintOf(old.level().dimension(), origin, placements.keySet());
            reportKept(source, keepOthers(placements, othersCells(source, old.level().dimension(), area, null)));
            placements = safe(source, old.level(), placements, area, p -> mayChange(old, p));
            if (placements.isEmpty()) throw new PlanException("No blocks remain outside the protected areas.");
            if (old.zone() != null) old.zone().check(old.level().dimension(), placements.keySet());
            BuildPlan finalPlan = placements.equals(result.placements()) ? sourcePlan
                    : PlanSnapshot.capture(old.title(), origin, facing, placements);
            config.limits().check(finalPlan);
            BuildRecord record = new BuildRecord(old.record().id(), actor, old.level().dimension(), origin, facing,
                    List.of(new BuildRecord.Version(old.record().current().request(), finalPlan, System.currentTimeMillis())), Map.of());
            record.zone(old.zone() == null ? footprint(record, placements.keySet()) : old.zone());
            record.constraints(old.record().constraints());
            record.typedRequest(old.record().typedRequest());
            record.constraints().check(origin, placements, Map.of());
            BuildRegion box = BuildRegion.enclosing(old.level().dimension(), placements.keySet());
            PendingPlacement moved = new PendingPlacement(actor, source, old.level(), record, true, null, placements,
                    Map.of(), old.title(), old.summary(), "Adjusted preview (no AI call). ", old.aiSummary(), old.requestStarted(),
                    box.min(), box.max(), old.zone(), old.focus(), old.guards(),
                    BuildQuality.inspect(placements, p -> readLoaded(old.level(), p)), sourcePlan, old.restoreLive(), old.projectId(), old.revisions());
            playerSession(actor).preview.take();
            showPreview(old, false);
            Alternatives batch = playerSession(actor).alternatives;
            if (batch != null && !batch.collecting) batch.choices.set(batch.selected, moved);
            offer(moved, true);
        } catch (PlanException | IllegalArgumentException e) { send(source, Chat.error("Preview unchanged: " + e.getMessage())); }
    }

    /** Tells the requesting player's game to draw (or remove) the preview box, if it has JustBuild. */
    private static void showPreview(PendingPlacement p, boolean show) {
        ServerPlayer player = p.source().getPlayer();
        if (player == null || player.hasDisconnected() || !ServerPlayNetworking.canSend(player, PreviewPayload.TYPE)) {
            return;
        }
        ServerPlayNetworking.send(player, show ? new PreviewPayload(true, p.level().dimension().identifier().toString(),
                p.min(), p.max(), p.title()) : PreviewPayload.hidden());
    }

    public dev.justbuild.construction.ConstructionManager construction() { return construction; }

    public void constructionCommand(CommandSourceStack source, String action) {
        ServerPlayer player = source.getPlayer();
        if (player == null) { send(source, Chat.error("Construction needs a player in the world.")); return; }
        if (!action.startsWith("start ")) { construction.command(player, action.trim()); return; }
        if (!canPlace(source)) return;
        PendingPlacement p = playerSession(ownerOf(source)).preview.current;
        if (p == null || !p.isNew()) { send(source, Chat.error("Survival construction starts from a new building preview.")); return; }
        if (!p.level().dimension().equals(player.level().dimension())) { send(source, Chat.error("Return to the preview's dimension.")); return; }
        try {
            var mode = dev.justbuild.construction.ConstructionProject.Mode.valueOf(action.substring(6).trim().toUpperCase(java.util.Locale.ROOT));
            config.limits().check(p.sourcePlan());
            if (p.placements().size() > config.maxBlocks) throw new PlanException("This blueprint exceeds the current block limit.");
            ensureLoaded(p.level(), p.placements().keySet());
            if (p.zone() != null) p.zone().check(p.level().dimension(), p.placements().keySet());
            p.record().constraints().check(p.record().origin(), p.placements(), p.ours());
            Set<BlockPos> others = othersCells(source, p.level().dimension(), p.zone(), p.record().id());
            if (p.placements().keySet().stream().anyMatch(pos -> !mayChange(p, pos) || others.contains(pos)))
                throw new PlanException("The blueprint now overlaps a protected area. Adjust the preview first.");
            var project = new dev.justbuild.construction.ConstructionProject(UUID.randomUUID(), player.getUUID(), p.title(),
                    p.level().dimension(), p.placements(), mode);
            construction.start(player, project);
            if (!clearSavedPreview(source)) {
                construction.close(player); return;
            }
            // Ending the project soon after can still bring the preview back, as after Discard.
            keepDiscarded(p);
            playerSession(p.actor()).preview.take(); playerSession(p.actor()).alternatives = null;
            showPreview(p, false); busy.remove(p.record().id());
            project.paused = false;
            send(source, Chat.info("Survival project pinned. Press Right Shift to change modes, link supplies, or pause. Clear obstructions by hand."));
            sendState(player); construction.syncNow(player);
        } catch (IOException | PlanException | IllegalArgumentException e) {
            send(source, Chat.error("Preview kept: " + e.getMessage()));
        }
    }

    public void confirm(CommandSourceStack source) {
        if (construction.active(source.getPlayer())) {
            send(source, Chat.error("Close your survival construction project before placing another preview.")); return;
        }
        PendingPlacement p = playerSession(ownerOf(source)).preview.current;
        if (p == null) {
            send(source, Chat.error("No build preview is waiting. To confirm an undo, use /justbuild undo confirm."));
            return;
        }
        if (!p.level().dimension().equals(source.getLevel().dimension())) {
            send(source, Chat.error("Return to the preview's dimension before confirming.")); return;
        }
        if (!canPlace(source)) return;
        // Recheck current limits and loaded area before consuming an arbitrarily old preview.
        // commit also checks live block entities and locked regions before scheduling placement.
        try {
            config.limits().check(p.sourcePlan());
            if (p.placements().size() > config.maxBlocks) {
                throw new PlanException("This preview changes " + p.placements().size()
                        + " blocks; the current limit is " + config.maxBlocks + ".");
            }
            p.record().constraints().check(p.record().origin(), p.placements(), p.ours());
            ensureLoaded(p.level(), p.placements().keySet());
            if (p.zone() != null) p.zone().check(p.level().dimension(), p.placements().keySet());
        } catch (PlanException e) {
            send(source, Chat.error("Preview kept: " + e.getMessage() + " Confirm again when ready, or cancel."));
            return;
        }
        if (!clearSavedPreview(source)) return;
        playerSession(ownerOf(source)).preview.take();
        playerSession(ownerOf(source)).alternatives = null;
        commit(p.withSource(source), true);
        sendStateTo(source);
    }

    private void commit(PendingPlacement p, boolean afterPreview) {
        if (!clearSavedPreview(p.source())) return;
        playerSession(p.actor()).qualityInfo = null; playerSession(p.actor()).qualityUndo = null;
        if (!canPlace(p.source())) { discard(p); return; }
        if (!p.isNew() && (registry.get(p.record().id()).orElse(null) != p.record() || !mayModify(p.source(), p.record()))) {
            discard(p);
            return;
        }
        try {
            p.record().constraints().check(p.record().origin(), p.placements(), p.ours());
            if (p.zone() != null) {
                p.zone().check(p.level().dimension(), p.placements().keySet());
            }
        } catch (PlanException e) {
            discard(p);
            send(p.source(), Chat.error(e.getMessage()));
            return;
        }
        if (afterPreview) {
            showPreview(p, false);
        }
        Protection.Result kept = Protection.filter(p.placements(), owned(p.record(), p.ours()), p.level()::getBlockEntity,
                p.level()::getBlockState);
        Map<BlockPos, BlockState> guarded = new LinkedHashMap<>(kept.placements());
        guarded.keySet().removeIf(pos -> !mayChange(p, pos));
        // Membership and other builds can change while a preview waits.
        BuildZone area = footprintOf(p.level().dimension(), p.record().origin(), guarded.keySet());
        reportKept(p.source(), keepOthers(guarded, othersCells(p.source(), p.level().dimension(), area,
                p.isNew() ? null : p.record().id())));
        // Preview-time support and containment can change before confirmation.
        BuildZone safetyArea = safetyArea(p.record(), p.zone(), union(p.ours().keySet(), p.placements().keySet()));
        if (!safe(p.source(), p.level(), guarded, safetyArea,
                pos -> mayChange(p, pos)).equals(guarded)) {
            discard(p);
            keepDiscarded(p);
            send(p.source(), Chat.error("The world changed and this preview is no longer safe. Nothing was placed. "
                    + "Use Retry saved plan to prepare a new preview."));
            return;
        }
        if (guarded.isEmpty()) {
            discard(p);
            send(p.source(), Chat.info("No changes remain outside protected areas. Nothing was placed."));
            return;
        }
        BuildRecord record = p.record();
        record.zone(p.zone() != null ? p.zone() : record.zone());
        playerSession(p.actor()).selectedBuild = record.id();
        if (p.isNew()) {
            // Placed blocks without a saved record could never be undone, so a build that can't be saved isn't placed.
            if (!saveRecordNow(record)) {
                discard(p);
                send(p.source(), Chat.error("Nothing was placed: “" + record.title() + "” couldn't be saved to the world's "
                        + "justbuild folder (is the disk full or read-only?)."));
                return;
            }
            registry.add(record);
            joinProject(p.source(), record, p.projectId());
            history.push(p.actor(), entry(record));
            saveHistory();
        }
        busy.add(record.id());
        List<Placement> ordered = PlacementOrder.order(guarded);
        var buildAccess = liveBuildAccess(p.source(), record, guarded.keySet());
        PlacementJob job = new PlacementJob(p.actor(), p.isNew() ? PlacementJob.Kind.BUILD : PlacementJob.Kind.EDIT,
                p.title(), BlockAccess.of(p.level(), false, p.zone(), pos -> mayChange(p, pos) && buildAccess.test(pos),
                        owned(p.record(), p.ours())),
                ordered);
        jobs.put(job, new JobInfo(p.source(), record, p.version(), p.isNew() ? null : p.ours(), p.requestStarted(),
                p.aiSummary(), p.restoreLive(), new JobProgress()));
        scheduler.enqueue(job);
        String ready = afterPreview ? "" : p.ready();
        String summary = p.summary().isBlank() ? "" : " " + p.summary();
        int from = record.current().number();
        send(p.source(), Chat.info(ready + (p.isNew()
                ? "Building “" + p.title() + "”, " + ordered.size() + " blocks."
                : "Updating “" + p.title() + "” (version " + from + " → " + (from + 1) + "), " + ordered.size()
                        + " blocks change.") + summary));
        if (kept.kept() > 0) {
            send(p.source(), Chat.info("Left " + kept.kept() + " chests, signs, or other blocks holding your things "
                    + "untouched."));
        }
    }

    /** A preview the player discarded stays retrievable for 30 minutes (Retry saved plan), without another AI call. */
    private void keepDiscarded(PendingPlacement p) {
        BuildRecord target = p.isNew() ? null : p.record();
        ActiveRequest request = new ActiveRequest(p.actor(), new Conversation(p.title()), p.level(), p.record().origin(),
                p.record().facing(), target, PromptSpec.Placement.FRONT, false, null, p.source());
        request.zone = p.zone();
        request.focus = p.focus();
        request.guards = p.guards();
        request.projectId = p.projectId();
        request.projectCaptured = true;
        request.restoreLive = p.restoreLive();
        playerSession(p.actor()).retainedPlan = new RetainedPlan(request, p.sourcePlan(), target == null ? null : target.current(),
                Instant.now().plus(Duration.ofMinutes(30)));
    }

    private void discard(PendingPlacement p) {
        forgetQuality(p);
        showPreview(p, false);
        if (!p.isNew()) {
            busy.remove(p.record().id());
        }
    }

    /** Outlines a pending plan's bounding box with particles visible from far away. */
    private static void drawOutline(PendingPlacement p) {
        double x0 = p.min().getX();
        double y0 = p.min().getY();
        double z0 = p.min().getZ();
        double x1 = p.max().getX() + 1;
        double y1 = p.max().getY() + 1;
        double z1 = p.max().getZ() + 1;
        double[][] corners = {{x0, z0}, {x1, z0}, {x1, z1}, {x0, z1}};
        for (int i = 0; i < 4; i++) {
            double[] a = corners[i];
            double[] b = corners[(i + 1) % 4];
            particleLine(p.level(), a[0], y0, a[1], b[0], y0, b[1]);
            particleLine(p.level(), a[0], y1, a[1], b[0], y1, b[1]);
            particleLine(p.level(), a[0], y0, a[1], a[0], y1, a[1]);
        }
    }

    private static void particleLine(ServerLevel level, double ax, double ay, double az, double bx, double by,
            double bz) {
        double length = Math.max(Math.abs(bx - ax), Math.max(Math.abs(by - ay), Math.abs(bz - az)));
        int steps = Math.max(1, (int) Math.ceil(length));
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            level.sendParticles(ParticleTypes.END_ROD, false, true, ax + (bx - ax) * t, ay + (by - ay) * t,
                    az + (bz - az) * t, 1, 0, 0, 0, 0);
        }
    }

    private static void reportSkipped(CommandSourceStack source, PlanPipeline.Result result) {
        if (!result.invalid().isEmpty()) {
            send(source, Chat.error("Skipped unknown blocks: " + String.join(", ", result.invalid())));
        }
        if (result.outsideWorld() > 0) {
            send(source, Chat.error("Skipped " + result.outsideWorld() + " blocks outside the world's build height."));
        }
    }

    private Optional<BuildRecord> findTarget(CommandSourceStack source) {
        return selected(source);
    }

    // ---- undo / cancel ----

    /** Shows what an undo would do and asks for confirmation (or runs it directly when confirmUndo is off). */
    public void undo(CommandSourceStack source) {
        if (!canPlace(source)) return;
        String actor = ownerOf(source);
        if (scheduler.busy(actor)) {
            send(source, Chat.with(Chat.error("A build is still being placed."), "Cancel", "/justbuild cancel", "Stop placing it"));
            return;
        }
        Optional<BuildRecord> target = nextUndoTarget(actor, source);
        if (target.isEmpty()) {
            send(source, Chat.error("Nothing to undo."));
            return;
        }
        BuildRecord record = target.get();
        if (!canUndoHere(source, record)) return;
        if (busy.contains(record.id())) {
            send(source, Chat.error(busyMessage(source, record)));
            return;
        }
        UndoPreview preview = preview(source, record);
        try {
            BuildZone zone = zoneFor(actor, record.dimension());
            if (zone != null) {
                zone.check(record.dimension(), preview.diff().keySet());
            }
        } catch (PlanException e) {
            send(source, Chat.error(e.getMessage()));
            return;
        }
        if (!config.confirmUndo) {
            runUndo(source, actor, preview);
            return;
        }
        playerSession(actor).pendingUndo = new PendingUndo(record.id(), record.current().id(),
                Instant.now().plus(UNDO_CONFIRM_TTL));
        String what = (record.current().number() > 1
                ? "Undo takes “" + record.title() + "” back to version " + (record.current().number() - 1) + ": "
                        + preview.diff().size() + " blocks change back."
                : "Undo removes “" + record.title() + "”: " + preview.diff().size()
                        + " blocks go back to what was there before it was built.")
                + (preview.kept() > 0 ? " " + preview.kept() + " chests, signs, or other blocks holding your things "
                        + "stay as they are." : "");
        send(source, Chat.undoPrompt(what, preview.changedSince()));
        sendStateTo(source);
    }

    public void confirmUndo(CommandSourceStack source) {
        String actor = ownerOf(source);
        PendingUndo pending = playerSession(actor).pendingUndo;
        playerSession(actor).pendingUndo = null;
        if (pending == null) {
            send(source, Chat.error("Nothing to confirm: no undo is waiting."));
            return;
        }
        Optional<BuildRecord> target = nextUndoTarget(actor, source);
        boolean stale = Instant.now().isAfter(pending.expires()) || target.isEmpty()
                || !target.get().id().equals(pending.buildId())
                || !target.get().current().id().equals(pending.versionId());
        if (stale) {
            send(source, Chat.with(Chat.error("That undo is out of date."), "Undo", "/justbuild undo", "Show what undo would do now"));
            return;
        }
        if (scheduler.busy(actor) || busy.contains(pending.buildId())) {
            send(source, Chat.error(busyMessage(source, target.get())));
            return;
        }
        if (!canUndoHere(source, target.get())) return;
        runUndo(source, actor, preview(source, target.get()));
    }

    /**
     * Like building and editing, undo starts in the build's dimension once all of it is loaded, so its check doesn't
     * load chunks and its zone check never blames a zone the player drew where they are. If the player then leaves,
     * the undo job waits for the chunks to load again (PlacementJob) instead of loading them.
     */
    private boolean canUndoHere(CommandSourceStack source, BuildRecord record) {
        if (!mayModify(source, record)) return false;
        String title = "“" + record.title() + "”";
        if (server.getLevel(record.dimension()) == null) {
            send(source, Chat.error(title + " is in a dimension that isn't loaded (" + record.dimension().identifier()
                    + "), so it can't be undone right now."));
            return false;
        }
        if (!record.dimension().equals(source.getLevel().dimension())) {
            send(source, Chat.error("Your latest change is to " + title + " in " + record.dimension().identifier()
                    + ". Travel there to undo it."));
            return false;
        }
        try {
            ensureLoaded(source.getLevel(), record.baseline().keySet());
        } catch (PlanException e) {
            send(source, Chat.error(source.getPlayer() == null
                    ? title + " isn't loaded. Load its area (for example with /forceload), then undo."
                    : "Move closer to " + title + " so all of it is loaded, then undo."));
            return false;
        }
        return true;
    }

    public void cancelUndo(CommandSourceStack source) {
        if (playerSession(ownerOf(source)).pendingUndo != null) {
            playerSession(ownerOf(source)).pendingUndo = null;
            send(source, Chat.info("Kept it. Nothing was undone."));
        } else {
            send(source, Chat.error("No undo is waiting for confirmation."));
        }
        sendStateTo(source);
    }

    /** A history entry names the exact version this action produced, including after trimming or undo/edit. */
    private static ActionHistory.Entry entry(BuildRecord record) {
        return ActionHistory.Entry.of(record);
    }

    /**
     * The newest history entry this actor can still undo. Entries for deleted builds are dropped, and so are entries
     * for builds someone changed again afterwards (undo only steps back over the latest change to a build).
     */
    private Optional<BuildRecord> nextUndoTarget(String actor, CommandSourceStack source) {
        for (Optional<ActionHistory.Entry> top = history.peek(actor); top.isPresent(); top = history.peek(actor)) {
            Optional<BuildRecord> record = registry.get(top.get().buildId());
            boolean stale = record.isPresent() && !top.get().matches(record.get());
            if (record.isPresent() && !stale) {
                return record;
            }
            history.pop(actor);
            saveHistory();
            if (stale) {
                send(source, Chat.info("Skipped your earlier change to “" + record.get().title() + "”: it was changed "
                        + "again after that, so undo can't step back over it."));
            }
        }
        return Optional.empty();
    }

    /** What undoing the build's last version would place, and how many of those spots no longer match the build. */
    private UndoPreview preview(CommandSourceStack source, BuildRecord record) {
        int count = record.versions().size();
        Map<BlockPos, BlockState> from = registry.placements(record, count);
        ServerLevel level = level(record.dimension());
        Protection.Result kept = Protection.filter(
                record.current().undoStates().isEmpty()
                        ? Diff.forUndo(from, registry.placements(record, count - 1), record.baseline()) : record.current().undoStates(), owned(record, from),
                level::getBlockEntity, level::getBlockState);
        Map<BlockPos, BlockState> diff = new LinkedHashMap<>(kept.placements());
        diff.keySet().removeIf(pos -> locked(record.dimension(), pos));
        reportKept(source, keepOthers(diff, othersCells(source, record.dimension(), footprint(record, diff.keySet()), record.id())));
        int changedSince = 0;
        for (BlockPos pos : diff.keySet()) {
            BlockState expected = from.containsKey(pos) ? from.get(pos) : record.baseline().get(pos);
            if (expected == null) {
                continue;
            }
            // The refresh pass connects fences, panes, and stairs, so compare against the connected form too.
            BlockState live = level.getBlockState(pos);
            if (live != expected && live != Block.updateFromNeighbourShapes(expected, level, pos)) {
                changedSince++;
            }
        }
        return new UndoPreview(record, count, diff, changedSince, kept.kept());
    }

    private void runUndo(CommandSourceStack source, String actor, UndoPreview preview) {
        if (!canPlace(source)) return;
        BuildRecord record = preview.record();
        if (!mayModify(source, record)) return;
        BuildZone zone;
        try {
            zone = zoneFor(actor, record.dimension());
            if (zone != null) {
                zone.check(record.dimension(), preview.diff().keySet());
            }
        } catch (PlanException e) {
            send(source, Chat.error(e.getMessage()));
            return;
        }
        busy.add(record.id());
        history.pop(actor);
        saveHistory();
        var buildAccess = liveBuildAccess(source, record, preview.diff().keySet());
        PlacementJob job = new PlacementJob(actor, PlacementJob.Kind.UNDO, record.title(),
                BlockAccess.of(level(record.dimension()), true, zone, pos -> !locked(record.dimension(), pos) && buildAccess.test(pos),
                        owned(record, registry.placements(record, preview.versionCount()))),
                PlacementOrder.order(preview.diff()));
        jobs.put(job, new JobInfo(source, record, null, null, Instant.now(), null));
        scheduler.enqueue(job);
        int count = record.current().number();
        send(source, Chat.progress(count > 1
                ? "Undoing the last change to “" + record.title() + "” (version " + count + " → " + (count - 1) + ")…"
                : "Removing “" + record.title() + "”…"));
    }

    /** Preview controls must never cancel a newer request or placement when their client view is stale. */
    public void discardPreview(CommandSourceStack source) {
        String actor = ownerOf(source);
        PendingPlacement preview = playerSession(actor).preview.current;
        if (preview != null) {
            if (!clearSavedPreview(source)) return;
            playerSession(actor).preview.take();
            playerSession(actor).alternatives = null;
            discard(preview);
            keepDiscarded(preview);
            send(source, Chat.info("Discarded the preview; its plan is kept for 30 minutes."));
        } else send(source, Chat.error("No build preview is waiting."));
        sendStateTo(source);
    }

    public void cancel(CommandSourceStack source) {
        String actor = ownerOf(source);
        boolean any = playerSession(actor).alternatives != null || playerSession(actor).savedPreview != null
                || playerSession(actor).previewLoadProblem != null;
        // A disk error must not prevent stopping an AI call or rolling back an active placement.
        clearSavedPreview(source);
        playerSession(actor).alternatives = null;
        if (playerSession(actor).retainedPlan != null) {
            playerSession(actor).retainedPlan = null;
            send(source, Chat.info("Discarded the retained plan."));
            any = true;
            sendStateTo(source);
        }
        ActiveRequest request = playerSession(actor).planning.current;
        if (request != null) {
            endRequest(request, true);
            send(source, Chat.info("Cancelled the AI request."));
            any = true;
        }
        PendingPlacement preview = playerSession(actor).preview.take();
        if (preview != null) {
            discard(preview);
            keepDiscarded(preview);
            send(source, Chat.with(Chat.info("Discarded the plan for “" + preview.title() + "”; nothing was built. It's kept "
                    + "for 30 minutes."), "Bring it back", "/justbuild retry", "Preview it again, without another AI call"));
            any = true;
        }
        if (playerSession(actor).pendingUndo != null) {
            playerSession(actor).pendingUndo = null;
            send(source, Chat.info("Kept it. Nothing was undone."));
            any = true;
        }
        for (PlacementJob job : scheduler.cancel(actor)) {
            any = true;
            JobInfo info = jobs.remove(job);
            if (info != null) {
                onJobCancelled(job, info, source);
            }
        }
        if (!any) {
            send(source, Chat.error("Nothing to cancel."));
        }
        sendStateTo(source);
    }

    private void onJobCancelled(PlacementJob job, JobInfo info, CommandSourceStack source) {
        BuildRecord record = info.record();
        String progress = job.placedCount() + " of " + job.total() + " blocks";
        switch (job.kind()) {
            case BUILD -> {
                record.recordBaseline(job.previousStates());
                saveRecord(record);
                busy.remove(record.id());
                send(source, Chat.info("Cancelled “" + record.title() + "” after " + progress
                        + "."));
                send(source, Chat.with(Chat.info("What was placed stays until you undo it."), "Undo", "/justbuild undo",
                        "Remove the part that was placed"));
            }
            case EDIT -> {
                record.recordBaseline(job.previousStates());
                Map<BlockPos, BlockState> revert = new LinkedHashMap<>();
                for (Placement p : job.previousStates()) {
                    BlockState back = info.restoreLive() ? p.state() : info.before().containsKey(p.pos()) ? info.before().get(p.pos())
                            : record.baseline().get(p.pos());
                    if (back != null) {
                        revert.put(p.pos(), back);
                    }
                }
                var buildAccess = liveBuildAccess(source, record, revert.keySet());
                PlacementJob revertJob = new PlacementJob(job.owner(), PlacementJob.Kind.REVERT, record.title(),
                        BlockAccess.of(level(record.dimension()), true, job.world().zone(),
                                pos -> job.world().allows(pos) && !locked(record.dimension(), pos) && buildAccess.test(pos),
                                owned(record, registry.current(record))), PlacementOrder.order(revert));
                jobs.put(revertJob, new JobInfo(source, record, null, null, Instant.now(), null));
                scheduler.enqueue(revertJob);
                send(source, Chat.info("Cancelled the edit of “" + record.title() + "”; putting back " + revert.size()
                        + " blocks."));
            }
            case UNDO -> {
                busy.remove(record.id());
                history.push(job.owner(), entry(record));
                saveHistory();
                send(source, Chat.info("Cancelled the undo of “" + record.title() + "” after " + progress
                        + "."));
                send(source, Chat.with(Chat.info("Finish it when you're ready."), "Undo", "/justbuild undo", "Undo again"));
            }
            case REVERT -> {
                busy.remove(record.id());
                saveRecord(record);
                send(source, Chat.info("Stopped restoring “" + record.title() + "”."));
            }
        }
    }

    private void onJobFinished(PlacementJob job) {
        JobInfo info = jobs.remove(job);
        if (info == null) {
            return;
        }
        try {
            finishJob(job, info);
        } finally {
            sendStateTo(info.source());
        }
    }

    private static BuildRecord.Version appliedVersion(PlacementJob job, JobInfo info) {
        if (!info.restoreLive()) return info.newVersion();
        Map<BlockPos, BlockState> before = new LinkedHashMap<>();
        job.previousStates().forEach(p -> before.put(p.pos(), p.state()));
        var version = info.newVersion();
        return version.withUndoStates(before);
    }

    private void finishJob(PlacementJob job, JobInfo info) {
        BuildRecord record = info.record();
        CommandSourceStack source = info.source();
        PlayerSession active = playerSessions.get(job.owner());
        if (active != null) active.materialPalette = null;
        switch (job.kind()) {
            case BUILD -> {
                record.recordBaseline(job.previousStates());
                if (!saveRecord(record)) send(source, Chat.error(unsaved(record)));
                busy.remove(record.id());
                LOGGER.info("Placed {} blocks for \"{}\" ({})", job.total(), record.title(), record.id());
                reportPlayerGaps(source, job);
                send(source, Chat.finished(record.title(), job.total(), sinceRequest(info), info.aiSummary(), record.id()));
                bar(source, StatusText.BAR + "Finished “" + record.title() + "”");
            }
            case EDIT -> {
                record.recordBaseline(job.previousStates());
                record.addVersion(appliedVersion(job, info));
                if (!saveRecord(record)) send(source, Chat.error(unsaved(record)));
                busy.remove(record.id());
                history.push(job.owner(), entry(record));
                saveHistory();
                LOGGER.info("Updated \"{}\" ({}) to version {}: {} blocks", record.title(), record.id(),
                        record.current().number(), job.total());
                reportPlayerGaps(source, job);
                send(source, Chat.updated(record.title(), record.current().number(), job.total(), sinceRequest(info),
                        info.aiSummary(), record.id()));
                bar(source, StatusText.BAR + "Updated “" + record.title() + "”");
            }
            case UNDO -> {
                busy.remove(record.id());
                LOGGER.info("Restored {} blocks for \"{}\" ({})", job.total(), record.title(), record.id());
                if (record.versions().size() > 1) {
                    record.removeLastVersion();
                    saveRecord(record);
                    send(source, Chat.info("Undid the last change to “" + record.title() + "” (now version "
                            + record.current().number() + ")."));
                } else {
                    registry.remove(record.id());
                    buildAccessRevision++;
                    deleteRecord(record.id());
                    forgetProjectBuild(source, record.id());
                    send(source, Chat.info("Removed “" + record.title() + "”."));
                }
                saveHistory();
            }
            case REVERT -> {
                busy.remove(record.id());
                saveRecord(record);
                send(source, Chat.info("Edit cancelled; “" + record.title() + "” is back to version "
                        + record.current().number() + "."));
            }
        }
    }

    public void tick() {
        construction.tick();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            PlayerSession session = playerSessions.get(player.getUUID().toString());
            if (session != null && session.pendingHello != null && server.getTickCount() >= session.nextHelloTick) {
                RelayJson.Hello hello = RelayJson.parseHello(session.pendingHello);
                session.pendingHello = null;
                session.nextHelloTick = (long) server.getTickCount() + 20;
                if (!java.util.Objects.equals(session.hello, hello)) {
                    session.hello = hello;
                    sendState(player);
                }
            }
        }
        if (!completedThumbnails.isEmpty()) {
            var ready = Set.copyOf(completedThumbnails); completedThumbnails.clear();
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                PlayerSession session = playerSessions.get(player.getUUID().toString());
                if (session != null && session.selectedBuild != null && registry.get(session.selectedBuild)
                        .map(r -> r.versions().stream().anyMatch(v -> ready.contains(v.id()))).orElse(false)) sendState(player);
            }
        }
        revokePlacementAccess();
        scheduler.tick();
        if (server.getTickCount() % 5 == 0) sendBuilderBots();
        if (server.getTickCount() % 20 == 0) {
            server.getPlayerList().getPlayers().forEach(this::giveStarterWand);
            expireQuestions();
            reportProgress();
        }
    }

    /** Once a second: action-bar status for every request and job, plus chat heartbeats and placement milestones. */
    private void reportProgress() {
        Instant now = Instant.now();
        for (PendingPlacement p : waitingPreviews()) {
            drawOutline(p);
            showPreview(p, true);
            bar(p.source(), StatusText.BAR + (p.isNew()
                    ? "Preview ready: Right Shift → Instant Build or Guided Build"
                    : "Preview ready: Right Shift → Instant Build or Discard"));
        }
        for (ActiveRequest request : planningRequests()) {
            RequestStatus status = request.status;
            if (request.inFlight == null || status == null) {
                continue;
            }
            Duration elapsed = status.elapsed(now);
            for (String[] notice = status.nextNotice(); notice != null; notice = status.nextNotice()) {
                if (noticesShown.add(notice[0])) {
                    send(request.source, Chat.notice(notice[1]));
                }
            }
            bar(request.source, StatusText.waitingBar(status.provider, status.currentPhase(), status.operationCount(),
                    status.reviewRound(), status.reviewRounds(), status.isShrinking(), status.note(), elapsed));
            if (status.heartbeatDue(elapsed)) {
                send(request.source, Chat.working(StatusText.heartbeat(status.provider, status.currentPhase(),
                        status.operationCount(), status.reviewRound(), status.reviewRounds(), status.isShrinking(),
                        status.note(), elapsed)));
            }
        }
        for (PlacementJob job : scheduler.jobs()) {
            JobInfo info = jobs.get(job);
            if (info == null) {
                continue;
            }
            bar(info.source(), job.waiting() ? StatusText.BAR + "Placing “" + job.title() + "” is paused: go back near it to finish"
                    : StatusText.placingBar(job.title(), job.placedCount(), job.total(), job.refreshing()));
            int mark = StatusText.milestone(job.placedCount(), job.total(), info.progress().nextMilestone);
            if (mark > 0 && Duration.between(info.progress().started, now).toSeconds() >= 2) {
                info.progress().nextMilestone = mark + 25;
                send(info.source(), Chat.progress("Placing “" + job.title() + "”: " + mark + "% (" + job.placedCount()
                        + " of " + job.total() + " blocks)…"));
            }
        }
    }

    private static void reportPlayerGaps(CommandSourceStack source, PlacementJob job) {
        if (job.skippedOccupied() > 0) {
            send(source, Chat.info("Left " + count(job.skippedOccupied(), "spot") + " unchanged where a player, pet, or villager "
                    + "was standing, or where a chest or other protected block appeared meanwhile."));
        }
    }

    /** Time the AI took and the tokens it reported, for the Finished and Updated lines. */
    private static String aiSummary(ActiveRequest request) {
        RequestStatus status = request.status;
        if (status == null) {
            return null;
        }
        return StatusText.aiSummary(status.elapsed(Instant.now()), status.tokensIn(), status.tokensOut(), status.calls());
    }

    private static String planReady(ActiveRequest request, BuildPlan plan) {
        int count = plan.operations().size();
        int reviews = request.status == null ? 0 : request.status.reviewsStarted();
        boolean smaller = request.status != null && request.status.smallerTries() > 0;
        String steps = (smaller ? "making it smaller" : "") + (smaller && reviews > 0 ? " and " : "")
                + (reviews == 0 ? "" : reviews == 1 ? "1 review round" : reviews + " review rounds");
        return "Plan ready in " + elapsed(request) + (steps.isEmpty() ? "" : " after " + steps) + " (" + count(count, "shape")
                + "). ";
    }

    private static String elapsed(ActiveRequest request) {
        return request.status == null ? "0:00" : StatusText.elapsed(request.status.elapsed(Instant.now()));
    }

    private static String sinceRequest(JobInfo info) {
        return StatusText.elapsed(Duration.between(info.requestStarted(), Instant.now()));
    }

    /** Action-bar text for player sources; console and RCON have no action bar. */
    private static void bar(CommandSourceStack source, String text) {
        ServerPlayer player = source.getPlayer();
        if (player != null) {
            player.sendSystemMessage(Chat.bar(text), true);
        }
    }

    private void expireQuestions() {
        Instant now = Instant.now();
        for (PlayerSession session : List.copyOf(playerSessions.values())) {
            RetainedPlan plan = session.retainedPlan;
            if (plan != null && now.isAfter(plan.expires())) {
                session.retainedPlan = null;
                sendStateTo(plan.request().source);
            }
        }
        for (Iterator<ActiveRequest> it = List.copyOf(planningRequests()).iterator(); it.hasNext(); ) {
            ActiveRequest request = it.next();
            if (request.conversation.isExpired(now)) {
                String question = request.conversation.pendingQuestion();
                endRequest(request, false);
                send(request.source, Chat.error("The question “" + question + "” timed out. Start again."));
                finishAlternatives(request.actor);
            }
        }
    }

    // ---- config / status ----

    public void playerAi(CommandSourceStack source) {
        String description = switch (config.playerAi) {
            case "require" -> "Remote players must use their own AI; unavailable player AI is refused.";
            case "prefer" -> "Use remote players' AI when ready, otherwise use the server's active profile.";
            default -> "Always use the server's active profile.";
        };
        send(source, Chat.info("Player AI policy: " + config.playerAi + ". " + description
                + " The singleplayer owner, LAN host, and console always use the server's profile."));
    }

    public void setPlayerAi(CommandSourceStack source, String policy) {
        if (!JustBuildCommand.allowed(source)) {
            send(source, Chat.error("Only operators or the world owner can change the server's player AI policy."));
            return;
        }
        String value = policy.strip().toLowerCase(java.util.Locale.ROOT);
        if (!JustBuildConfig.PLAYER_AI.contains(value)) {
            send(source, Chat.error("Unknown player AI policy “" + policy + "”. Use require, prefer, or off."));
            return;
        }
        if (!configWritable(source)) return;
        String previous = config.playerAi;
        config.playerAi = value;
        if (!saveConfig(source)) {
            config.playerAi = previous;
            return;
        }
        sendStateToAll();
        playerAi(source);
    }

    /** Switches to the first profile using that provider, adding a starter profile if there is none. */
    public void setProvider(CommandSourceStack source, String id) {
        if (!JustBuildConfig.PROVIDERS.contains(id)) {
            send(source, Chat.error("Unknown provider “" + id + "”. Use " + String.join(", ", JustBuildConfig.PROVIDERS) + "."));
            return;
        }
        Optional<JustBuildConfig.ModelProfile> existing = config.profiles.stream().filter(p -> p.provider.equals(id)).findFirst();
        if (existing.isEmpty()) {
            if (config.profiles.size() >= JustBuildConfig.MAX_PROFILES) {
                send(source, Chat.error("No profile uses " + id + " and there are already " + JustBuildConfig.MAX_PROFILES
                        + " profiles. Remove one first."));
                return;
            }
            List<JustBuildConfig.ModelProfile> list = new java.util.ArrayList<>(config.profiles);
            list.add(new JustBuildConfig.ModelProfile(Providers.displayName(id), id, ""));
            config.replaceProfiles(list, config.activeProfile);
            existing = Optional.of(config.profiles.get(config.profiles.size() - 1));
        }
        switchProfile(source, existing.get().name);
    }

    public List<String> profileNames() {
        return config.profileNames();
    }

    public void setProfile(CommandSourceStack source, String name) {
        if (config.profile(name).isEmpty()) {
            send(source, Chat.error("No profile called “" + name.strip() + "”. Profiles: "
                    + String.join(", ", config.profileNames()) + "."));
            return;
        }
        switchProfile(source, name);
    }

    private void switchProfile(CommandSourceStack source, String name) {
        if (!configWritable(source)) return;
        config.activate(name);
        if (saveConfig(source)) {
            send(source, Chat.info("Using profile “" + config.activeProfile + "” (" + profileLabel(config.active()) + ")."));
            sendStateToAll();
        }
    }

    public void setModel(CommandSourceStack source, String model) {
        if (!configWritable(source)) return;
        if (!config.active().isApi() && !dev.justbuild.provider.CliModel.valid(model)) {
            send(source, Chat.error("Choose a CLI model identifier without spaces or shell characters."));
            return;
        }
        config.setModel("default".equalsIgnoreCase(model.trim()) ? "" : model.trim());
        if (saveConfig(source)) {
            send(source, Chat.info("Model for profile “" + config.activeProfile + "” set to " + modelLabel(config.active()) + "."));
            sendStateToAll();
        }
    }

    public void profiles(CommandSourceStack source) {
        send(source, Chat.profiles(config.profiles, config.activeProfile, BuildSessionManager::profileLabel));
    }

    /** /justbuild model (or models): the active profile's models as clickable names. */
    public void models(CommandSourceStack source) {
        JustBuildConfig.ModelProfile profile = config.active().copy();
        BuildProvider provider = Providers.create(config, profile);
        send(source, Chat.progress("Asking " + provider.displayName() + " which models it has…"));
        workers.execute(() -> {
            Component line;
            try {
                line = Chat.models(profile.name, provider.models(new CancellationToken()));
            } catch (ProviderException e) {
                line = Chat.error("Could not list models for “" + profile.name + "”: " + e.getMessage());
            } catch (RuntimeException e) {
                LOGGER.error("Listing models failed for {}", profile.name, e);
                line = Chat.error("Could not list models for “" + profile.name + "”: " + e);
            }
            Component result = line;
            onServer(() -> send(source, result));
        });
    }

    /** The profile editor's Save: replaces every profile. Only players who may use /justbuild. */
    public void applyProfiles(ServerPlayer player, String json) {
        CommandSourceStack source = player.createCommandSourceStack();
        if (!JustBuildCommand.allowed(source)) {
            send(source, Chat.error("Only operators (or the singleplayer world owner) can change JustBuild profiles."));
            return;
        }
        if (json.length() > ProfilesPayload.MAX_LENGTH) {
            send(source, Chat.error("Those profile changes are too large to send."));
            return;
        }
        if (!configWritable(source)) return;
        try {
            ProfileEdits.Request request = ProfileEdits.parse(json);
            List<JustBuildConfig.ModelProfile> profiles = ProfileEdits.apply(config.profiles, request);
            config.replaceProfiles(profiles, request.active());
        } catch (ConfigException e) {
            send(source, Chat.error("Profiles not saved: " + e.getMessage()));
            return;
        }
        if (saveConfig(source)) {
            LOGGER.info("{} saved {} JustBuild profiles; active: {}", player.getName().getString(),
                    config.profiles.size(), config.activeProfile);
            send(source, Chat.info("Saved " + config.profiles.size() + (config.profiles.size() == 1 ? " profile" : " profiles")
                    + ". Using “" + config.activeProfile + "” (" + profileLabel(config.active()) + ")."));
            sendStateToAll();
        }
    }

    public void testProfile(ServerPlayer player, dev.justbuild.net.ProfileTestRequestPayload request) {
        profileTests.request(player, request, config);
    }

    /** The model picker: lists models for a profile as currently edited and answers that player only. */
    public void requestModels(ServerPlayer player, int id, String json) {
        if (!JustBuildCommand.allowed(player.createCommandSourceStack())) {
            ServerPlayNetworking.send(player, new ModelsPayload(id, List.of(),
                    "Only operators (or the singleplayer world owner) can list models."));
            return;
        }
        JustBuildConfig.ModelProfile profile;
        try {
            profile = ProfileEdits.resolve(config.profiles, ProfileEdits.parseEdit(json));
        } catch (ConfigException e) {
            ServerPlayNetworking.send(player, new ModelsPayload(id, List.of(), e.getMessage()));
            return;
        }
        BuildProvider provider = Providers.create(config, profile);
        workers.execute(() -> {
            ModelsPayload reply;
            try {
                List<String> models = provider.models(new CancellationToken());
                reply = new ModelsPayload(id, models.subList(0, Math.min(models.size(), ModelsPayload.MAX_MODELS)), "");
            } catch (ProviderException e) {
                reply = new ModelsPayload(id, List.of(), e.getMessage());
            } catch (RuntimeException e) {
                LOGGER.error("Listing models failed for {}", profile.name, e);
                reply = new ModelsPayload(id, List.of(), e.toString());
            }
            ModelsPayload result = reply;
            onServer(() -> {
                if (!player.hasDisconnected() && ServerPlayNetworking.canSend(player, ModelsPayload.TYPE)) {
                    ServerPlayNetworking.send(player, result);
                }
            });
        });
    }

    // ---- players' own AI ----

    static final String NEED_OWN_AI = "This server plans builds with each player's own AI. Install JustBuild "
            + "Client-side, then in the panel (Right Shift → Settings) turn on “Use my AI here” and set up a profile.";

    /** Whose AI plans this source's requests (see relay.AiSource). */
    private AiSource aiSource(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return AiSource.SERVER;
        }
        RelayJson.Hello hello = playerSession(player.getUUID().toString()).hello;
        boolean ready = hello != null && hello.ready() && hello.protocol() == RelayJson.PROTOCOL
                && ServerPlayNetworking.canSend(player, AiRequestPayload.TYPE);
        return AiSource.choose(config.playerAi, true, server.isSingleplayerOwner(player.nameAndId()), ready);
    }

    /** The player's game reported its own AI (on join and whenever it changes). */
    public void onClientAi(ServerPlayer player, String json) {
        if (player.hasDisconnected()) return;
        // Coalesce to the newest value: a burst cannot repeatedly rebuild the complete panel state.
        playerSession(player.getUUID().toString()).pendingHello = json;
    }

    public void giveStarterWand(ServerPlayer player) {
        if (!playerSession(player.getUUID().toString()).starterWand && ZoneWand.giveStarter(player)) {
            playerSession(player.getUUID().toString()).starterWand = true;
        }
    }

    public void playerRespawned(ServerPlayer player) {
        construction.left(player, "after you respawned");
        construction.syncNow(player);
        playerSession(player.getUUID().toString()).starterWand = false;
        giveStarterWand(player);
        sendState(player);
    }

    public void onAiResponse(ServerPlayer player, AiResponsePayload response) {
        relay.onResponse(player, response);
    }

    public void onAiProgress(ServerPlayer player, AiProgressPayload progress) {
        relay.onProgress(player, progress);
    }

    /** A player left: forget their game and fail any request waiting on it. */
    public void playerLeft(ServerPlayer player) {
        construction.left(player, "after you left the game");
        profileTests.cancel(player);
        String actor = player.getUUID().toString();
        PlayerSession session = playerSessions.get(actor);
        if (session != null) {
            savePreview(actor, player.createCommandSourceStack());
            session.planning.end(session.planning.current, true);
            PendingPlacement pending = session.preview.take();
            if (pending != null) discard(pending);
            playerSessions.remove(actor);
        }
        relay.fail(player.getUUID(), "You left the server.");
    }

    /** "Builds here use …" for status and doctor. */
    private String aiLine(CommandSourceStack source) {
        return switch (aiSource(source)) {
            case PLAYER -> "Builds here use your own AI (" + playerSession((source.getPlayer().getUUID()).toString()).hello.label() + ").";
            case REFUSE -> NEED_OWN_AI;
            case SERVER -> "Builds here use the server's profile “" + config.activeProfile + "”"
                    + ("off".equals(config.playerAi) ? "." : " (players' own AI: " + config.playerAi + ").");
        };
    }

    /**
     * Test hook (RelayTest): sends one request straight to a player's game, the way a malicious server could, and
     * returns what the game answers. Call it off the server thread.
     */
    public String relayDirectly(UUID player, PromptSpec spec) throws ProviderException {
        return relay.call(player, new RelayJson.Request(spec, "quick", 10, 1000, 16, 30), new CancellationToken(),
                dev.justbuild.provider.ProgressListener.NONE);
    }

    /** Auto-build: this player's AI results place as soon as they're ready, without a preview. */
    public void autoBuild(CommandSourceStack source, String value) {
        String actor = ownerOf(source);
        boolean on = switch (value.strip().toLowerCase(java.util.Locale.ROOT)) {
            case "on" -> true;
            case "off" -> false;
            default -> !prefs.autoBuild(actor);
        };
        try {
            prefs.autoBuild(actor, on);
        } catch (IOException e) {
            send(source, Chat.error("Auto-build wasn't changed because the setting couldn't be saved: " + e.getMessage()));
            sendStateTo(source);
            return;
        }
        send(source, Chat.info(on ? "Auto-build is on: AI results place as soon as they're ready. Undo still works; "
                + "alternatives, saved plans, and design copies still preview." : "Auto-build is off: AI results wait as a preview."));
        sendStateTo(source);
    }

    /** Test hook: whether a build is held by a request, preview, or placement. */
    public boolean isBusy(String id) {
        return busy.contains(id);
    }

    /** Test hook: a saved build's title, or null. */
    public String buildTitle(String id) {
        return registry.get(id).map(BuildRecord::title).orElse(null);
    }

    /** Test hook and status: how many builds this world has saved. */
    public int savedBuildCount() {
        return registry.all().size();
    }

    // ---- panel state ----

    /** Visual activity is independent of the panel and contains no AI request text. */
    private void sendBuilderBots() {
        Map<String, BuilderWorksite> sites = new LinkedHashMap<>();
        for (PlacementJob job : scheduler.jobs()) {
            JobInfo info = jobs.get(job);
            if (info == null) continue;
            sites.putIfAbsent(job.owner(), new BuilderWorksite(job.owner(),
                    builderArea(info.record().dimension(), job.world().zone(), info.record().origin()), !job.waiting()));
        }
        for (ActiveRequest request : planningRequests()) {
            sites.putIfAbsent(request.actor, new BuilderWorksite(request.actor,
                    builderArea(request.level.dimension(), request.zone, request.origin),
                    request.inFlight != null && !request.conversation.hasPendingQuestion()));
        }
        for (PendingPlacement preview : waitingPreviews()) {
            sites.putIfAbsent(preview.actor(), new BuilderWorksite(preview.actor(),
                    preview.zone() != null ? builderArea(preview.level().dimension(), preview.zone(), preview.record().origin())
                            : new BuildRegion(preview.level().dimension(), new BlockPos(preview.min().getX(), preview.record().origin().getY(), preview.min().getZ()),
                                    new BlockPos(Math.min(preview.max().getX(), preview.min().getX() + BuildRegion.MAX_SPAN - 1),
                                            preview.record().origin().getY(), Math.min(preview.max().getZ(), preview.min().getZ() + BuildRegion.MAX_SPAN - 1))), false));
        }
        var players = server.getPlayerList().getPlayers();
        lastBuilderBots.keySet().retainAll(players.stream().map(ServerPlayer::getUUID).toList());
        for (ServerPlayer player : players) {
            if (!ServerPlayNetworking.canSend(player, dev.justbuild.net.BuilderBotsPayload.TYPE)) continue;
            var nearby = sites.values().stream().filter(site -> site.area().dimension().equals(player.level().dimension())
                            && site.distanceSquared(player.position()) <= BuilderWorksite.VIEW_DISTANCE * BuilderWorksite.VIEW_DISTANCE)
                    .sorted(Comparator.comparingDouble((BuilderWorksite site) -> site.distanceSquared(player.position()))
                            .thenComparing(BuilderWorksite::id))
                    .limit(dev.justbuild.net.BuilderBotsPayload.MAX_SITES).toList();
            var payload = new dev.justbuild.net.BuilderBotsPayload(nearby);
            var previous = lastBuilderBots.put(player.getUUID(), payload);
            if (!payload.equals(previous) || !nearby.isEmpty() && server.getTickCount() % 20 == 0) {
                ServerPlayNetworking.send(player, payload);
            }
        }
    }

    private static BuildRegion builderArea(ResourceKey<Level> dimension, BuildZone zone, BlockPos origin) {
        if (zone != null) return new BuildRegion(dimension, new BlockPos(zone.minX(), origin.getY(), zone.minZ()),
                new BlockPos(zone.maxX(), origin.getY(), zone.maxZ()));
        return new BuildRegion(dimension,
                new BlockPos(Math.max(-30_000_000, origin.getX() - 3), origin.getY(), Math.max(-30_000_000, origin.getZ() - 3)),
                new BlockPos(Math.min(30_000_000, origin.getX() + 3), origin.getY(), Math.min(30_000_000, origin.getZ() + 3)));
    }

    /** Capture transient state on the server thread; document construction has its own presentation layer. */
    public void sendState(ServerPlayer player) {
        construction.sync(player);
        if (player.hasDisconnected() || !ServerPlayNetworking.canSend(player, StatePayload.TYPE)) return;
        String actor = player.getUUID().toString(); var session = playerSession(actor);
        var source = player.createCommandSourceStack(); var dimension = player.level().dimension();
        boolean manage = JustBuildCommand.allowed(source);
        StateJson.Ai ai = switch (aiSource(source)) {
            case PLAYER -> new StateJson.Ai("player", session.hello.label());
            case REFUSE -> StateJson.Ai.NONE;
            case SERVER -> StateJson.Ai.SERVER;
        };
        BuildRecord editing = selected(source).orElse(null);
        PartSelection part = session.part;
        boolean partHere = part != null && part.dimension().equals(dimension);
        String[] links = session.connections;
        var selection = new PanelStateBuilder.Selection(session.zone == null ? null : session.zone.zone(),
                session.zone != null && session.zone.zone() == null, partHere ? part.region() : null,
                partHere ? part.outline() : null, partHere && part.region() == null,
                ZoneWand.isPart(player.getMainHandItem()) || ZoneWand.isPart(player.getOffhandItem()),
                links == null ? null : links[0], links == null ? null : links[1], retainedTitle(session));
        var polygon = session.polygonDraft;
        var workshop = new PanelStateBuilder.Workshop(prefs.constraints(actor), polygon == null ? null
                : new PanelStateBuilder.PolygonView(polygon.dimension(), polygon.y(), polygon.points()),
                session.materialPalette == null ? Map.of() : session.materialPalette, session.sampledMaterial, session.activeProject, session.routeReport);
        ActiveRequest request = session.planning.current;
        boolean asking = request != null && request.conversation.hasPendingQuestion();
        var activity = new PanelStateBuilder.Activity(asking ? request.conversation.pendingQuestion() : null,
                asking ? request.conversation.pendingOptions() : List.of(), request == null ? null : request.conversation.request(),
                session.pendingUndo == null ? null : registry.get(session.pendingUndo.buildId()).map(BuildRecord::title).orElse(null),
                history.peek(actor).map(ActionHistory.Entry::buildId).flatMap(registry::get).map(BuildRecord::title).orElse(null),
                scheduler.active(actor).map(PlacementJob::title).orElse(null));
        PendingPlacement preview = session.preview.current; Alternatives options = session.alternatives;
        var summary = preview == null ? null : new StateJson.PreviewSummary(preview.title(), preview.isNew(), preview.quality(),
                options == null ? List.of() : options.choices.stream().map(PendingPlacement::title).toList(), options == null ? 0 : options.selected,
                preview.record().origin().toShortString() + " · " + preview.record().facing().getName(),
                preview.max().getX() - preview.min().getX() + 1, preview.max().getY() - preview.min().getY() + 1,
                preview.max().getZ() - preview.min().getZ() + 1, preview.counts(), preview.isNew(),
                preview.revisions().count(), preview.revisions().hasPrevious());
        var view = new PanelStateBuilder.View(actor, dimension, manage, ai, editing, selection, workshop, activity, summary,
                options != null && options.collecting ? "Planning option " + options.index + " of " + options.count : null,
                session.usage.snapshot(), preview == null ? List.of() : qualityIssues(preview),
                preview == null ? "" : preview.level().dimension().identifier().toString(),
                session.qualityUndo != null && session.qualityUndo.repaired() == preview, prefs.autoBuild(actor));
        var catalog = new PanelStateBuilder.Catalog(registry, designs.values(), projects.values(), lockedAreas,
                manage ? designTransfer.files() : List.of(), this::playerName, versionThumbnails::get);
        ServerPlayNetworking.send(player, new StatePayload(PanelStateBuilder.build(config, view, catalog)));
    }

    private void sendStateToAll() {
        server.getPlayerList().getPlayers().forEach(this::sendState);
    }

    /** After a build changes: refresh the panel of the player behind the source, if any. */
    private void sendStateTo(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player != null) {
            // A source kept from before a respawn holds the old player object, still in its old dimension.
            ServerPlayer live = server.getPlayerList().getPlayer(player.getUUID());
            sendState(live != null ? live : player);
        }
    }

    // ---- adoption, local edits, interiors and village projects ----

    private BuildRecord workshopBuild(CommandSourceStack source) {
        if (!canPlace(source) || !canChangeZone(source)) return null;
        BuildRecord record = selected(source).orElse(null);
        if (record == null || !canModify(source, record)) {
            send(source, Chat.error("Select one of your (or your team's) buildings first.")); return null;
        }
        if (busy.contains(record.id())) { send(source, Chat.error(busyMessage(source, record))); return null; }
        PartSelection part = playerSession(ownerOf(source)).part;
        if (part != null && record.id().equals(part.buildId()) && part.region() == null) {
            send(source, Chat.error(part.nextInstruction())); return null;
        }
        return record;
    }

    public void addExisting(CommandSourceStack source, String name) {
        if (!canPlace(source) || !canChangeZone(source)) return;
        String actor = ownerOf(source); PartSelection selection = playerSession(actor).part;
        if (selection == null || selection.region() == null || !selection.dimension().equals(source.getLevel().dimension())) {
            send(source, Chat.error("Use the Builder Wand in Box mode: left-click to start, then right-click to include the footprint and roof.")); return;
        }
        try {
            String title = BuildRecord.label(name); BuildRegion area = selection.region();
            long volume = (long) (area.max().getX() - area.min().getX() + 1) * (area.max().getY() - area.min().getY() + 1)
                    * (area.max().getZ() - area.min().getZ() + 1);
            if (volume > config.maxBlocks) throw new PlanException("Select a smaller building; the selected volume exceeds the block limit.");
            if (area.min().getY() < source.getLevel().getMinY() || area.max().getY() > source.getLevel().getMaxY())
                throw new PlanException("Keep both corners inside the world's build height.");
            for (BuildRecord other : registry.all()) {
                if (other.dimension().equals(area.dimension()) && registry.current(other).entrySet().stream()
                        .anyMatch(e -> !e.getValue().isAir() && area.contains(e.getKey())))
                    throw new PlanException("The selection overlaps saved building “" + other.title() + "”. Adjust the corners.");
            }
            Map<BlockPos, BlockState> blocks = new LinkedHashMap<>(), baseline = new LinkedHashMap<>();
            int protectedCount = 0;
            for (BlockPos p : BlockPos.betweenClosed(area.min(), area.max())) {
                if (!source.getLevel().hasChunk(p.getX() >> 4, p.getZ() >> 4)) throw new PlanException("Move closer so the whole building is loaded.");
                if (locked(area.dimension(), p) || source.getLevel().getBlockEntity(p) != null) { protectedCount++; continue; }
                blocks.put(p.immutable(), source.getLevel().getBlockState(p));
                baseline.put(p.immutable(), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
            }
            if (blocks.values().stream().noneMatch(b -> !b.isAir())) throw new PlanException("No editable building blocks are inside the selection.");
            BuildPlan plan = PlanSnapshot.capture(title, area.min(), Direction.NORTH, blocks); config.limits().check(plan);
            var validated = PlanPipeline.run(plan, area.min(), Direction.NORTH, source.getLevel(), p -> readLoaded(source.getLevel(), p), config.maxBlocks);
            if (!validated.invalid().isEmpty()) throw new PlanException("The selection contains blocks that cannot be safely edited. Adjust the corners.");
            BuildRecord record = new BuildRecord(registry.newId(), actor, area.dimension(), area.min(), Direction.NORTH,
                    List.of(new BuildRecord.Version("Added existing build", plan, System.currentTimeMillis())), baseline);
            record.adopted(true); record.rename(title);
            record.zone(BuildZone.between(area.dimension(), area.min(), area.max()));
            record.addCheckpoint(new BuildRecord.Checkpoint("Original building", plan, System.currentTimeMillis()));
            store.save(record); registry.add(record); buildAccessRevision++; playerSession(actor).part = null;
            joinProject(source, record, playerSession(actor).activeProject); selectBuild(source, record.id());
            send(source, Chat.info("Added “" + title + "” without changing any blocks. Original checkpoint saved; future edits are undoable. "
                    + (protectedCount == 0 ? "" : protectedCount + " protected or block-entity positions were excluded.")));
        } catch (IOException | PlanException | IllegalArgumentException e) { send(source, Chat.error("Could not add existing build: " + e.getMessage())); }
    }

    private Map<BlockPos, BlockState> liveBlocks(BuildRecord record) throws PlanException {
        Map<BlockPos, BlockState> live = new LinkedHashMap<>(); ServerLevel world = level(record.dimension());
        var expected = registry.current(record); ensureLoaded(world, expected.keySet());
        expected.keySet().forEach(p -> live.put(p, world.getBlockState(p))); return live;
    }

    public void materials(CommandSourceStack source) {
        BuildRecord record = selected(source).orElse(null);
        if (record == null || !canModify(source, record)) {
            send(source, Chat.error("Select one of your (or your team's) buildings first.")); return;
        }
        try { playerSession(ownerOf(source)).materialPalette = LocalEdits.palette(liveBlocks(record)); sendStateTo(source); }
        catch (PlanException e) { send(source, Chat.error(e.getMessage())); }
    }

    public void sampleMaterial(CommandSourceStack source) {
        if (source.getPlayer() == null || !(source.getPlayer().pick(EDIT_REACH, 0, false) instanceof BlockHitResult hit)
                || hit.getType() != HitResult.Type.BLOCK) { send(source, Chat.error("Point at a block to sample its material.")); return; }
        try {
            String id = LocalEdits.blockId(LocalEdits.id(source.getLevel().getBlockState(hit.getBlockPos())));
            playerSession(ownerOf(source)).sampledMaterial = id; send(source, Chat.info("Sampled " + id + ". Open Materials to use it.")); sendStateTo(source);
        } catch (PlanException e) { send(source, Chat.error(e.getMessage())); }
    }

    public void replaceMaterial(CommandSourceStack source, String from, String to) {
        BuildRecord record = workshopBuild(source); if (record == null) return;
        try { previewLocalChange(source, record, "Replace " + from + " with " + to, LocalEdits.replace(liveBlocks(record), from, to)); }
        catch (PlanException | IllegalArgumentException e) { send(source, Chat.error(e.getMessage())); }
    }

    /** Rerasterize existing mix palettes locally; matching world cells alone are eligible. */
    public void texture(CommandSourceStack source, Integer seed, String material, Integer weight) {
        if (!canPlace(source)) return;
        PendingPlacement preview = playerSession(ownerOf(source)).preview.current;
        if (preview != null && !preview.isNew()) {
            send(source, Chat.error("Confirm or discard the current edit preview before changing texture.")); return;
        }
        BuildRecord record = preview == null ? workshopBuild(source) : preview.record();
        if (record == null) return;
        try {
            BuildPlan original = preview == null ? record.current().plan() : preview.sourcePlan();
            var palette = dev.justbuild.plan.PlanTextures.materials(original);
            if (palette.isEmpty()) throw new PlanException("This plan has no procedural material mixes to reroll. Solid and existing builds keep their materials.");
            var weights = new LinkedHashMap<>(original.texture().weights());
            if (material != null) {
                if (!palette.contains(material)) throw new PlanException("Choose a material used in this plan's mix: " + String.join(", ", new java.util.TreeSet<>(palette)));
                weights.put(material, weight);
            }
            int nextSeed = seed != null ? seed : material == null ? original.texture().seed() + 1 : original.texture().seed();
            BuildPlan revised = original.withTexture(new dev.justbuild.plan.TextureSettings(nextSeed, weights));
            if (preview != null) { rebuildPreview(source, 0, 0, 0, 0, false, revised); return; }
            var before = registry.current(record);
            var rendered = PlanPipeline.run(revised, record.origin(), record.facing(), source.getLevel(),
                    p -> record.baseline().getOrDefault(p, readLoaded(source.getLevel(), p)), config.maxBlocks);
            ensureLoaded(source.getLevel(), before.keySet());
            Map<BlockPos, BlockState> changes = new LinkedHashMap<>();
            rendered.placements().forEach((pos, state) -> {
                BlockState old = before.get(pos);
                if (old != null && old.isAir() == state.isAir() && !old.equals(state)
                        && source.getLevel().getBlockState(pos).equals(old)) changes.put(pos, state);
            });
            previewLocalChange(source, record, "Texture seed " + nextSeed + (material == null ? "" : ", " + material + " weight " + weight),
                    changes, revised, rendered.placements());
        } catch (PlanException | IllegalArgumentException e) { send(source, Chat.error(e.getMessage())); }
    }

    public void repair(CommandSourceStack source, boolean missingOnly, String checkpoint) {
        BuildRecord record = workshopBuild(source); if (record == null) return;
        try {
            Map<BlockPos, BlockState> expected;
            if (checkpoint == null) expected = registry.current(record);
            else {
                var saved = record.checkpoints().stream().filter(c -> c.name().equalsIgnoreCase(checkpoint.strip())).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("Choose an existing checkpoint."));
                expected = PlanPipeline.run(saved.plan(), record.origin(), record.facing(), source.getLevel(),
                        p -> record.baseline().getOrDefault(p, readLoaded(source.getLevel(), p)), config.maxBlocks).placements();
            }
            ensureLoaded(source.getLevel(), expected.keySet());
            var changes = LocalEdits.repair(expected, source.getLevel()::getBlockState, missingOnly,
                    p -> Block.updateFromNeighbourShapes(expected.get(p), source.getLevel(), p));
            previewLocalChange(source, record, "Repair " + (missingOnly ? "missing blocks" : "all differences")
                    + (checkpoint == null ? " from current version" : " from checkpoint " + checkpoint), changes);
        } catch (PlanException | IllegalArgumentException e) { send(source, Chat.error(e.getMessage())); }
    }

    private void previewLocalChange(CommandSourceStack source, BuildRecord record, String description,
            Map<BlockPos, BlockState> changes) throws PlanException {
        previewLocalChange(source, record, description, changes, null, Map.of());
    }

    private void previewLocalChange(CommandSourceStack source, BuildRecord record, String description,
            Map<BlockPos, BlockState> changes, BuildPlan recipe, Map<BlockPos, BlockState> rendered) throws PlanException {
        String actor = ownerOf(source);
        ActiveRequest request = new ActiveRequest(actor, new Conversation(description), source.getLevel(), record.origin(), record.facing(),
                record, null, false, null, source);
        request.zone = zoneFor(actor, record.dimension()); request.savedPlan = true; request.forcePreview = true; request.restoreLive = true;
        PartSelection part = playerSession(actor).part;
        if (part != null && part.region() != null && record.id().equals(part.buildId())) request.focus = part.region();
        previewLocalChange(request, changes, recipe, rendered);
    }

    private void previewLocalChange(ActiveRequest request, Map<BlockPos, BlockState> changes,
            BuildPlan recipe, Map<BlockPos, BlockState> rendered) throws PlanException {
        String actor = request.actor; CommandSourceStack source = request.source; BuildRecord record = request.target;
        String description = request.conversation.request();
        Map<BlockPos, BlockState> allowed = new LinkedHashMap<>(changes);
        // Local tools select matching cells; older parts of the saved build may lie outside today's zone.
        allowed.keySet().removeIf(p -> !mayChange(request, p) || request.zone != null && !request.zone.contains(p));
        ensureLoaded(source.getLevel(), allowed.keySet());
        Map<BlockPos, BlockState> before = new LinkedHashMap<>(registry.current(record));
        allowed = new LinkedHashMap<>(Protection.filter(allowed, owned(record, before), source.getLevel()::getBlockEntity, source.getLevel()::getBlockState).placements());
        BuildZone area = safetyArea(record, request.zone, union(before.keySet(), allowed.keySet()));
        reportKept(source, keepOthers(allowed, othersCells(source, record.dimension(), area, record.id())));
        allowed = new LinkedHashMap<>(safe(source, source.getLevel(), allowed, area, pos -> mayChange(request, pos)));
        if (allowed.isEmpty()) { send(source, Chat.info("No matching changes remain in the editable area.")); return; }
        if (request.zone != null) request.zone.check(record.dimension(), allowed.keySet());
        request.constraints.check(record.origin(), allowed, before);
        Map<BlockPos, BlockState> finalBlocks = new LinkedHashMap<>(before); finalBlocks.putAll(allowed);
        BuildPlan plan;
        if (recipe == null) plan = PlanSnapshot.capture(record.title(), record.origin(), record.facing(), finalBlocks);
        else {
            // Keep procedural recipes, overlaying cells excluded by locks, parts or manual edits.
            Map<BlockPos, BlockState> exceptions = new LinkedHashMap<>();
            finalBlocks.forEach((pos, state) -> { if (!state.equals(rendered.get(pos))) exceptions.put(pos, state); });
            rendered.keySet().stream().filter(pos -> !finalBlocks.containsKey(pos)).forEach(pos ->
                    exceptions.put(pos, record.baseline().getOrDefault(pos, readLoaded(source.getLevel(), pos))));
            var operations = new java.util.ArrayList<>(recipe.operations());
            if (!exceptions.isEmpty()) operations.addAll(PlanSnapshot.capture(record.title(), record.origin(), record.facing(), exceptions).operations());
            plan = new BuildPlan(recipe.title(), recipe.summary(), operations, recipe.texture());
        }
        config.limits().check(plan);
        BuildRecord.Version version = new BuildRecord.Version(description, plan, System.currentTimeMillis());
        playerSession(actor).retainedPlan = null; busy.add(record.id()); offer(pending(request, record, false, version, allowed, before, plan), true);
    }

    private List<BuildQuality.Issue> qualityIssues(PendingPlacement p) {
        QualityInfo info = playerSession(p.actor()).qualityInfo;
        if (info == null || info.preview() != p) {
            info = new QualityInfo(p, BuildQuality.details(p.placements(), pos -> readLoaded(p.level(), pos)));
            playerSession(p.actor()).qualityInfo = info;
        }
        return info.issues();
    }

    private void forgetQuality(PendingPlacement p) {
        QualityInfo info = playerSession(p.actor()).qualityInfo;
        if (info != null && info.preview() == p) playerSession(p.actor()).qualityInfo = null;
        QualityUndo undo = playerSession(p.actor()).qualityUndo;
        if (undo != null && (undo.repaired() == p || undo.original() == p)) playerSession(p.actor()).qualityUndo = null;
    }

    public void qualityRepair(CommandSourceStack source, String arguments) {
        String actor = ownerOf(source); PendingPlacement old = playerSession(actor).preview.current;
        if (!canPlace(source) || old == null) { send(source, Chat.error("Create a preview before choosing a repair.")); return; }
        if (!old.level().dimension().equals(source.getLevel().dimension())) { send(source, Chat.error("Return to the preview's dimension.")); return; }
        if (!old.isNew() && !mayModify(source, old.record())) return;
        if (arguments.strip().equals("revert")) {
            QualityUndo undo = playerSession(actor).qualityUndo;
            if (undo == null || undo.repaired() != old) { send(source, Chat.error("No repair suggestion to revert.")); return; }
            playerSession(actor).qualityUndo = null; replacePreview(old, undo.original().withSource(source)); return;
        }
        try {
            String[] args = arguments.strip().split("\\s+", 2);
            if (args.length != 2 || !args[0].equals("repair")) throw new PlanException("Choose a repair in Quality checks.");
            var issue = BuildQuality.details(old.placements(), pos -> readLoaded(old.level(), pos)).stream()
                    .filter(i -> i.id().equals(args[1])).findFirst().orElseThrow(() -> new PlanException("That warning changed; reopen Quality checks."));
            Map<BlockPos, BlockState> repair = BuildQuality.repair(issue, old.placements(), pos -> readLoaded(old.level(), pos));
            ensureLoaded(old.level(), repair.keySet());
            if (repair.keySet().stream().anyMatch(p -> !mayChange(old, p) || old.level().isOutsideBuildHeight(p)))
                throw new PlanException("The suggested repair crosses a locked area, selected part, or world height boundary.");
            if (old.zone() != null) old.zone().check(old.level().dimension(), repair.keySet());
            var protectedCells = othersCells(source, old.level().dimension(), footprintOf(old.level().dimension(), old.record().origin(), repair.keySet()),
                    old.isNew() ? null : old.record().id());
            if (repair.keySet().stream().anyMatch(protectedCells::contains)) throw new PlanException("The repair would affect another building.");
            var protectedResult = Protection.filter(repair, owned(old.record(), old.ours()), old.level()::getBlockEntity, old.level()::getBlockState);
            if (!protectedResult.placements().equals(repair)) throw new PlanException("The repair would affect protected blocks or container data.");
            if (!safe(source, old.level(), repair, old.zone(), p -> mayChange(old, p)).equals(repair))
                throw new PlanException("The repair cannot be placed safely here.");
            old.record().constraints().check(old.record().origin(), repair, old.ours());
            Map<BlockPos, BlockState> changes = new LinkedHashMap<>(old.placements()); changes.putAll(repair);
            if (changes.size() > config.maxBlocks) throw new PlanException("The repair would exceed the block limit.");
            Map<BlockPos, BlockState> model = new LinkedHashMap<>(old.ours()); model.putAll(changes);
            BuildPlan plan = PlanSnapshot.capture(old.title(), old.record().origin(), old.record().facing(), model); config.limits().check(plan);
            BuildRecord record = old.record(); BuildRecord.Version version;
            String description = "Repair suggestion: " + issue.kind().name().toLowerCase(java.util.Locale.ROOT);
            if (old.isNew()) {
                record = new BuildRecord(record.id(), record.owner(), record.dimension(), record.origin(), record.facing(),
                        List.of(new BuildRecord.Version(record.current().request(), plan, System.currentTimeMillis())), Map.of());
                record.zone(old.record().zone()); record.constraints(old.record().constraints()); record.typedRequest(old.record().typedRequest());
                version = null;
            } else version = new BuildRecord.Version(description, plan, System.currentTimeMillis());
            BuildRegion bounds = BuildRegion.enclosing(old.level().dimension(), changes.keySet());
            var changed = new PendingPlacement(actor, source, old.level(), record, old.isNew(), version, changes, old.ours(), old.title(),
                    old.summary(), description + " (no AI call). ", old.aiSummary(), old.requestStarted(), bounds.min(), bounds.max(),
                    old.zone(), old.focus(), old.guards(), BuildQuality.inspect(changes, p -> readLoaded(old.level(), p)), plan,
                    old.restoreLive(), old.projectId(), old.revisions());
            QualityUndo prior = playerSession(actor).qualityUndo;
            playerSession(actor).qualityUndo = new QualityUndo(changed, prior != null && prior.repaired() == old ? prior.original() : old);
            replacePreview(old, changed);
        } catch (PlanException | IllegalArgumentException e) { send(source, Chat.error("Preview unchanged: " + e.getMessage())); }
    }

    private void replacePreview(PendingPlacement old, PendingPlacement next) {
        playerSession(old.actor()).preview.take(); showPreview(old, false);
        Alternatives batch = playerSession(old.actor()).alternatives;
        if (batch != null && !batch.collecting) batch.choices.set(batch.selected, next);
        offer(next, true);
    }

    public void interior(CommandSourceStack source, String text) {
        BuildRecord record = workshopBuild(source); if (record == null) return;
        try {
            var choice = InteriorRequests.parse(text);
            var bounds = BuildRegion.enclosing(record.dimension(), registry.current(record).entrySet().stream()
                    .filter(e -> !e.getValue().isAir()).map(Map.Entry::getKey).toList());
            BlockPos min = bounds.min().offset(1, 1, 1), max = bounds.max().offset(-1, -1, -1);
            PartSelection selected = playerSession(ownerOf(source)).part;
            if (selected != null && selected.region() != null && record.id().equals(selected.buildId())) {
                BuildRegion part = selected.region();
                min = new BlockPos(Math.max(min.getX(), part.min().getX()), Math.max(min.getY(), part.min().getY()), Math.max(min.getZ(), part.min().getZ()));
                max = new BlockPos(Math.min(max.getX(), part.max().getX()), Math.min(max.getY(), part.max().getY()), Math.min(max.getZ(), part.max().getZ()));
            }
            BuildRegion inside = new BuildRegion(record.dimension(), min, max);
            String actor = ownerOf(source);
            PartSelection previous = playerSession(actor).part;
            playerSession(actor).part = new PartSelection(record.id(), record.dimension(), inside.min(), inside);
            try { edit(source, InteriorRequests.request(choice.purpose(), choice.stage(), choice.detail())); }
            finally {
                if (previous == null) playerSession(actor).part = null; else playerSession(actor).part = previous;
                sendStateTo(source);
            }
        } catch (IllegalArgumentException e) { send(source, Chat.error("Could not plan interior: " + e.getMessage())); }
    }

    private void captureProject(ActiveRequest request) {
        if (request.projectCaptured) return;
        request.projectCaptured = true;
        ProjectStore.Project project = request.target == null ? projects.get(playerSession(request.actor).activeProject)
                : projects.values().stream().filter(p -> p.team(request.actor) && p.builds().contains(request.target.id())).findFirst().orElse(null);
        if (project != null && project.team(request.actor) && project.dimension().equals(request.level.dimension())) {
            request.projectId = project.id(); request.theme = project.theme();
        }
    }

    private void saveProject(ProjectStore.Project project) throws IOException {
        if (projectLoadProblem != null) throw new IOException("Repair projects.json and reload first: " + projectLoadProblem);
        var next = new LinkedHashMap<>(projects); next.put(project.id(), project);
        projectStore.save(List.copyOf(next.values())); projects.put(project.id(), project);
        buildAccessRevision++;
        revokePlacementAccess();
    }

    /** Permission and membership changes invalidate work before another placement tick. */
    private void revokePlacementAccess() {
        for (ActiveRequest request : List.copyOf(planningRequests())) {
            if (allowedNow(request.source) && (request.target == null || canModify(request.source, request.target))) continue;
            playerSession(request.actor).alternatives = null;
            endRequest(request, true);
            send(request.source, Chat.error("Your plan was cancelled because your build permissions changed."));
            sendStateTo(request.source);
        }
        for (PendingPlacement p : List.copyOf(waitingPreviews())) {
            if (allowedNow(p.source()) && (p.isNew() || canModify(p.source(), p.record()))) continue;
            playerSession(p.actor()).preview.take();
            playerSession(p.actor()).alternatives = null;
            discard(p);
            send(p.source(), Chat.error("Your preview was discarded because your build permissions changed."));
            sendStateTo(p.source());
        }
        for (PlayerSession session : playerSessions.values()) {
            RetainedPlan plan = session.retainedPlan;
            if (plan != null && (!allowedNow(plan.request().source) || plan.request().target != null
                    && !canModify(plan.request().source, plan.request().target))) session.retainedPlan = null;
        }
        for (PlacementJob job : scheduler.jobs()) {
            JobInfo info = jobs.get(job);
            // A revert only repairs the partial edit already authorized before membership changed.
            if (info == null || job.kind() == PlacementJob.Kind.REVERT
                    || allowedNow(info.source()) && canModify(info.source(), info.record())) continue;
            scheduler.cancel(job);
            jobs.remove(job);
            onJobCancelled(job, info, info.source());
            sendStateTo(info.source());
        }
    }

    private void forgetProjectBuild(CommandSourceStack source, String id) {
        for (var project : List.copyOf(projects.values())) if (project.builds().contains(id)) {
            try { saveProject(project.withBuilds(project.builds().stream().filter(member -> !member.equals(id)).toList())); }
            catch (IOException e) { send(source, Chat.error("Could not remove the undone building from its project: " + e.getMessage())); }
        }
    }

    private void joinProject(CommandSourceStack source, BuildRecord record, String projectId) {
        ProjectStore.Project project = projects.get(projectId);
        if (project == null || !project.team(record.owner()) || !project.dimension().equals(record.dimension())
                || project.builds().contains(record.id())) return;
        try {
            var ids = new java.util.ArrayList<>(project.builds()); ids.add(record.id()); saveProject(project.withBuilds(ids));
        } catch (IOException | IllegalArgumentException e) { send(source, Chat.error("Building saved, but could not join its project: " + e.getMessage())); }
    }

    /**
     * A player by online name, by the name they last joined this server with, or by UUID: {id, name}.
     * An offline UUID uses the name this server last saw them join with.
     */
    private String[] resolvePlayer(String nameOrId, ProjectStore.Project removingFrom) {
        String text = nameOrId.strip();
        if (removingFrom != null) {
            var matches = removingFrom.collaborators().entrySet().stream()
                    .filter(c -> c.getValue().equalsIgnoreCase(text)).toList();
            if (matches.size() > 1) throw new IllegalArgumentException("More than one collaborator used that name. Use their UUID.");
            if (matches.size() == 1) return new String[] {matches.getFirst().getKey(), matches.getFirst().getValue()};
        }
        ServerPlayer online = server.getPlayerList().getPlayerByName(text);
        if (online != null) return new String[] {online.getUUID().toString(), online.getName().getString()};
        Optional<NameAndId> recent = recentLogin(text);
        if (recent.isPresent()) return new String[] {recent.get().id().toString(), recent.get().name()};
        try {
            UUID id = UUID.fromString(text);
            ServerPlayer byId = server.getPlayerList().getPlayer(id);
            String known = server.services().nameToIdCache().get(id).map(NameAndId::name)
                    .or(() -> projects.values().stream().map(p -> p.collaborators().get(id.toString()))
                            .filter(java.util.Objects::nonNull).findFirst()).orElse(null);
            return new String[] {id.toString(), byId != null ? byId.getName().getString() : known != null ? known
                    : id.toString().substring(0, 8)};
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("No player named “" + text + "” is online or joined this server in the last month. "
                    + "Ask them to join, or use their UUID.");
        }
    }

    /** A name from the server's cache of logins. Unlike UserNameToIdResolver.get(String), a miss never asks Mojang. */
    private Optional<NameAndId> recentLogin(String name) {
        if (!(server.services().nameToIdCache() instanceof NameCacheAccessor cache)) return Optional.empty();
        // Expiry matches vanilla's own name lookup: a month after the player's latest join.
        return cache.justbuild$profilesByName().get(name.toLowerCase(java.util.Locale.ROOT)) instanceof NameCacheEntryAccessor entry
                && entry.justbuild$expirationDate().getTime() > System.currentTimeMillis()
                ? Optional.of(entry.justbuild$nameAndId()) : Optional.empty();
    }

    /** A chat line to a player, if they are online. */
    private void warn(String actor, String text) {
        try {
            ServerPlayer player = server.getPlayerList().getPlayer(UUID.fromString(actor));
            if (player != null) player.sendSystemMessage(Chat.error(text));
        } catch (IllegalArgumentException ignored) {
            // the console
        }
    }

    private void tell(String actor, String text) {
        try {
            ServerPlayer player = server.getPlayerList().getPlayer(UUID.fromString(actor));
            if (player != null) {
                player.sendSystemMessage(Chat.info(text));
                sendState(player);
            }
        } catch (IllegalArgumentException ignored) {
            // the console
        }
    }

    public void project(CommandSourceStack source, String command) {
        if (!canChangeZone(source)) return;
        String actor = ownerOf(source); String[] words = command.strip().split("\\s+", 2);
        String action = words[0], value = words.length == 2 ? words[1].strip() : "";
        try {
            ProjectStore.Project active = projects.get(playerSession(actor).activeProject);
            if (active != null && (!active.team(actor) || !active.dimension().equals(source.getLevel().dimension()))) active = null;
            switch (action) {
                case "create" -> {
                    if (projects.size() >= ProjectStore.MAX_PROJECTS || projects.values().stream().filter(p -> p.owner().equals(actor)).count() >= 32)
                        throw new IllegalArgumentException("The project limit is 32 per player and 128 per world.");
                    if (projects.values().stream().anyMatch(p -> p.owner().equals(actor) && p.name().equalsIgnoreCase(value)))
                        throw new IllegalArgumentException("Choose a different project name.");
                    var project = new ProjectStore.Project(UUID.randomUUID().toString().replace("-", ""), actor, value,
                            source.getLevel().dimension(), ProjectTheme.EMPTY, List.of());
                    saveProject(project); playerSession(actor).activeProject = project.id();
                    send(source, Chat.info("Created project “" + project.name() + "”. New buildings and connections join it automatically."));
                }
                case "select" -> {
                    var project = projects.get(value);
                    if (project == null || !project.team(actor) || !project.dimension().equals(source.getLevel().dimension()))
                        throw new IllegalArgumentException("Choose one of your (or your team's) projects in this dimension.");
                    playerSession(actor).activeProject = value; send(source, Chat.info("Active project: “" + project.name() + "”."));
                }
                case "clear" -> { playerSession(actor).activeProject = null; send(source, Chat.info("Project selection cleared.")); }
                case "add", "remove", "style", "palette" -> {
                    if (active == null) throw new IllegalArgumentException("Select a project in this dimension first.");
                    if (action.equals("style")) saveProject(active.withTheme(new ProjectTheme(value, active.theme().palette())));
                    else if (action.equals("palette")) {
                        List<String> palette = new java.util.ArrayList<>();
                        if (value.equals("building")) {
                            BuildRecord record = selected(source).orElseThrow(() -> new IllegalArgumentException("Select a building first."));
                            if (!canModify(source, record)) throw new IllegalArgumentException("Select one of your (or your team's) buildings.");
                            palette.addAll(LocalEdits.palette(liveBlocks(record)).keySet().stream().limit(12).toList());
                        } else if (!value.equals("clear")) for (String id : value.split("[,\\s]+")) palette.add(LocalEdits.blockId(id));
                        saveProject(active.withTheme(new ProjectTheme(active.theme().style(), palette)));
                    } else {
                        BuildRecord record = selected(source).orElseThrow(() -> new IllegalArgumentException("Select a building first."));
                        if (!isAdmin(source) && !ProjectStore.mayChangeMembership(actor, record, active, action.equals("add")))
                            throw new IllegalArgumentException("Only a building's owner or an admin can share it with a project; its project's owner may also remove it.");
                        if (!record.dimension().equals(active.dimension())) throw new IllegalArgumentException("Choose a building in this project's dimension.");
                        var ids = new java.util.ArrayList<>(active.builds());
                        if (action.equals("add")) {
                            if (projects.values().stream().anyMatch(p -> p.builds().contains(record.id()) && !p.id().equals(playerSession(actor).activeProject)))
                                throw new IllegalArgumentException("Remove this building from its current project first.");
                            if (!ids.contains(record.id())) ids.add(record.id());
                        } else ids.remove(record.id());
                        saveProject(active.withBuilds(ids));
                    }
                    send(source, Chat.info("Project updated. Buildings keep their own undo histories."));
                }
                case "list" -> projects.values().stream().filter(p -> p.team(actor)).forEach(p -> send(source,
                        Chat.info(p.name() + " — " + p.id() + " — " + p.builds().size() + " buildings"
                                + (p.owner().equals(actor) ? "" : " — shared by " + playerName(p.owner())))));
                case "invite", "uninvite" -> {
                    if (active == null || !active.owner().equals(actor)) {
                        throw new IllegalArgumentException("Select one of your own projects first; only its owner changes its team.");
                    }
                    String[] player = resolvePlayer(value, action.equals("uninvite") ? active : null);
                    Map<String, String> team = new LinkedHashMap<>(active.collaborators());
                    if (action.equals("invite")) {
                        if (player[0].equals(actor)) throw new IllegalArgumentException("You already own this project.");
                        if (team.size() >= ProjectStore.MAX_COLLABORATORS && !team.containsKey(player[0])) {
                            throw new IllegalArgumentException("A project has at most " + ProjectStore.MAX_COLLABORATORS + " collaborators.");
                        }
                        team.put(player[0], player[1]);
                    } else if (team.remove(player[0]) == null) {
                        throw new IllegalArgumentException(player[1] + " isn't on this project's team.");
                    }
                    saveProject(active.withCollaborators(team));
                    tell(player[0], action.equals("invite")
                            ? playerName(actor) + " added you to the project “" + active.name() + "”: you can now change its buildings."
                            : playerName(actor) + " removed you from the project “" + active.name() + "”.");
                    send(source, Chat.info(action.equals("invite") ? player[1] + " can now change the buildings of “" + active.name() + "”."
                            : player[1] + " is no longer on the team of “" + active.name() + "”."));
                }
                case "leave" -> {
                    var project = projects.get(value.isEmpty() ? playerSession(actor).activeProject : value);
                    if (project == null || !project.collaborators().containsKey(actor)) {
                        throw new IllegalArgumentException("Choose a project you collaborate on.");
                    }
                    Map<String, String> team = new LinkedHashMap<>(project.collaborators());
                    team.remove(actor);
                    saveProject(project.withCollaborators(team));
                    if (project.id().equals(playerSession(actor).activeProject)) playerSession(actor).activeProject = null;
                    tell(project.owner(), playerName(actor) + " left the project “" + project.name() + "”.");
                    send(source, Chat.info("You left the project “" + project.name() + "”."));
                }
                default -> throw new IllegalArgumentException(
                        "Use project create, select, clear, add, remove, style, palette, invite, uninvite, leave, or list.");
            }
            sendStateTo(source);
        } catch (IOException | PlanException | IllegalArgumentException e) { send(source, Chat.error(e.getMessage())); }
    }

    public void walkability(CommandSourceStack source, boolean clear) {
        String actor = ownerOf(source);
        if (clear) { playerSession(actor).routeReport = null; sendStateTo(source); return; }
        PendingPlacement preview = playerSession(actor).preview.current; BuildRecord record = preview == null ? selected(source).orElse(null) : preview.record();
        if (record == null || !record.dimension().equals(source.getLevel().dimension())) { send(source, Chat.error("Select a building or make a preview first.")); return; }
        try {
            Map<BlockPos, BlockState> overlay = preview == null ? Map.of() : Protection.filter(preview.placements(), owned(record, preview.ours()),
                    source.getLevel()::getBlockEntity, source.getLevel()::getBlockState).placements();
            var known = preview != null && preview.isNew() ? new LinkedHashMap<BlockPos, BlockState>() : new LinkedHashMap<>(registry.current(record));
            known.putAll(overlay);
            BuildRegion bounds = BuildRegion.enclosing(record.dimension(), known.entrySet().stream().filter(e -> !e.getValue().isAir()).map(Map.Entry::getKey).toList());
            PartSelection part = playerSession(actor).part;
            if (part != null && part.region() != null && record.id().equals(part.buildId())) bounds = part.region();
            Walkability.Result result = Walkability.inspect(bounds, p -> !source.getLevel().hasChunk(p.getX() >> 4, p.getZ() >> 4) ? null
                    : overlay.getOrDefault(p, source.getLevel().getBlockState(p)));
            playerSession(actor).routeReport = new RouteReport(record.dimension(), result, Instant.now().plus(ROUTE_REPORT_TTL));
            result.lines().forEach(line -> send(source, Chat.info(line))); sendStateTo(source);
        } catch (IllegalArgumentException e) { send(source, Chat.error(e.getMessage())); }
    }

    // ---- templates / history ----

    public void saveCheckpoint(CommandSourceStack source, String name) {
        if (!canChangeZone(source)) return;
        Optional<BuildRecord> found = selected(source);
        if (found.isEmpty()) { send(source, Chat.error("Select a build first.")); return; }
        BuildRecord record = found.get();
        if (!mayModify(source, record)) return;
        if (busy.contains(record.id())) { send(source, Chat.error(busyMessage(source, record))); return; }
        try {
            record.addCheckpoint(new BuildRecord.Checkpoint(name, record.current().plan(), System.currentTimeMillis()));
            if (saveRecord(record)) send(source, Chat.info("Saved checkpoint “" + name.strip() + "” for “" + record.title() + "”."));
            else send(source, Chat.error("The checkpoint is available for this session, but could not be saved to disk."));
            sendStateTo(source);
        } catch (IllegalArgumentException e) { send(source, Chat.error(e.getMessage())); }
    }

    public void checkpoints(CommandSourceStack source) {
        Optional<BuildRecord> found = selected(source);
        if (found.isEmpty()) { send(source, Chat.error("Select a build first.")); return; }
        List<BuildRecord.Checkpoint> list = found.get().checkpoints();
        send(source, Chat.info(list.isEmpty() ? "No checkpoints yet. Use /justbuild checkpoint save <name>."
                : "Checkpoints for “" + found.get().title() + "”: "
                        + list.stream().map(BuildRecord.Checkpoint::name).collect(java.util.stream.Collectors.joining(", "))));
    }

    public void restoreCheckpoint(CommandSourceStack source, String name) {
        if (!canPlace(source) || !canChangeZone(source)) return;
        Optional<BuildRecord> found = selected(source);
        if (found.isEmpty()) { send(source, Chat.error("Select a build first.")); return; }
        BuildRecord record = found.get();
        if (!mayModify(source, record)) return;
        if (busy.contains(record.id())) { send(source, Chat.error(busyMessage(source, record))); return; }
        Optional<BuildRecord.Checkpoint> checkpoint = record.checkpoints().stream()
                .filter(c -> c.name().equalsIgnoreCase(name.strip())).findFirst();
        if (checkpoint.isEmpty()) { send(source, Chat.error("No checkpoint has that name.")); return; }
        try {
            ActiveRequest request = new ActiveRequest(ownerOf(source), new Conversation("Restore checkpoint: " + checkpoint.get().name()),
                    source.getLevel(), record.origin(), record.facing(), record, null, false, null, source);
            request.zone = zoneFor(request.actor, record.dimension());
            request.forcePreview = true;
            request.savedPlan = true;
            playerSession(request.actor).retainedPlan = null;
            playerSession(request.actor).planning.start(request);
            busy.add(record.id());
            placeEdit(request, checkpoint.get().plan());
        } catch (PlanException e) { send(source, Chat.error(e.getMessage())); }
    }

    public void previewVersion(CommandSourceStack source, String id) {
        if (!canPlace(source) || !canChangeZone(source)) return;
        BuildRecord record = selected(source).orElse(null);
        if (record == null || !mayModify(source, record)) { send(source, Chat.error("Select a building you can edit first.")); return; }
        if (busy.contains(record.id())) { send(source, Chat.error(busyMessage(source, record))); return; }
        var version = record.versions().stream().filter(v -> v.id().equals(id)).findFirst();
        if (version.isEmpty()) { send(source, Chat.error("That version is no longer retained. Refresh version history.")); return; }
        try {
            ActiveRequest request = new ActiveRequest(ownerOf(source), new Conversation("Restore version " + version.get().number()),
                    source.getLevel(), record.origin(), record.facing(), record, null, false, null, source);
            request.zone = zoneFor(request.actor, record.dimension());
            request.forcePreview = true; request.savedPlan = true;
            playerSession(request.actor).retainedPlan = null; playerSession(request.actor).planning.start(request); busy.add(record.id());
            placeEdit(request, version.get().plan());
        } catch (PlanException e) { send(source, Chat.error(e.getMessage())); }
    }

    public void saveDesign(CommandSourceStack source, String name) {
        if (!canChangeZone(source)) return;
        Optional<BuildRecord> found = selected(source);
        if (found.isEmpty()) { send(source, Chat.error("Select a build first.")); return; }
        BuildRecord record = found.get();
        if (busy.contains(record.id())) { send(source, Chat.error(busyMessage(source, record))); return; }
        String actor = ownerOf(source);
        try {
            String label = BuildRecord.label(name);
            if (designs.values().stream().filter(d -> d.owner().equals(actor)).count() >= 64) {
                throw new IllegalArgumentException("You already have 64 saved designs in this world.");
            }
            if (designs.values().stream().anyMatch(d -> d.owner().equals(actor) && d.name().equalsIgnoreCase(label))) {
                throw new IllegalArgumentException("A design already has that name. Choose a different name.");
            }
            Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
            ServerLevel world = level(record.dimension());
            for (BlockPos pos : registry.current(record).keySet()) {
                if (!world.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
                    throw new PlanException("Move close enough to load the whole build before saving a design.");
                }
                blocks.put(pos, world.getBlockState(pos));
            }
            BuildPlan snapshot = PlanSnapshot.capture(label, record.origin(), record.facing(), blocks);
            config.limits().check(snapshot);
            if (PlanWriter.write(snapshot).length() > 1_500_000) throw new PlanException("This design is too large to save as an editable plan.");
            DesignStore.Design design = new DesignStore.Design(UUID.randomUUID().toString().replace("-", ""), actor,
                    label, snapshot, System.currentTimeMillis(), "", DesignThumbnail.create(snapshot));
            designStore.save(design);
            designs.put(design.id(), design);
            send(source, Chat.info("Saved design “" + design.name() + "”. Place copies from the Designs panel without an AI call."));
            sendStateTo(source);
        } catch (IOException | IllegalArgumentException | PlanException e) { send(source, Chat.error("Could not save design: " + e.getMessage())); }
    }

    public void designs(CommandSourceStack source) {
        List<DesignStore.Design> mine = designs.values().stream().filter(d -> d.owner().equals(ownerOf(source))).toList();
        if (mine.isEmpty()) { send(source, Chat.info("No saved designs. Select a build, then /justbuild design save <name>.")); return; }
        for (DesignStore.Design d : mine) send(source, Chat.info(d.name() + " — " + d.id()));
    }

    public void designFolder(CommandSourceStack source, String id, String folder) {
        DesignStore.Design old = designs.get(id);
        if (old == null || !old.owner().equals(ownerOf(source))) { send(source, Chat.error("Choose one of your designs.")); return; }
        try {
            var changed = new DesignStore.Design(old.id(), old.owner(), old.name(), old.plan(), old.createdAt(), folder, old.thumbnail());
            designStore.save(changed); designs.put(id, changed);
            send(source, Chat.info("Moved “" + changed.name() + "” to " + (changed.folder().isEmpty() ? "Unfiled" : changed.folder()) + "."));
            sendStateTo(source);
        } catch (IOException | IllegalArgumentException e) { send(source, Chat.error("Could not move the design: " + e.getMessage())); }
    }

    public void exportDesign(CommandSourceStack source, String id) { exportDesign(source, id, "justbuild.json"); }

    public void exportDesign(CommandSourceStack source, String id, String format) {
        DesignStore.Design design = designs.get(id);
        if (design == null || !design.owner().equals(ownerOf(source))) { send(source, Chat.error("Choose one of your designs.")); return; }
        try {
            String file = designTransfer.exportDesign(design, format);
            send(source, Chat.info("Exported “" + design.name() + "” as " + file
                    + " in justbuild/library in this game's or server's folder. Other worlds can import it."));
            sendStateTo(source);
        } catch (IOException e) { send(source, Chat.error("Could not export the design: " + e.getMessage())); }
    }

    public void importDesign(CommandSourceStack source, String file) { importDesign(source, file, false); }

    public void importDesign(CommandSourceStack source, String file, boolean includeAir) {
        String actor = ownerOf(source);
        try {
            if (designs.values().stream().filter(d -> d.owner().equals(actor)).count() >= 64) {
                throw new IllegalArgumentException("You already have 64 designs in this world.");
            }
            DesignTransfer.Imported imported = designTransfer.read(file, includeAir);
            config.limits().check(imported.plan());
            var check = PlanPipeline.run(imported.plan(), BlockPos.ZERO, Direction.NORTH,
                    LevelHeightAccessor.create(-1024, 2048), p -> net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), config.maxBlocks);
            if (!check.invalid().isEmpty()) throw new PlanException("The design uses unavailable blocks: " + String.join(", ", check.invalid()));
            if (check.placements().isEmpty() || check.outsideWorld() > 0) throw new PlanException("The design has no valid complete placement.");
            if (PlanWriter.write(imported.plan()).length() > 1_500_000) throw new PlanException("The design plan is too large.");
            String base = imported.name(), name = base;
            Set<String> names = designs.values().stream().filter(d -> d.owner().equals(actor))
                    .map(d -> d.name().toLowerCase(java.util.Locale.ROOT)).collect(java.util.stream.Collectors.toSet());
            for (int suffix = 2; names.contains(name.toLowerCase(java.util.Locale.ROOT)); suffix++) {
                name = clip(base, 52) + " (" + suffix + ")";
            }
            var design = new DesignStore.Design(UUID.randomUUID().toString().replace("-", ""), actor, name, imported.plan(),
                    System.currentTimeMillis(), imported.folder(), DesignThumbnail.create(imported.plan()));
            designStore.save(design); designs.put(design.id(), design);
            send(source, Chat.info("Imported “" + name + "”. Choose it in Designs to preview a copy."));
            imported.warnings().forEach(warning -> send(source, Chat.info(warning)));
            sendStateTo(source);
        } catch (IOException | PlanException | IllegalArgumentException e) { send(source, Chat.error("Could not import the design: " + e.getMessage())); }
    }

    public void refreshLibrary(CommandSourceStack source) { sendStateTo(source); }

    public void placeDesign(CommandSourceStack source, String id, int rotation, String substitutions) {
        if (!canPlace(source) || !canChangeZone(source)) return;
        DesignStore.Design design = designs.get(id);
        if (design == null || !design.owner().equals(ownerOf(source))) {
            send(source, Chat.error("No design with that id. See /justbuild design list.")); return;
        }
        if (rotation % 90 != 0 || rotation < 0 || rotation > 270) {
            send(source, Chat.error("Rotation must be 0, 90, 180, or 270 degrees.")); return;
        }
        try {
            BuildPlan plan = PlanMaterials.apply(design.plan(), design.name(), PlanMaterials.parse(substitutions));
            String actor = ownerOf(source);
            BuildZone zone = zoneFor(actor, source.getLevel().dimension());
            Direction facing = Direction.fromYRot(source.getRotation().y + rotation);
            BlockPos origin = BlockPos.containing(source.getPosition()).relative(Direction.fromYRot(source.getRotation().y), 2);
            if (zone != null) {
                int x = zone.minX() + (zone.maxX() - zone.minX()) / 2;
                int z = zone.minZ() + (zone.maxZ() - zone.minZ()) / 2;
                if (!source.getLevel().hasChunk(x >> 4, z >> 4)) throw new PlanException("Move closer to the selected zone first.");
                origin = new BlockPos(x, Ground.standingY(source.getLevel(), x, z, zone.displayY()), z);
            } else if (source.getPlayer() != null && source.getPlayer().pick(EDIT_REACH, 0, false) instanceof BlockHitResult hit
                    && hit.getType() == HitResult.Type.BLOCK
                    && hit.getLocation().distanceTo(source.getPlayer().getEyePosition()) >= MIN_TARGET_DISTANCE) {
                origin = hit.getBlockPos().relative(hit.getDirection());
            }
            ActiveRequest request = new ActiveRequest(actor, new Conversation("Copy of saved design: " + design.name()),
                    source.getLevel(), origin, facing, null, PromptSpec.Placement.FRONT, zone != null, null, source);
            request.zone = zone;
            request.forcePreview = true;
            request.savedPlan = true;
            playerSession(actor).selectedBuild = null;
            playerSession(actor).part = null;
            playerSession(actor).retainedPlan = null;
            playerSession(actor).planning.start(request);
            placeNew(request, plan);
            sendStateTo(source);
        } catch (PlanException e) { send(source, Chat.error("Could not place design: " + e.getMessage())); }
    }

    public void templates(CommandSourceStack source, String filter) {
        send(source, Chat.templates(TemplateLibrary.defaults().filter(filter)));
    }

    public void template(CommandSourceStack source, String id) {
        Optional<TemplateLibrary.Template> template = TemplateLibrary.defaults().byId(id);
        if (template.isEmpty()) {
            send(source, Chat.error("No template “" + id + "”. See /justbuild templates."));
            return;
        }
        send(source, Chat.progress("Template: " + template.get().name() + "."));
        start(source, template.get().request());
    }

    public void history(CommandSourceStack source) {
        String actor = ownerOf(source);
        ResourceKey<Level> dimension = source.getLevel().dimension();
        List<BuildRecord> mine = registry.all().stream()
                .filter(r -> r.owner().equals(actor) && r.dimension().equals(dimension))
                .sorted(java.util.Comparator.comparingLong(BuildRecord::lastChanged).reversed())
                .limit(10).toList();
        if (mine.isEmpty()) {
            send(source, Chat.info("No AI builds of yours in this dimension yet."));
            return;
        }
        send(source, Chat.info("Your recent builds:"));
        long now = System.currentTimeMillis();
        for (BuildRecord r : mine) {
            send(source, Chat.historyLine(r.title(), r.current().number(), ago(now - r.lastChanged()), r.id()));
        }
    }

    /** Re-runs a build's original request (with a fresh creative direction) at the player's current spot. */
    public void again(CommandSourceStack source, String build) {
        String id = build.strip().startsWith("#") ? build.strip().substring(1) : build.strip();
        Optional<BuildRecord> record = registry.get(id);
        if (record.isEmpty()) {
            send(source, Chat.error("No saved build has id #" + id + "."));
            return;
        }
        // The request as typed, so a player's own AI recognizes it (PromptCheck); older builds fall back to the record.
        String typed = record.get().typedRequest();
        start(source, typed != null ? typed : EditTarget.withoutDirection(record.get().versions().get(0).request()));
    }

    private static String ago(long millis) {
        long minutes = millis / 60_000;
        if (minutes < 1) {
            return "just now";
        }
        if (minutes < 60) {
            return minutes + " min ago";
        }
        long hours = minutes / 60;
        return hours < 48 ? hours + " h ago" : hours / 24 + " days ago";
    }

    public void setDetail(CommandSourceStack source, String level) {
        Optional<Guidance.DetailLevel> parsed = Guidance.DetailLevel.parse(level);
        if (parsed.isEmpty()) {
            send(source, Chat.error("Unknown detail level “" + level + "”. Use quick, detailed, or masterpiece."));
            return;
        }
        if (!configWritable(source)) return;
        config.detail = parsed.get().id();
        if (saveConfig(source)) {
            sendStateToAll();
            send(source, Chat.info("AI review set to " + parsed.get().reviewLabel() + ". " + parsed.get().reviewHint()));
        }
    }

    public void setStyle(CommandSourceStack source, String name) {
        Optional<Guidance.Style> parsed = Guidance.Style.parse(name);
        if (parsed.isEmpty()) {
            send(source, Chat.error("Unknown style “" + name + "”. Use auto or one of: medieval, modern, futuristic, "
                    + "japanese, nordic, steampunk, rustic, fantasy."));
            return;
        }
        if (!configWritable(source)) return;
        config.style = parsed.get().id();
        if (saveConfig(source)) {
            send(source, Chat.info(parsed.get() == Guidance.Style.AUTO ? "Style set to auto: the AI picks."
                    : "Style set to " + config.style + "."));
            sendStateToAll();
        }
    }

    public void reload(CommandSourceStack source) {
        try {
            config = configStore.load();
            configLoadProblem = null;
            try {
                var loaded = regionStore.load();
                lockedAreas.clear(); lockedAreas.addAll(loaded); lockLoadProblem = null;
            } catch (IOException e) {
                lockLoadProblem = e.getMessage();
                send(source, Chat.error("Placement is disabled until locked areas can be read: " + lockLoadProblem));
            }
            try {
                var loaded = projectStore.load(); projects.clear(); loaded.forEach(p -> projects.put(p.id(), p)); projectLoadProblem = null;
                buildAccessRevision++;
                revokePlacementAccess();
            } catch (IOException e) { projectLoadProblem = e.getMessage(); send(source, Chat.error(projectLoadProblem)); }
            send(source, Chat.info("Reloaded " + configStore.file().getFileName() + "."));
            sendStateToAll();
        } catch (ConfigException e) {
            configLoadProblem = e.getMessage();
            send(source, Chat.error(e.getMessage() + " Keeping the previous settings; nothing saves until it reads."));
        }
    }

    public void status(CommandSourceStack source) {
        String actor = ownerOf(source);
        JustBuildConfig cfg = config;
        send(source, Chat.info("Profile: " + cfg.activeProfile + " (" + profileLabel(cfg.active()) + "), AI review: "
                + cfg.detailLevel().reviewLabel() + ", style: " + cfg.style + "."));
        send(source, Chat.info(aiLine(source)));
        send(source, Chat.info(selected(source).map(r -> "Editing: “" + r.title() + "” (#" + r.id() + ").")
                .orElse("New-build mode; no build selected for editing.")));
        RetainedPlan retained = playerSession(actor).retainedPlan;
        if (retained != null) send(source, Chat.info("Retained plan: “" + retained.plan().title()
                + "”. Enlarge its area, then /justbuild retry; no new AI call."));
        else if (retainedTitle(playerSession(actor)) != null) send(source, Chat.with(Chat.info("Saved preview: “"
                + retainedTitle(playerSession(actor)) + "”. Resume with fresh world checks; no AI call."),
                "Resume", "/justbuild retry", "Preview the saved options again"));
        ActiveRequest request = playerSession(actor).planning.current;
        if (request != null) {
            if (request.inFlight != null) {
                send(source, Chat.info("Waiting for the AI: “" + request.conversation.request() + "”."));
            } else if (request.conversation.hasPendingQuestion()) {
                send(source, Chat.question(request.conversation.pendingQuestion(), request.conversation.pendingOptions()));
            }
        }
        PendingPlacement preview = playerSession(actor).preview.current;
        if (preview != null) {
            send(source, Chat.preview("Waiting for you to confirm “" + preview.title() + "” ("
                    + preview.placements().size() + " blocks)."));
        }
        scheduler.active(actor).ifPresent(job -> send(source, Chat.info("Placing “" + job.title() + "”: "
                + job.placedCount() + " of " + job.total() + " blocks.")));
        send(source, Chat.info("Saved builds in this world: " + registry.all().size() + ". Undo steps: "
                + history.size(actor) + "."));
    }

    public void doctor(CommandSourceStack source) {
        doctor(source, 0, result -> send(source, result.error() ? Chat.error(result.message())
                : result.index() < 0 ? Chat.progress("Checking each profile… " + result.message()) : Chat.info(result.message())));
    }

    /** The panel gets a separate, correlated report instead of trying to collect unrelated chat messages. */
    public void doctor(ServerPlayer player, long id) {
        if (player.hasDisconnected() || !ServerPlayNetworking.canSend(player, dev.justbuild.net.DoctorPayload.TYPE)) return;
        var source = player.createCommandSourceStack();
        if (!JustBuildCommand.allowed(source)) {
            ServerPlayNetworking.send(player, new dev.justbuild.net.DoctorPayload(id, -1, 0,
                    "Only an operator or the world owner can check server profiles.", true));
            return;
        }
        doctor(source, id, result -> {
            if (player.hasDisconnected() || !ServerPlayNetworking.canSend(player, dev.justbuild.net.DoctorPayload.TYPE)) return;
            ServerPlayNetworking.send(player, JustBuildCommand.allowed(player.createCommandSourceStack()) ? result
                    : new dev.justbuild.net.DoctorPayload(id, -1, 0, "You no longer have permission to check server profiles.", true));
        });
    }

    private void doctor(CommandSourceStack source, long requestId, java.util.function.Consumer<dev.justbuild.net.DoctorPayload> reply) {
        JustBuildConfig cfg = config;
        int total = cfg.profiles.size();
        reply.accept(new dev.justbuild.net.DoctorPayload(requestId, -1, total, aiLine(source), false));
        for (int i = 0; i < total; i++) {
            int index = i;
            JustBuildConfig.ModelProfile profile = cfg.profiles.get(i);
            BuildProvider provider = Providers.create(cfg, profile);
            String id = profile.name;
            String label = profile.name + " (" + provider.displayName() + (profile.name.equals(cfg.activeProfile) ? ", active)" : ")");
            workers.execute(() -> {
                String line;
                boolean error = false;
                try {
                    line = label + ": OK, " + provider.check(new CancellationToken());
                } catch (ProviderException e) {
                    line = label + ": " + e.getMessage();
                    error = true;
                } catch (RuntimeException e) {
                    LOGGER.error("Doctor check failed for {}", id, e);
                    line = label + ": Unexpected error. Check the server log for details.";
                    error = true;
                }
                var result = new dev.justbuild.net.DoctorPayload(requestId, index, total, line, error);
                onServer(() -> reply.accept(result));
            });
        }
    }

    /** Server stopping: keep partially placed builds undoable and save everything. */
    public void shutdown() {
        versionThumbnails.close(); completedThumbnails.clear();
        profileTests.close();
        closed = true;
        relay.close();
        for (var entry : playerSessions.entrySet()) {
            PlayerSession session = entry.getValue();
            PendingPlacement p = session.preview.current;
            if (p == null && session.alternatives != null && !session.alternatives.choices.isEmpty()) p = session.alternatives.choices.getFirst();
            if (p != null) savePreview(entry.getKey(), p.source());
        }
        List.copyOf(planningRequests()).forEach(r -> endRequest(r, true));
        playerSessions.clear();
        for (PlacementJob job : scheduler.drain()) {
            JobInfo info = jobs.remove(job);
            if (info == null) {
                continue;
            }
            // A job still waiting in the queue placed nothing: keep no trace of it rather than a change that never happened.
            if (job.placedCount() == 0 && (job.kind() == PlacementJob.Kind.BUILD || job.kind() == PlacementJob.Kind.EDIT)) {
                if (job.kind() == PlacementJob.Kind.BUILD) {
                    String id = info.record().id();
                    registry.remove(id);
                    buildAccessRevision++;
                    deleteRecord(id);
                    for (var project : List.copyOf(projects.values())) if (project.builds().contains(id)) {
                        try { saveProject(project.withBuilds(project.builds().stream().filter(b -> !b.equals(id)).toList())); }
                        catch (IOException e) { LOGGER.warn("Could not update project {}: {}", project.id(), e.toString()); }
                    }
                }
                LOGGER.info("Server stopped before placing “{}”; nothing was changed", info.record().title());
                continue;
            }
            switch (job.kind()) {
                case BUILD -> {
                    info.record().recordBaseline(job.previousStates());
                    saveRecord(info.record());
                }
                case EDIT -> {
                    // Keep the partly placed edit as a version so undo can still take it back.
                    info.record().recordBaseline(job.previousStates());
                    info.record().addVersion(appliedVersion(job, info));
                    saveRecord(info.record());
                    history.push(job.owner(), entry(info.record()));
                    LOGGER.warn("Server stopped mid-edit of \"{}\"; saved it as version {} so /justbuild undo can revert it",
                            info.record().title(), info.record().current().number());
                }
                case UNDO -> history.push(job.owner(), entry(info.record()));
                case REVERT -> saveRecord(info.record());
            }
        }
        saveHistory();
        // After the saves above: waits until every queued build save is written.
        store.close();
        jobs.clear();
        busy.clear();
        relay.fail(null, "The server is stopping.");
        workers.shutdownNow();
        relayWorkers.shutdownNow();
    }

    // ---- helpers ----

    /**
     * Set as the server stops. From then on a worker's results are dropped: once the server has stopped, execute()
     * would run them on the worker thread itself, racing the shutdown.
     */
    private volatile boolean closed;

    /** Runs a worker's result on the server thread, unless the server is stopping. */
    private void onServer(Runnable task) {
        if (closed) return;
        server.execute(() -> {
            if (!closed && server.isSameThread()) task.run();
        });
    }

    /** Never a stand-in: reading or placing in the Overworld for a missing dimension would change the wrong world. */
    private ServerLevel level(ResourceKey<Level> dimension) {
        ServerLevel level = server.getLevel(dimension);
        if (level == null) throw new IllegalStateException(dimension.identifier() + " isn't loaded");
        return level;
    }

    private static String unsaved(BuildRecord record) {
        return "“" + record.title() + "” was placed, but its record couldn't be saved (is the disk full?). Undo works "
                + "until the server restarts; after that it may not.";
    }

    /**
     * Queues the record's save; its snapshot is taken now and written off the server thread. A failed write is logged
     * and reported to the owner when it happens, and the build's next save tries again. False only when the store no
     * longer takes saves (the server is stopping).
     */
    private boolean saveRecord(BuildRecord record) {
        buildAccessRevision++;
        String id = record.id(), owner = record.owner(), title = record.title();
        boolean queued = store.saveLater(record, e -> {
            LOGGER.warn("Could not save build {}: {}", id, e.toString());
            onServer(() -> warn(owner, "“" + title + "” couldn't be saved to disk (" + e.getMessage() + "). It stays as it "
                    + "is in the game, and its next change saves it again."));
        });
        if (!queued) LOGGER.warn("Could not save build {}: the build store is closed", id);
        return queued;
    }

    /** Writes the record before returning, for a caller that acts only once the build is saved. */
    private boolean saveRecordNow(BuildRecord record) {
        buildAccessRevision++;
        try {
            store.save(record);
            return true;
        } catch (IOException e) {
            LOGGER.warn("Could not save build {}: {}", record.id(), e.toString());
            return false;
        }
    }

    private void deleteRecord(String id) {
        try {
            store.delete(id);
        } catch (IOException e) {
            LOGGER.warn("Could not delete build {}: {}", id, e.toString());
        }
    }

    private void saveHistory() {
        if (!historyWritable) return;
        Map<String, Set<String>> referenced = history.referencedVersions();
        // In-flight jobs hold placement maps and may be cancelled; never compact underneath them.
        for (BuildRecord record : registry.all()) {
            if (!busy.contains(record.id()) && record.trimVersions(referenced.getOrDefault(record.id(), Set.of()), 32)) {
                saveRecord(record);
            }
        }
        try {
            history.save(historyFile);
        } catch (IOException e) {
            LOGGER.warn("Could not save undo history: {}", e.toString());
        }
    }

    /**
     * The config file couldn't be read at startup or on the last reload. Settings then run on defaults (or the previous
     * ones), and saving would replace the file, with its profiles and keys, by those: so nothing saves until a reload
     * reads it.
     */
    private String configLoadProblem;

    public void configLoadProblem(String problem) {
        configLoadProblem = problem;
    }

    private boolean configWritable(CommandSourceStack source) {
        if (configLoadProblem == null) return true;
        send(source, Chat.error("Settings weren't changed: " + configStore.file().getFileName() + " couldn't be read ("
                + configLoadProblem + "). Fix the file, then run /justbuild reload; saving now would replace it."));
        return false;
    }

    private boolean saveConfig(CommandSourceStack source) {
        if (!configWritable(source)) return false;
        try {
            configStore.save(config);
            return true;
        } catch (ConfigException e) {
            send(source, Chat.error(e.getMessage()));
            return false;
        }
    }

    private static String modelLabel(JustBuildConfig.ModelProfile profile) {
        if (!profile.model.isBlank()) {
            return profile.model;
        }
        return profile.isApi() ? "no model set" : "CLI default";
    }

    /** "Claude CLI, opus" or "OpenAI-compatible, gpt-x at https://…". */
    static String profileLabel(JustBuildConfig.ModelProfile profile) {
        String label = Providers.displayName(profile.provider) + ", " + modelLabel(profile);
        return profile.isApi() ? label + " at " + profile.baseUrl : label;
    }

    /** Console and RCON sources drop late messages, so mirror them to the log. */
    private static void send(CommandSourceStack source, Component message) {
        source.sendSystemMessage(message);
        if (source.getPlayer() == null) {
            LOGGER.info(message.getString());
        }
    }

    private void savePlan(String raw, String title) {
        try {
            planStore.save(raw, title);
        } catch (IOException e) {
            LOGGER.warn("Could not save plan", e);
        }
    }

    private void saveInvalid(String raw) {
        if (raw == null) {
            return;
        }
        try {
            LOGGER.warn("Saved invalid AI response to {}", planStore.saveInvalid(raw));
        } catch (IOException e) {
            LOGGER.warn("Could not save invalid response", e);
        }
    }
}
