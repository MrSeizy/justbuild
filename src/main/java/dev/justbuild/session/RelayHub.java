package dev.justbuild.session;

import dev.justbuild.net.AiCancelPayload;
import dev.justbuild.net.AiProgressPayload;
import dev.justbuild.net.AiRequestPayload;
import dev.justbuild.net.AiResponsePayload;
import dev.justbuild.plan.PlanParser;
import dev.justbuild.provider.CancellationToken;
import dev.justbuild.provider.CancelledException;
import dev.justbuild.provider.IdleTimeout;
import dev.justbuild.provider.ProgressListener;
import dev.justbuild.provider.ProviderException;
import dev.justbuild.relay.RelayJson;
import java.util.Map;
import java.util.UUID;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * The server's side of AI calls made in a player's game: sends each request, waits for the answer, and accepts it
 * only from the player it was sent to.
 */
final class RelayHub {
    /** Extra inactivity allowance for transport and response normalization. */
    static final int GRACE_SECONDS = 30;

    private record Pending(UUID player, CompletableFuture<String> result, ProgressListener progress, IdleTimeout idle) {
    }

    private final MinecraftServer server;
    private final AtomicInteger ids = new AtomicInteger();
    private final Map<Integer, Pending> pending = new ConcurrentHashMap<>();

    RelayHub(MinecraftServer server) {
        this.server = server;
    }

    /** Blocks (off the server thread) until the player's game answers with the raw plan text. */
    String call(UUID player, RelayJson.Request request, CancellationToken token, ProgressListener progress)
            throws ProviderException {
        token.throwIfCancelled();
        if (closed) throw new ProviderException("The server is stopping.");
        int id = ids.incrementAndGet();
        Pending p = new Pending(player, new CompletableFuture<>(), progress,
                new IdleTimeout(Duration.ofSeconds(request.timeoutSeconds() + GRACE_SECONDS)));
        pending.put(id, p);
        String json = RelayJson.toJson(request);
        server.execute(() -> {
            ServerPlayer target = closed || !server.isSameThread() ? null : server.getPlayerList().getPlayer(player);
            if (target == null || !ServerPlayNetworking.canSend(target, AiRequestPayload.TYPE)) {
                p.result().completeExceptionally(new ProviderException("You are no longer connected."));
                return;
            }
            ServerPlayNetworking.send(target, new AiRequestPayload(id, json));
        });
        token.onCancel(() -> {
            p.result().cancel(false);
            cancelInGame(player, id);
        });
        try {
            return p.idle().await(p.result());
        } catch (CancellationException e) {
            throw new CancelledException();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancelInGame(player, id);
            throw new CancelledException();
        } catch (TimeoutException e) {
            cancelInGame(player, id);
            throw new ProviderException("Your game sent no AI progress or response for "
                    + (request.timeoutSeconds() + GRACE_SECONDS) + " s.");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof ProviderException pe) {
                throw pe;
            }
            throw new ProviderException("Your game could not plan it: " + e.getCause());
        } finally {
            pending.remove(id);
        }
    }

    /** Server thread: an answer, ignored unless it is for a request sent to this player. */
    void onResponse(ServerPlayer from, AiResponsePayload response) {
        Pending p = pending.get(response.id());
        if (p == null || !p.player().equals(from.getUUID())) {
            return;
        }
        p.progress().tokens(Math.max(0, response.tokensIn()), Math.max(0, response.tokensOut()));
        if (!response.error().isEmpty()) {
            String error = PlanParser.clean(response.error(), 300);
            p.result().completeExceptionally(new ProviderException("Your game: " + (error.isEmpty() ? "failed" : error)));
        } else {
            p.result().complete(response.text());
        }
    }

    /** Server thread: live progress, from the same player only. */
    void onProgress(ServerPlayer from, AiProgressPayload update) {
        Pending p = pending.get(update.id());
        if (p == null || !p.player().equals(from.getUUID())) {
            return;
        }
        p.idle().activity();
        p.progress().activity();
        ProgressListener.Phase[] phases = ProgressListener.Phase.values();
        p.progress().phase(phases[Math.max(0, Math.min(phases.length - 1, update.phase()))]);
        p.progress().operations(Math.max(0, update.operations()));
        String note = PlanParser.clean(update.note(), 60);
        if (!note.isEmpty()) {
            p.progress().note(note);
        }
    }

    /** The player left or the server is stopping: fail their calls. */
    /** As the server stops (after it stops, execute() would run tasks on the calling worker thread). */
    private volatile boolean closed;

    void close() {
        closed = true;
    }

    void fail(UUID player, String message) {
        pending.values().stream().filter(p -> player == null || p.player().equals(player))
                .forEach(p -> p.result().completeExceptionally(new ProviderException(message)));
    }

    private void cancelInGame(UUID player, int id) {
        server.execute(() -> {
            if (closed || !server.isSameThread()) return;
            ServerPlayer target = server.getPlayerList().getPlayer(player);
            if (target != null && ServerPlayNetworking.canSend(target, AiCancelPayload.TYPE)) {
                ServerPlayNetworking.send(target, new AiCancelPayload(id));
            }
        });
    }
}
