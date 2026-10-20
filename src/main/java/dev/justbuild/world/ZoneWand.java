package dev.justbuild.world;

import dev.justbuild.JustBuildMod;
import dev.justbuild.command.JustBuildCommand;
import dev.justbuild.command.Chat;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.CustomData;

/** A held corner selector. Normal interaction packets reach the same server-side zone checks as the commands. */
public final class ZoneWand {
    public static final double REACH = 64;
    // Keep the original registry IDs so existing inventories and containers still load.
    public static final Item ITEM = wand("zone_wand", "corner1", "corner2", "select", "height", "switch");
    public static final Item PART_ITEM = wand("box_wand", "start", "extend", "clear", "select", "outline");

    private ZoneWand() {
    }

    private static Item wand(String name, String... hints) {
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(JustBuildMod.MOD_ID, name));
        return Registry.register(BuiltInRegistries.ITEM, key,
                new Item(new Item.Properties().setId(key).stacksTo(1).rarity(Rarity.UNCOMMON)
                        .component(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, false)
                        .component(DataComponents.LORE, new ItemLore(java.util.Arrays.stream(hints)
                                .map(h -> hint("item.justbuild." + name + "." + h)).toList()))));
    }

    private static Component hint(String key) {
        return Component.translatable(key).withStyle(style -> style.withColor(ChatFormatting.GRAY).withItalic(false));
    }

    public static void register() {
        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> select(player, hand, pos, 1));
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> select(player, hand, hit.getBlockPos(), 2));
        // The selector must not break blocks, open containers, hurt mobs, or fall through to an offhand item.
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, entity) -> !holding(player, InteractionHand.MAIN_HAND));
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) ->
                holding(player, hand) ? InteractionResult.FAIL : InteractionResult.PASS);
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) ->
                holding(player, hand) ? InteractionResult.FAIL : InteractionResult.PASS);
        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (!holding(player, hand)) return InteractionResult.PASS;
            if (player.isSpectator() || !player.isShiftKeyDown()) return InteractionResult.FAIL;
            if (player instanceof ServerPlayer serverPlayer) {
                if (!JustBuildCommand.allowed(serverPlayer.createCommandSourceStack())) {
                    serverPlayer.sendSystemMessage(Chat.error("You need permission to use JustBuild on this server."));
                    return InteractionResult.FAIL;
                }
                ItemStack stack = builder(player.getItemInHand(hand), !isPart(player.getItemInHand(hand)));
                player.setItemInHand(hand, stack);
                sync(serverPlayer);
                if (JustBuildMod.manager() != null) JustBuildMod.manager().sendState(serverPlayer);
                serverPlayer.sendOverlayMessage(Component.literal(isPart(stack)
                        ? "Builder Wand · Box mode (purple)" : "Builder Wand · Zone mode (gold)"));
            }
            return InteractionResult.SUCCESS;
        });
    }

    private static boolean holding(Player player, InteractionHand hand) {
        return isWand(player.getItemInHand(hand));
    }

    public static boolean isWand(ItemStack stack) { return stack.is(ITEM) || stack.is(PART_ITEM); }

    public static boolean isPart(ItemStack stack) {
        return stack.is(PART_ITEM) || stack.is(ITEM)
                && stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag()
                        .getBooleanOr("justbuild_box_mode", false);
    }

    private static ItemStack builder(ItemStack original, boolean part) {
        ItemStack stack = original.transmuteCopy(ITEM, 1);
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putBoolean("justbuild_box_mode", part));
        stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, false);
        stack.set(DataComponents.ITEM_NAME, Component.translatable("item.justbuild.builder_wand." + (part ? "box" : "zone"))
                .withStyle(part ? ChatFormatting.LIGHT_PURPLE : ChatFormatting.GOLD));
        stack.set(DataComponents.ITEM_MODEL, Identifier.fromNamespaceAndPath(JustBuildMod.MOD_ID, part ? "box_wand" : "zone_wand"));
        String prefix = "item.justbuild." + (part ? "box_wand." : "zone_wand.");
        var hints = new java.util.ArrayList<>(java.util.List.of(
                hint(prefix + (part ? "start" : "corner1")), hint(prefix + (part ? "extend" : "corner2")),
                hint(prefix + "select"), hint("item.justbuild.zone_wand.switch")));
        if (part) hints.add(hint("item.justbuild.box_wand.clear"));
        stack.set(DataComponents.LORE, new ItemLore(hints));
        return stack;
    }

    /** Consolidate old selectors into one tool, keeping the held wand's mode where possible. */
    private static boolean consolidate(ServerPlayer player) {
        Inventory inventory = player.getInventory();
        int keep = isWand(player.getMainHandItem()) ? inventory.getSelectedSlot() : -1;
        boolean offhand = keep < 0 && isWand(player.getOffhandItem());
        for (int i = 0; i < inventory.getNonEquipmentItems().size(); i++) {
            if (!isWand(inventory.getItem(i))) continue;
            if (keep < 0 && !offhand) keep = i;
            if (i != keep) inventory.setItem(i, ItemStack.EMPTY);
        }
        if (keep >= 0) {
            inventory.setItem(keep, builder(inventory.getItem(keep), isPart(inventory.getItem(keep))));
            if (isWand(player.getOffhandItem())) player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
        } else if (offhand) {
            player.setItemInHand(InteractionHand.OFF_HAND, builder(player.getOffhandItem(), isPart(player.getOffhandItem())));
        }
        return keep >= 0 || offhand;
    }

    /** Automatic delivery never changes the selected slot, drops items, or overwrites a full inventory. */
    public static boolean giveStarter(ServerPlayer player) {
        if (player.isSpectator() || !JustBuildCommand.allowed(player.createCommandSourceStack())) return false;
        if (!consolidate(player)) {
            int slot = player.getInventory().getFreeSlot();
            if (slot < 0) return false;
            player.getInventory().setItem(slot, builder(new ItemStack(ITEM), false));
            player.sendSystemMessage(Chat.info("Builder Wand added. Gold selects zones; purple selects boxes. Sneak-right-click in the air to switch modes."));
        }
        sync(player);
        return true;
    }

    private static void sync(ServerPlayer player) {
        player.getInventory().setChanged();
        player.inventoryMenu.broadcastChanges();
        player.containerMenu.broadcastChanges();
    }

    private static InteractionResult select(Player player, InteractionHand hand, BlockPos pos, int corner) {
        if (!holding(player, hand) || player.isSpectator()) {
            return InteractionResult.PASS;
        }
        if (player instanceof ServerPlayer serverPlayer) {
            CommandSourceStack source = serverPlayer.createCommandSourceStack();
            if (!JustBuildCommand.allowed(source)) {
                serverPlayer.sendSystemMessage(Chat.error("You need permission to use JustBuild on this server."));
            } else if (serverPlayer.isWithinBlockInteractionRange(pos, 0)
                    && serverPlayer.level().hasChunk(pos.getX() >> 4, pos.getZ() >> 4)
                    && !serverPlayer.level().getBlockState(pos).isAir()) {
                var manager = JustBuildMod.manager();
                if (manager != null) {
                    if (corner == 2 && player.isShiftKeyDown()) manager.selectBuildAt(source, pos);
                    else manager.wandCorner(source, corner, pos, isPart(player.getItemInHand(hand)));
                }
            }
        }
        // Fabric forwards the normal click packet on the client and cancels block behavior on both sides.
        return InteractionResult.SUCCESS;
    }

    /** Reuse an existing wand, or equip a new one only when the displaced item can be kept in the inventory. */
    public static void give(CommandSourceStack source) {
        give(source, false);
    }

    public static boolean give(CommandSourceStack source, boolean part) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendSystemMessage(Chat.error("Run /justbuild " + (part ? "box" : "zone") + " wand as a player to get the selection wand."));
            return false;
        }
        if (!JustBuildCommand.allowed(source)) {
            player.sendSystemMessage(Chat.error("You need permission to use JustBuild on this server."));
            return false;
        }
        consolidate(player);
        Item item = ITEM;
        Inventory inventory = player.getInventory();
        int found = -1;
        for (int i = 0; i < inventory.getNonEquipmentItems().size(); i++) {
            if (inventory.getItem(i).is(item)) {
                found = i;
                break;
            }
        }
        if (found >= 0) {
            if (Inventory.isHotbarSlot(found)) {
                inventory.setSelectedSlot(found);
            } else {
                inventory.pickSlot(found);
            }
        } else if (player.getOffhandItem().is(item)) {
            ItemStack previous = player.getMainHandItem();
            player.setItemInHand(InteractionHand.MAIN_HAND, player.getOffhandItem());
            player.setItemInHand(InteractionHand.OFF_HAND, previous);
        } else if (inventory.getFreeSlot() >= 0) {
            inventory.addAndPickItem(new ItemStack(item));
        } else {
            player.sendSystemMessage(Chat.error("Make room in your inventory, then request the selection wand again."));
            return false;
        }
        player.setItemInHand(InteractionHand.MAIN_HAND, builder(player.getMainHandItem(), part));
        sync(player);
        player.connection.send(new ClientboundSetHeldSlotPacket(inventory.getSelectedSlot()));
        player.sendSystemMessage(Chat.info(part
                ? "Builder Wand · Box mode equipped (purple). Left-click to start; right-click a block to grow the box or a purple corner to resize it. Sneak-left-click to clear. Sneak-right-click in the air to switch modes."
                : "Builder Wand · Zone mode equipped (gold). Left-click a block for corner 1, right-click another for corner 2. Sneak-right-click in the air to switch modes. "
                        + "Sneak-right-click a build to select it. Blocks stay intact; height is automatic."));
        return true;
    }
}
