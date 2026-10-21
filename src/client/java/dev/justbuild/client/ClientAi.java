package dev.justbuild.client;

import dev.justbuild.JustBuildMod;
import dev.justbuild.command.Chat;
import dev.justbuild.config.JustBuildConfig;
import dev.justbuild.net.AiCancelPayload;
import dev.justbuild.net.AiProgressPayload;
import dev.justbuild.net.AiRequestPayload;
import dev.justbuild.net.AiResponsePayload;
import dev.justbuild.net.ClientAiPayload;
import dev.justbuild.plan.BuildPlanSchema;
import dev.justbuild.plan.PlanException;
import dev.justbuild.plan.PlanLimits;
import dev.justbuild.plan.PlanParser;
import dev.justbuild.prompt.Guidance;
import dev.justbuild.provider.BuildProvider;
import dev.justbuild.provider.CancellationToken;
import dev.justbuild.provider.CancelledException;
import dev.justbuild.provider.PlanRequest;
import dev.justbuild.provider.ProgressListener;
import dev.justbuild.provider.ProviderException;
import dev.justbuild.provider.Providers;
import dev.justbuild.relay.PromptCheck;
import dev.justbuild.relay.RelayGate;
import dev.justbuild.relay.RelayJson;
import dev.justbuild.relay.RelayOutput;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;

/**
 * Plans builds for a server with this computer's own AI, guarding the player's subscription against the server:
 * <ul>
 * <li>only on servers the player turned "Use my AI here" on for;</li>
 * <li>only right after the player started a build, edit, or answer there (RelayGate), at most 30 calls an hour;</li>
 * <li>the request arrives as data and is checked against what this game saw the player type and its own AI ask
 * (PromptCheck); this game writes the system prompt, request text, and schema itself;</li>
 * <li>only a re-written plan (real block ids), question, or decline goes back (RelayOutput), never the AI's raw text,
 * its reasoning, or the details of a provider error;</li>
 * <li>one request at a time, checked and drawn off the game's thread.</li>
 * </ul>
 */
final class ClientAi {
    private static final RelayGate GATE = new RelayGate();
    private static volatile dev.justbuild.provider.UsageTracker usage = new dev.justbuild.provider.UsageTracker();
    static dev.justbuild.provider.UsageTracker.Snapshot usage() { return usage.snapshot(); }
    static void limits(dev.justbuild.provider.UsageTracker.Limits value) { usage.limits(value); }
    static void resetUsage() {
        if (!running.isEmpty()) throw new IllegalStateException("Finish or cancel the current request before resetting usage.");
        usage.reset();
    }
    private static final Map<Integer, Work> running = new ConcurrentHashMap<>();
    private static final AtomicReference<ClientPacketListener> connection = new AtomicReference<>();

    /** Identity, not just the server's reusable request id, owns every queued callback. */
    private static final class Work {
        final int id;
        final ClientPacketListener connection = Minecraft.getInstance().getConnection();
        final CancellationToken token = new CancellationToken();
        final boolean review;
        final dev.justbuild.provider.UsageTracker accounting = usage;
        ResponseReviewScreen screen;

        Work(int id, boolean review) { this.id = id; this.review = review; }
    }
    /** Notice keys already shown (plan-limit warnings are about the player's own subscription, so they stay here). */
    private static final java.util.Set<String> noticesShown = ConcurrentHashMap.newKeySet();

    private ClientAi() {
    }

    /** Called only when this client sends the text the player submitted in the request editor. */
    static void editorConsent(dev.justbuild.net.BuildRequestPayload request) {
        RelaySeen.command(request.consentCommand());
        GATE.command(request.consentCommand(), Instant.now());
    }

