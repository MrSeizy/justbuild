package dev.justbuild.client;

import dev.justbuild.config.ProfileEdits;
import dev.justbuild.net.ProfileTestPayload;
import dev.justbuild.net.ProfileTestRequestPayload;
import dev.justbuild.provider.CancellationToken;
import dev.justbuild.provider.CancelledException;
import dev.justbuild.provider.ProfileDiagnostics;
import dev.justbuild.provider.ProfileDiagnostics.Check;
import dev.justbuild.provider.ProfileDiagnostics.Status;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Tests one frozen unsaved draft. Closing cancels; stale replies cannot land in a later screen/connection. */
final class ProfileTestScreen extends FormScreen {
    private static final AtomicLong IDS = new AtomicLong();
    private final long id = IDS.incrementAndGet();
    private final Screen parent;
    private final ProfileEdits.Edit draft;
    private final boolean local;
    private final Object connection = Minecraft.getInstance().getConnection();
    private final CancellationToken token = new CancellationToken();
    private List<Check> checks;
    private boolean started;
    private int page, left, panelWidth;
    ProfileTestScreen(Screen parent, ProfileEdits.Edit draft, boolean local) {
        super(Component.literal("JustBuild · Test connection"));
        this.parent = parent; this.draft = draft; this.local = local;
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override protected void init() {
        panelWidth = Math.min(width - 20, 440); left = (width - panelWidth) / 2;
        if (!started) { started = true; start(); }
        if (checks != null && checks.size() > 1) {
            var prev = addRenderableWidget(GlassButton.builder(Component.literal("Previous check"), b -> { page--; rebuildWidgets(); })
                    .bounds(left, height - 50, (panelWidth - 4) / 2, 20).build()); prev.active = page > 0;
            var next = addRenderableWidget(GlassButton.builder(Component.literal("Next check"), b -> { page++; rebuildWidgets(); })
                    .bounds(left + (panelWidth + 4) / 2, height - 50, (panelWidth - 4) / 2, 20).build()); next.active = page < checks.size() - 1;
        }
        addRenderableWidget(GlassButton.builder(Component.literal(checks == null ? "Cancel test" : "Back to profiles"), b -> onClose())
                .bounds(left, height - 26, (panelWidth - 4) / 2, 20).build());
        var retry = addRenderableWidget(GlassButton.builder(Component.literal("Test again"), b -> minecraft.setScreen(new ProfileTestScreen(parent, draft, local)))
                .bounds(left + (panelWidth + 4) / 2, height - 26, (panelWidth - 4) / 2, 20)
                .tooltip(Tooltip.create(Component.literal("Send another tiny request; it may use paid tokens."))).build());
        retry.active = checks != null && connection == minecraft.getConnection();
    }
    private void start() {
        if (!local) {
            if (connection != null && ClientPlayNetworking.canSend(ProfileTestRequestPayload.TYPE))
                ClientPlayNetworking.send(new ProfileTestRequestPayload(id, ProfileEdits.toJson(draft), false));
            else checks = List.of(new Check(Status.FAIL, "Connection unavailable", "Reconnect to a server that supports profile tests, then reopen Profiles."));
            return;
        }
        CompletableFuture.delayedExecutor(45, TimeUnit.SECONDS).execute(token::cancel);
        Thread.ofVirtual().name("JustBuild local profile test").start(() -> {
            List<Check> result;
            try {
                var config = LocalProfiles.store().load();
                var profile = ProfileEdits.resolve(config.profiles, draft);
                result = ProfileDiagnostics.run(config, profile, token);
            } catch (CancelledException e) { result = List.of(new Check(Status.FAIL, "Response timeout", "The test stopped after its deadline. Check the service and try again.")); }
            catch (dev.justbuild.config.ConfigException e) { result = List.of(ProfileDiagnostics.invalidDraft()); }
            catch (RuntimeException e) { result = List.of(ProfileDiagnostics.unexpected()); }
            var reply = result;
            Minecraft.getInstance().execute(() -> {
                if (Minecraft.getInstance().screen == this && connection == Minecraft.getInstance().getConnection()) accept(reply);
            });
        });
    }
    static void receive(ProfileTestPayload payload) {
        var mc = Minecraft.getInstance();
        if (mc.screen instanceof ProfileTestScreen s && !s.local && s.id == payload.id() && s.connection == mc.getConnection())
            s.accept(payload.checks());
    }
    private void accept(List<Check> result) {
        checks = result.isEmpty() ? List.of(ProfileDiagnostics.unexpected()) : List.copyOf(result);
        page = checks.size() - 1; rebuildWidgets();
    }
    @Override public void removed() {
        token.cancel();
        if (!local && checks == null && connection == minecraft.getConnection() && ClientPlayNetworking.canSend(ProfileTestRequestPayload.TYPE))
            ClientPlayNetworking.send(new ProfileTestRequestPayload(id, "", true));
        super.removed();
    }
    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float tick) { Glass.sheet(g, left, panelWidth, height); }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float tick) {
        super.extractRenderState(g, mx, my, tick);
        g.centeredText(font, title, width / 2, 8, Glass.TITLE);
        draw(g, local ? "Testing the draft on this computer." : "Testing the draft on the server.", 28, 0xFFAAAAAA);
        if (checks == null) { draw(g, "Checking access and requesting a tiny JSON response…", 58, 0xFFFFFFFF); return; }
        long failed = checks.stream().filter(c -> c.status() == Status.FAIL).count();
        draw(g, failed > 0 ? "Test needs attention. Your draft has not been saved."
                : "Test complete. Your draft has not been saved.", 52, failed > 0 ? 0xFFFFAAAA : 0xFFAAFFAA);
        Check check = checks.get(page);
        int color = check.status() == Status.PASS ? 0xFFAAFFAA : check.status() == Status.WARN ? 0xFFFFFFAA : 0xFFFFAAAA;
        draw(g, (page + 1) + "/" + checks.size() + " · " + check.status() + " · " + check.title(), 88, color);
        draw(g, check.detail(), 118, 0xFFFFFFFF);
    }
    private void draw(GuiGraphicsExtractor g, String text, int y, int color) {
        for (var line : font.split(Component.literal(text), panelWidth - 12)) {
            if (y + font.lineHeight > height - 56) break;
            g.text(font, line, left + 6, y, color); y += font.lineHeight + 2;
        }
    }
}
