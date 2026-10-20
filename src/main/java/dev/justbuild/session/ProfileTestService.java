package dev.justbuild.session;

import dev.justbuild.command.JustBuildCommand;
import dev.justbuild.config.JustBuildConfig;
import dev.justbuild.config.ConfigException;
import dev.justbuild.config.ProfileEdits;
import dev.justbuild.net.ProfileTestPayload;
import dev.justbuild.net.ProfileTestRequestPayload;
import dev.justbuild.provider.CancellationToken;
import dev.justbuild.provider.CancelledException;
import dev.justbuild.provider.ProfileDiagnostics;
import dev.justbuild.provider.ProfileDiagnostics.Check;
import dev.justbuild.provider.ProfileDiagnostics.Status;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

/** Server-thread ownership of bounded, cancellable diagnostic jobs. Workers receive immutable profile copies. */
final class ProfileTestService {
    private record Work(ServerPlayer player, long id, CancellationToken token) {}
    private final Map<UUID, Work> running = new HashMap<>();
    private final Semaphore slots = new Semaphore(4);
    private final Consumer<Runnable> onServer;
    private boolean closed;
    ProfileTestService(Consumer<Runnable> onServer) { this.onServer = onServer; }
    void request(ServerPlayer player, ProfileTestRequestPayload request, JustBuildConfig config) {
        if (closed || player.hasDisconnected()) return;
        Work old = running.get(player.getUUID());
        if (request.cancel()) {
            if (old != null && old.player == player && old.id == request.id()) cancel(player);
            return;
        }
        if (!ServerPlayNetworking.canSend(player, ProfileTestPayload.TYPE)) return;
        if (!JustBuildCommand.allowed(player.createCommandSourceStack())) {
            send(player, request.id(), List.of(new Check(Status.FAIL, "Profile ownership", "Only an operator or the world owner can test server profiles.")));
            return;
        }
        cancel(player);
        JustBuildConfig.ModelProfile profile;
        try { profile = ProfileEdits.resolve(config.profiles, ProfileEdits.parseEdit(request.json())); }
        catch (ConfigException | RuntimeException e) { send(player, request.id(), List.of(ProfileDiagnostics.invalidDraft())); return; }
        if (!slots.tryAcquire()) {
            send(player, request.id(), List.of(new Check(Status.WARN, "Server busy", "Other connection tests are still running. Retry in a moment.")));
            return;
        }
        var settings = JustBuildConfig.defaults();
        settings.claude.command = config.claude.command; settings.codex.command = config.codex.command;
        settings.grok.command = config.grok.command;
        var work = new Work(player, request.id(), new CancellationToken());
        running.put(player.getUUID(), work);
        CompletableFuture.delayedExecutor(45, TimeUnit.SECONDS).execute(work.token::cancel);
        Thread.ofVirtual().name("JustBuild profile test").start(() -> {
            List<Check> result;
            try { result = ProfileDiagnostics.run(settings, profile, work.token); }
            catch (CancelledException e) { result = List.of(new Check(Status.FAIL, "Response timeout", "The check stopped after its deadline. Verify the service is running and try again.")); }
            catch (RuntimeException e) { result = List.of(ProfileDiagnostics.unexpected()); }
            finally { slots.release(); }
            var reply = result;
            onServer.accept(() -> {
                if (running.get(player.getUUID()) != work) return;
                running.remove(player.getUUID());
                if (!closed && !player.hasDisconnected()) send(player, work.id, reply);
            });
        });
    }
    private void send(ServerPlayer player, long id, List<Check> checks) {
        if (ServerPlayNetworking.canSend(player, ProfileTestPayload.TYPE)) ServerPlayNetworking.send(player, new ProfileTestPayload(id, checks));
    }
    void cancel(ServerPlayer player) {
        Work work = running.get(player.getUUID());
        if (work != null && work.player == player) { running.remove(player.getUUID()); work.token.cancel(); }
    }
    void close() { closed = true; running.values().forEach(w -> w.token.cancel()); running.clear(); }
}
