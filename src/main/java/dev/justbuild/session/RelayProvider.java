package dev.justbuild.session;

import dev.justbuild.plan.PlanLimits;
import dev.justbuild.prompt.Guidance;
import dev.justbuild.provider.BuildProvider;
import dev.justbuild.provider.CancellationToken;
import dev.justbuild.provider.PlanRequest;
import dev.justbuild.provider.ProgressListener;
import dev.justbuild.provider.ProviderException;
import dev.justbuild.relay.RelayJson;
import java.util.List;
import java.util.UUID;

/**
 * A player's own AI, reached through their game. The request goes as data (relay.PromptSpec) with the numbers the
 * system prompt states: the game checks it and writes the system prompt and request text itself, so the server
 * cannot give the player's AI other instructions.
 */
final class RelayProvider implements BuildProvider {
    private final RelayHub hub;
    private final UUID player;
    private final String label;
    private final PlanLimits limits;
    private final Guidance.DetailLevel detail;

    RelayProvider(RelayHub hub, UUID player, String label, PlanLimits limits, Guidance.DetailLevel detail) {
        this.hub = hub;
        this.player = player;
        this.label = label;
        this.limits = limits;
        this.detail = detail;
    }

    @Override
    public String id() {
        return "relay";
    }

    @Override
    public String displayName() {
        return "your " + (label.isBlank() ? "own AI" : label);
    }

    @Override
    public String complete(PlanRequest request, CancellationToken token, ProgressListener progress)
            throws ProviderException {
        if (request.spec() == null) {
            throw new ProviderException("Internal error: this request can't be sent to a player's game.");
        }
        RelayJson.Request relayed = new RelayJson.Request(request.spec(), detail.id(), limits.maxOperations(),
                limits.maxBlocks(), limits.maxSize(), (int) Math.min(RelayJson.MAX_TIMEOUT,
                        Math.max(RelayJson.MIN_TIMEOUT, request.timeout().toSeconds())));
        return hub.call(player, relayed, token, progress);
    }

    @Override
    public String check(CancellationToken token) {
        return "plans in the player's own game";
    }

    @Override
    public List<String> models(CancellationToken token) {
        return List.of();
    }
}