    static void register() {
        ClientSendMessageEvents.COMMAND.register(command -> {
            RelaySeen.command(command);
            GATE.command(command, Instant.now());
        });
        ClientSendMessageEvents.CHAT.register(message -> {
            if (GATE.awaitingAnswer()) {
                RelaySeen.answer(message);
            }
            GATE.chat(Instant.now());
        });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            connection.set(handler);
            usage = new dev.justbuild.provider.UsageTracker();
            GATE.reset();
            RelaySeen.clear();
            // A proxy can move the player to another server without a disconnect; nothing planned for the last one goes on.
            clear();
            sendHello();
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            // Transfer may disconnect on a Netty thread. Revoke immediately; touch screens/state only on the client.
            connection.compareAndSet(handler, null);
            running.values().stream().filter(work -> work.connection == handler).forEach(work -> work.token.cancel());
            client.execute(() -> {
                if (connection.get() == null) {
                    GATE.reset();
                    RelaySeen.clear();
                }
                running.values().stream().filter(work -> work.connection == handler).toList()
                        .forEach(work -> discard(work, false));
            });
        });
        ClientPlayNetworking.registerGlobalReceiver(AiRequestPayload.TYPE,
                (payload, context) -> context.client().execute(() -> {
                    if (connection.get() == context.player().connection) handle(payload);
                }));
        // Same thread as requests, so a cancel never overtakes the request it is for.
        ClientPlayNetworking.registerGlobalReceiver(AiCancelPayload.TYPE, (payload, context) -> context.client().execute(() -> {
            if (connection.get() != context.player().connection) return;
            Work work = running.get(payload.id());
            if (work != null) discard(work, true);
        }));
    }

    /** Tells the server whether this game can plan its builds (after joining and after any profile change). */
    static void sendHello() {
        if (Minecraft.getInstance().getConnection() != null && ClientPlayNetworking.canSend(ClientAiPayload.TYPE)) {
            ClientPlayNetworking.send(new ClientAiPayload(RelayJson.toJson(LocalProfiles.hello())));
        }
    }

    private static void handle(AiRequestPayload payload) {
        int id = payload.id();
        JustBuildConfig config = LocalProfiles.load();
        if (!LocalProfiles.allowedHere(config)) {
            respond(id, "", "“Use my AI here” is off for this server in the player's game.");
            return;
        }
        if (!running.isEmpty()) {
            respond(id, "", "the player's game is already planning a build for this server.");
            return;
        }
        if (!GATE.allow(Instant.now())) {
            say("Declined an AI request from this server that you didn't start.");
            respond(id, "", "the player's game only plans builds the player just asked for.");
            return;
        }
        PromptCheck.Seen seen = RelaySeen.snapshot();
        boolean first = GATE.lastWasFirst();
        JustBuildConfig.ModelProfile profile = config.active().copy();
        Work work = new Work(id, config.reviewAiResponses);
        running.put(id, work);
        Thread.ofVirtual().name("JustBuild planner").start(() -> plan(work, payload.json(), seen, config, profile, first));
    }

    /** Off the game's thread: checking a server's request can mean drawing its plan, and the AI call takes minutes. */
    private static void plan(Work work, String requestJson, PromptCheck.Seen seen, JustBuildConfig config,
            JustBuildConfig.ModelProfile profile, boolean first) {
        String text = "";
        String error = "";
        RelayOutput output = null;
        Progress progress = new Progress(work);
        try {
            work.token.throwIfCancelled();
            RelayJson.Request request = RelayJson.parseRequest(requestJson);
            PromptCheck.Result checked = PromptCheck.check(request.spec(), seen);
            String content;
            try {
                if (checked.problem() != null) {
                    throw new PlanException(checked.problem());
                }
                content = checked.spec().userContent();
            } catch (PlanException e) {
                JustBuildMod.LOGGER.warn("Declined an AI request from the server: {}", e.getMessage());
                sayLater("Declined an AI request from this server: " + e.getMessage() + ".");
                error = "the player's game refused the request: " + e.getMessage() + ".";
                return;
            }
            BuildProvider provider = Providers.create(config, profile);
            JustBuildMod.LOGGER.info("Planning for the server with your profile {} ({})", profile.name, provider.displayName());
            if (first) {
                sayLater("Planning “" + checked.spec().request() + "” with your " + LocalProfiles.label(profile)
                        + " for this server.");
            }
            Guidance.DetailLevel detail = Guidance.DetailLevel.parse(request.detail()).orElse(Guidance.DetailLevel.DETAILED);
            PlanRequest planRequest = new PlanRequest(
                    checked.spec().systemPrompt(
                            new PlanLimits(request.maxOperations(), request.maxBlocks(), request.maxSize()), detail),
                    content, BuildPlanSchema.JSON, profile.model, Duration.ofSeconds(request.timeoutSeconds()), checked.spec());
            // Only a re-written plan, question, or decline goes back, never the AI's raw text.
            work.token.throwIfCancelled();
            RelayOutput out;
            try (var call = work.accounting.begin(checked.spec().kind() == dev.justbuild.relay.PromptSpec.Kind.REVIEW, progress)) {
                out = RelayOutput.normalize(provider.complete(planRequest, work.token, call));
            }
            output = out;
            text = out.json();
            if (out.localMessage() != null) sayLater(out.localMessage());
            if (text.length() > AiResponsePayload.MAX_CHARS) {
                text = "";
                error = "the plan is too large to send.";
            }
        } catch (CancelledException e) {
            text = null;
        } catch (RelayJson.RelayException e) {
            error = e.getMessage();
        } catch (PlanException e) {
            // Parse failures can quote raw AI output (unknown kinds, operations, and styles). Keep that local too.
            sayLater("Your AI didn't return a usable plan: "
                    + PlanParser.clean(e.getCause() == null ? e.getMessage() : e.getCause().getMessage(), 400));
            error = "the player's AI didn't return a usable plan; the details are shown in their game.";
        } catch (ProviderException e) {
            // The details (paths, endpoints, account messages) stay in this game.
            sayLater("Your AI couldn't plan this: " + e.getMessage());
            error = "the player's AI couldn't finish; the details are shown in their game.";
        } catch (RuntimeException e) {
            JustBuildMod.LOGGER.error("Planning for the server failed", e);
            sayLater("Planning for the server failed: " + e);
            error = "the player's game hit an error; the details are in its log.";
        } finally {
            if (text != null) {
                AiResponsePayload response = new AiResponsePayload(work.id, text, error, progress.tokensIn, progress.tokensOut);
                RelayOutput result = error.isEmpty() ? output : null;
                Minecraft.getInstance().execute(() -> ready(work, response, result));
            } else {
                Minecraft.getInstance().execute(() -> discard(work, true));
            }
        }
    }

    private static void respond(int id, String text, String error) {
        // Early refusals run on the receiving connection's client thread and contain no provider output.
        if (ClientPlayNetworking.canSend(AiResponsePayload.TYPE)) {
            ClientPlayNetworking.send(new AiResponsePayload(id, text, error, 0, 0));
        }
    }

    private static boolean current(Work work) {
        return running.get(work.id) == work && !work.token.isCancelled()
                && connection.get() == work.connection
                && Minecraft.getInstance().getConnection() == work.connection;
    }

    private static void ready(Work work, AiResponsePayload response, RelayOutput output) {
        if (!current(work)) return;
        JustBuildConfig config = LocalProfiles.load();
        if (!LocalProfiles.allowedHere(config)) {
            cancel(work);
        } else if ((work.review || config.reviewAiResponses)
                && (output != null || response.tokensIn() != 0 || response.tokensOut() != 0)) {
            Minecraft mc = Minecraft.getInstance();
            work.screen = new ResponseReviewScreen(mc.gui.screen(), LocalProfiles.serverKey(), response,
                    () -> send(work, response, output), () -> cancel(work));
            mc.gui.setScreen(work.screen);
        } else {
            send(work, response, output);
        }
    }

    private static void send(Work work, AiResponsePayload response, RelayOutput output) {
        if (!current(work)) return;
        if (!LocalProfiles.allowedHere(LocalProfiles.load())) {
            cancel(work);
            return;
        }
        if (ClientPlayNetworking.canSend(AiResponsePayload.TYPE)) {
            ClientPlayNetworking.send(response);
            // Only sent responses authorize follow-up questions and plan reviews.
            if (output != null && output.question() != null) {
                RelaySeen.question(output.question());
                GATE.questionAsked();
            } else if (output != null && output.planKey() != null) {
                RelaySeen.plan(output.planKey());
            }
        }
        discard(work, true);
    }

    private static void cancel(Work work) {
        if (current(work)) respond(work.id, "", "the player cancelled sending this AI response.");
        discard(work, true);
    }

    private static void discard(Work work, boolean restoreParent) {
        running.remove(work.id, work);
        work.token.cancel();
        if (work.screen != null) work.screen.dismiss(restoreParent);
    }

    private static void clear() {
        for (Work work : running.values()) discard(work, false);
        running.clear();
    }

    private static void sayLater(String text) {
        Minecraft.getInstance().execute(() -> say(text));
    }

    /** A chat line from this game itself (not the server), so the player can trust it. */
    private static void say(String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            var line = Chat.progress("(your game) " + text);
            mc.player.sendSystemMessage(line);
            ClientState.addLine(line);
        }
    }

    /**
     * Forwards progress to the server at most four times a second, and always on a phase change; keeps the token
     * count for the answer and shows plan-limit notices in this game only. The AI's reasoning headlines are not
     * forwarded: they are the AI's own text, outside what RelayOutput lets through.
     */
    private static final class Progress implements ProgressListener {
        private final Work work;
        private volatile Phase phase = Phase.WAITING;
        private volatile int operations;
        private volatile long lastSent;
        private volatile long tokensIn;
        private volatile long tokensOut;

        Progress(Work work) {
            this.work = work;
        }

        @Override
        public void phase(Phase value) {
            boolean changed = value != phase;
            phase = value;
            send(changed);
        }

        @Override
        public void operations(int count) {
            operations = count;
            send(false);
        }

        @Override
        public void activity() {
            send(false);
        }

        @Override
        public void note(String headline) {
        }

        @Override
        public synchronized void tokens(long input, long output) {
            tokensIn += Math.max(0, input);
            tokensOut += Math.max(0, output);
        }

        @Override
        public void notice(String key, String message) {
            if (noticesShown.add(key)) {
                Minecraft.getInstance().execute(() -> say(message));
            }
        }

        private synchronized void send(boolean force) {
            long now = System.nanoTime();
            if (!force && lastSent != 0 && now - lastSent < 250_000_000L) {
                return;
            }
            lastSent = now;
            AiProgressPayload update = new AiProgressPayload(work.id, phase.ordinal(), operations, "");
            Minecraft.getInstance().execute(() -> {
                if (current(work) && ClientPlayNetworking.canSend(AiProgressPayload.TYPE)) {
                    GATE.activity(Instant.now());
                    ClientPlayNetworking.send(update);
                }
            });
        }
    }
}
