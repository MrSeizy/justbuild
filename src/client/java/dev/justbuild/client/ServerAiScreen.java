package dev.justbuild.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** The server's policy for remote players; local AI consent remains each player's choice. */
final class ServerAiScreen extends ToolScreen {
    private static final String[] POLICIES = { "require", "prefer", "off" };
    private static final String[] LABELS = { "Require players' AI", "Prefer players' AI", "Use server AI" };
    private static final String[] DESCRIPTIONS = {
            "Each remote player uses their own AI. Refuse if it is unavailable.",
            "Use players' AI when ready; otherwise use the server's profile.",
            "Always use the server's profile for remote players."
    };

    ServerAiScreen(Screen parent) { super("Server AI policy", parent); }

    @Override protected void build() {
        String current = ClientState.string("playerAi", "require");
        for (int i = 0; i < POLICIES.length; i++) {
            String policy = POLICIES[i];
            boolean selected = policy.equals(current);
            var choice = button((selected ? "★ " : "") + LABELS[i], left, 34 + i * 44, panelWidth,
                    () -> run("playerai " + policy));
            choice.active = ClientState.manage() && !selected;
            choice.setTooltip(Tooltip.create(Component.literal(DESCRIPTIONS[i]
                    + " The singleplayer owner, LAN host, and console always use the server's profile.")));
        }
        button("Back", left, height - 28, panelWidth, this::onClose);
    }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float tick) {
        super.extractRenderState(g, x, y, tick);
        for (int i = 0; i < DESCRIPTIONS.length; i++) text(g, DESCRIPTIONS[i], 58 + i * 44, 78 + i * 44, Glass.MUTED);
        text(g, ClientState.manage() ? "Saved on the server; active AI calls can finish."
                : "Only operators or the world owner can change this policy.", 174, height - 52, Glass.MUTED);
    }
}
