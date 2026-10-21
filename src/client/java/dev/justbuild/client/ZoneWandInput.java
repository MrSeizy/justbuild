package dev.justbuild.client;

import dev.justbuild.world.ZoneWand;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/** Extended targeting uses selection commands; ordinary clicks retain vanilla interaction packets. */
final class ZoneWandInput {
    private static final Identifier INPUT_PHASE = Identifier.fromNamespaceAndPath("justbuild", "wand_input");
    private static boolean attackHandled;
    private static boolean useHandled;
    private static String extendedCommand;

    private ZoneWandInput() { }

    static void register() {
        LevelRenderEvents.BEFORE_BLOCK_OUTLINE.register((context, outline) ->
                !SelectionHandles.moving() && highlightTarget(Minecraft.getInstance()) == null);
        // Run before the common wand callback, whose SUCCESS sends the normal interaction packet.
        AttackBlockCallback.EVENT.addPhaseOrdering(INPUT_PHASE, Event.DEFAULT_PHASE);
        AttackBlockCallback.EVENT.register(INPUT_PHASE,
                (player, level, hand, pos, direction) -> press(player, hand, true));
        UseBlockCallback.EVENT.addPhaseOrdering(INPUT_PHASE, Event.DEFAULT_PHASE);
        UseBlockCallback.EVENT.register(INPUT_PHASE,
                (player, level, hand, hit) -> press(player, hand, false));
        UseItemCallback.EVENT.addPhaseOrdering(INPUT_PHASE, Event.DEFAULT_PHASE);
        UseItemCallback.EVENT.register(INPUT_PHASE, (player, level, hand) -> press(player, hand, false));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Send after the player's movement/look update, so sneak-select uses the current server-side aim.
            if (extendedCommand != null) {
                if (extendedAvailable(client)) client.player.connection.sendCommand(extendedCommand);
                extendedCommand = null;
            }
            drawTarget(client);
            if (client.player == null || client.gui.screen() != null) {
                attackHandled = false;
                useHandled = false;
            } else {
                // Key mappings also cover users who bind attack/use to keyboard keys.
                if (!client.options.keyAttack.isDown()) attackHandled = false;
                if (!client.options.keyUse.isDown()) useHandled = false;
            }
        });
    }

    static BlockHitResult target(Minecraft mc) {
        return mc.player != null && mc.level != null && mc.player.pick(ZoneWand.REACH, 1, false) instanceof BlockHitResult hit
                && hit.getType() == HitResult.Type.BLOCK ? hit : null;
    }

    private static boolean available(Minecraft mc) {
        return mc.player != null && mc.level != null && mc.gui.screen() == null && !mc.player.isSpectator()
                && ClientState.preview() == null
                && (ZoneWand.isWand(mc.player.getMainHandItem()) || ZoneWand.isWand(mc.player.getOffhandItem()));
    }

    private static boolean extendedAvailable(Minecraft mc) {
        // Vanilla gives usable main-hand items priority over the offhand wand (for example, bows and food).
        return available(mc) && (mc.player.getMainHandItem().isEmpty() || ZoneWand.isWand(mc.player.getMainHandItem()));
    }

    /** Clear before corner controls or vanilla attack handling, including when aiming at air or an entity. */
    static boolean clearPartBeforeTick(Minecraft mc) {
        if (!extendedAvailable(mc) || !mc.options.keyShift.isDown()) return false;
        var stack = ZoneWand.isWand(mc.player.getMainHandItem()) ? mc.player.getMainHandItem() : mc.player.getOffhandItem();
        if (!ZoneWand.isPart(stack) || !consume(mc.options.keyAttack)) return false;
        SelectionHandles.clear();
        extendedCommand = "justbuild box clear";
        return true;
    }

    /** Only intercept targets outside normal reach; near clicks and sneak-use in empty air keep their usual path. */
    static void beforeTick(Minecraft mc) {
        if (!extendedAvailable(mc) || SelectionHandles.moving() || mc.hitResult == null || mc.hitResult.getType() != HitResult.Type.MISS) return;
        BlockHitResult hit = target(mc);
        if (hit == null) return;
        boolean mainWand = ZoneWand.isWand(mc.player.getMainHandItem());
        boolean attack = mainWand && consume(mc.options.keyAttack), use = consume(mc.options.keyUse);
        if (!attack && !use) return;
        var stack = mainWand ? mc.player.getMainHandItem() : mc.player.getOffhandItem();
        var p = hit.getBlockPos();
        extendedCommand = use && !attack && mc.player.isShiftKeyDown() ? "justbuild select"
                : "justbuild " + (ZoneWand.isPart(stack) ? "box" : "zone") + (attack ? " 1 " : " 2 ")
                        + p.getX() + " " + p.getY() + " " + p.getZ();
    }

    private static boolean consume(KeyMapping key) {
        boolean clicked = false;
        while (key.consumeClick()) clicked = true;
        if (clicked) key.setDown(false);
        return clicked;
    }

    private static BlockHitResult highlightTarget(Minecraft mc) {
        if (!extendedAvailable(mc) || SelectionHandles.moving() || mc.hitResult == null
                || mc.hitResult.getType() == HitResult.Type.ENTITY) return null;
        // Use the actual interaction target up close, then extend the same highlight to the wand's full reach.
        return mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK ? hit : target(mc);
    }

    private static void drawTarget(Minecraft mc) {
        BlockHitResult hit = highlightTarget(mc);
        if (hit == null) return;
        var wand = ZoneWand.isWand(mc.player.getMainHandItem()) ? mc.player.getMainHandItem() : mc.player.getOffhandItem();
        int color = ZoneWand.isPart(wand) ? 0xFFD88BFF : 0xFFFFCF6B;
        try (var collecting = mc.collectPerTickGizmos()) {
            // Hidden cube edges must stay behind terrain; drawing them through the ground makes the target look closer.
            Gizmos.cuboid(new AABB(hit.getBlockPos()).inflate(0.005), GizmoStyle.stroke(color, 2));
        }
    }

    private static InteractionResult press(Player player, InteractionHand hand, boolean attack) {
        if (!(player instanceof LocalPlayer) || player.isSpectator() || !ZoneWand.isWand(player.getItemInHand(hand))) {
            return InteractionResult.PASS;
        }
        // FAIL cancels repeats without sending another packet or allowing normal block interaction.
        if (attack ? attackHandled : useHandled) return InteractionResult.FAIL;
        if (attack) attackHandled = true;
        else useHandled = true;
        return InteractionResult.PASS;
    }
}
