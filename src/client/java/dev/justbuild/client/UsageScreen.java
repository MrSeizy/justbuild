package dev.justbuild.client;

import dev.justbuild.provider.UsageTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class UsageScreen extends ToolScreen {
    private boolean local;
    private int ticks;
    private String problem = "";
    private final TextDraft[] callDrafts = { new TextDraft(), new TextDraft() };
    private final TextDraft[] tokenDrafts = { new TextDraft(), new TextDraft() };
    UsageScreen(Screen parent) {
        super("AI usage and session limits", parent);
        local = !Minecraft.getInstance().hasSingleplayerServer() && LocalProfiles.allowedHere(LocalProfiles.load());
    }
    private UsageTracker.Snapshot usage() { return local ? ClientAi.usage() : ClientState.usage(); }
    @Override protected void build() {
        button(local ? "Scope: AI on this computer" : "Scope: requests handled by the server", left, 34, panelWidth, () -> {
            if (!minecraft.hasSingleplayerServer()) { local = !local; rebuildWidgets(); }
        }).active = !minecraft.hasSingleplayerServer();
        int third = (panelWidth - 8) / 3;
        TextDraft callDraft = callDrafts[local ? 1 : 0], tokenDraft = tokenDrafts[local ? 1 : 0];
        var calls = new GlassEditBox(font, left, 118, third, 20, Component.literal("Maximum calls"));
        calls.setMaxLength(5); calls.setValue(callDraft.receive("limits", Integer.toString(usage().limits().calls())));
        calls.setResponder(callDraft::edit); addRenderableWidget(calls);
        var tokens = new GlassEditBox(font, left + third + 4, 118, third, 20, Component.literal("Maximum reported tokens"));
        tokens.setMaxLength(10); tokens.setValue(tokenDraft.receive("limits", Long.toString(usage().limits().tokens())));
        tokens.setResponder(tokenDraft::edit); addRenderableWidget(tokens);
        button("Apply limits", left + 2 * (third + 4), 118, third, () -> {
            try {
                var limit = new UsageTracker.Limits(Integer.parseInt(calls.getValue()), Long.parseLong(tokens.getValue()));
                if (local) ClientAi.limits(limit); else run("usage " + limit.calls() + " " + limit.tokens());
                problem = "Limits applied for this session.";
            } catch (IllegalArgumentException e) { problem = e.getMessage(); }
        });
        button("Reset session counters", left, 144, panelWidth, () -> {
            try { if (local) ClientAi.resetUsage(); else run("usage reset"); problem = ""; }
            catch (IllegalStateException e) { problem = e.getMessage(); }
        });
        button("Back", left, height - 28, panelWidth, this::onClose);
    }
    @Override void stateChanged() { /* Keep typed limits and focus while live counters update. */ }
    @Override public void tick() { super.tick(); if (!local && ticks++ % 20 == 0) run("usage show"); }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float tick) {
        super.extractRenderState(g,x,y,tick); var value = usage();
        g.text(font, "Calls: " + value.calls() + "    Review calls: " + value.reviews() + "    Active: " + value.active(), left, 62, Glass.TEXT);
        g.text(font, "Reported tokens: " + value.input() + " in + " + value.output() + " out", left, 76, Glass.TEXT);
        g.text(font, "AI time: " + value.elapsedMillis()/1000 + "s    Calls without token counts: " + value.unreported(), left, 90, Glass.MUTED);
        g.text(font, "Max calls / max reported tokens · 0 = unlimited", left, 106, Glass.MUTED);
        text(g, problem.isEmpty() ? "Limits stop the next call; tokens may overrun or be unreported. Resets on leaving."
                : problem, 172, height - 43, Glass.MUTED);
    }
}
