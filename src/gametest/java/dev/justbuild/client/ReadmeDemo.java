package dev.justbuild.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import dev.justbuild.config.JustBuildConfig;
import dev.justbuild.config.ConfigStore;
import dev.justbuild.plan.BuildPlan;
import dev.justbuild.plan.Cell;
import dev.justbuild.plan.Operation;
import dev.justbuild.plan.PlanWriter;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import com.mojang.blaze3d.platform.InputConstants;

/** Opt-in documentation recording, intentionally absent from the normal gametest entrypoints and release JAR. */
public final class ReadmeDemo implements FabricClientGameTest {
    private final Queue<String> replies = new ConcurrentLinkedQueue<>();
    private final JsonArray clips = new JsonArray();
    private Path output;
    private String clip, title;
    private int frame;

    public ReadmeDemo() { }
    ReadmeDemo(Path output) { this.output = output; }

    static Path captureDirectory() {
        String configured = System.getenv("JUSTBUILD_DEMO_OUTPUT");
        return configured == null ? FabricLoader.getInstance().getGameDir().resolve("readme-demos") : Path.of(configured);
    }

    void writeManifest(String name) {
        try { Files.writeString(output.resolve(name), clips.toString()); }
        catch (Exception e) { throw new AssertionError(e); }
    }

    @Override public void runTest(ClientGameTestContext c) {
        System.setProperty("java.awt.headless", "true");
        output = captureDirectory();
        Path configFile = FabricLoader.getInstance().getConfigDir().resolve("justbuild.json");
        byte[] previous;
        try { previous = Files.exists(configFile) ? Files.readAllBytes(configFile) : null; }
        catch (Exception e) { throw new AssertionError(e); }
        HttpServer provider = provider();
        configure(configFile, provider.getAddress().getPort());
        try (TestSingleplayerContext world = c.worldBuilder().create()) {
            world.getConnection().waitForChunksRender();
            BlockPos base = c.computeOnClient(mc -> mc.player.blockPosition());
            String player = c.computeOnClient(mc -> mc.player.getName().getString());
            prepare(c, world, player, base);

            // Zone selection uses actual left and right mouse clicks with the equipped wand.
            begin("01-select-area", "SELECT A BUILD AREA");
            open(c, "World");
            hold(c, "Press Right Shift, then World and Wand: Zone.", "Wand: Zone", 16);
            c.clickScreenButton("Wand: Zone"); c.waitTicks(8);
            lookDown(c, world, player, base.offset(-14, -1, -14));
            hold(c, "Left-click the first ground corner.", null, 12);
            c.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT); c.waitTicks(5);
            hold(c, "Corner 1 is set. The block stays intact.", null, 12);
            lookDown(c, world, player, base.offset(14, -1, 14));
            hold(c, "Right-click the opposite corner.", null, 12);
            c.getInput().pressMouse(InputConstants.MOUSE_BUTTON_RIGHT); c.waitTicks(5);
            camera(c, world, player, base, 21, 18, 24);
            c.runOnClient(mc -> dev.justbuild.client.TestHud.hidden(mc, false));
            hold(c, "The wand HUD shows controls, dimensions, and height.", null, 22);
            snapshot(c, "wand-selection");
            PreviewControlsGameTest.runOnLook(c, c.computeOnClient(mc -> SelectionHandles.zoneCornerPosition(ClientState.zone(), 0)));
            hold(c, "Right-click a corner handle to pick it up.", null, 16);
            PreviewControlsGameTest.click(c); c.waitFor(mc -> SelectionHandles.moving());
            lookDown(c, world, player, base.offset(-13, -1, -14));
            hold(c, "Walk to a new spot. Right-click to place; left-click cancels.", null, 22);
            snapshot(c, "corner-relocation");
            PreviewControlsGameTest.click(c); c.waitFor(mc -> !SelectionHandles.moving() && ClientState.zone().minX() == base.getX() - 13);
            camera(c, world, player, base, 21, 18, 24); c.runOnClient(mc -> dev.justbuild.client.TestHud.hidden(mc, false));
            hold(c, "The corner moves without opening a menu.", null, 18);
            finish();

            // The only AI calls in the recordings go to this local fixed-plan provider.
            begin("02-first-build", "DESCRIBE, PREVIEW, BUILD");
            camera(c, world, player, base, 10, 8, 14);
            emptyHand(c, world);
            open(c, "Create");
            type(c, "Request", "Build a cozy stone cottage with a spruce roof and windows.", "Describe the building you want.");
            replies.add(cottage(false));
            hold(c, "Choose Build to request a plan.", "Build", 12); c.clickScreenButton("Build");
            c.waitFor(mc -> ClientState.preview() != null); close(c);
            hold(c, "Inspect the translucent preview before placing blocks.", null, 22);
            open(c, "Create"); hold(c, "Build it places the preview. Discard removes it.", "Build it", 13);
            c.clickScreenButton("Build it"); close(c);
            hold(c, "The mod places the validated plan over several ticks.", null, 30);
            c.waitFor(mc -> ClientState.string("selectedBuild", null) != null && ClientState.preview() == null);
            hold(c, "The finished cottage is saved and remains editable.", null, 22);
            snapshot(c, "cottage-build");
            String house = c.computeOnClient(mc -> ClientState.string("selectedBuild", null));
            finish();

            begin("03-edit-building", "EDIT A SAVED BUILDING");
            open(c, "History"); hold(c, "Choose Edit beside a saved building in History.", "Edit", 17);
            c.clickScreenButton("Edit"); c.waitTicks(5); c.tryClickScreenButton("Create");
            type(c, "Request", "Change the roof to dark oak and add a brick chimney.", "The selected building uses Apply instead of Build.");
            replies.add(cottage(true)); hold(c, "Apply requests an edit to this building.", "Apply", 12); c.clickScreenButton("Apply");
            c.waitFor(mc -> ClientState.preview() != null); close(c);
            hold(c, "Gold highlights replacements; inspect the new chimney.", null, 24);
            open(c, "Create"); c.clickScreenButton("Floors");
            c.clickScreenButton("Before"); hold(c, "Before shows the building as it stands now.", "Before", 16);
            c.clickScreenButton("After"); hold(c, "After shows the proposed roof and chimney.", "After", 16);
            snapshot(c, "before-after");
            c.clickScreenButton("Back"); c.clickScreenButton("Build it"); close(c);
            hold(c, "Confirm applies only the changes to the saved build.", null, 32);
            finish();

            // Save a design once; subsequent demos use the same actual library entry.
            command(c, "design save Stone Cottage"); c.waitFor(mc -> !ClientState.designs().isEmpty());
            String design = c.computeOnClient(mc -> ClientState.designs().getFirst().id());
            command(c, "select clear"); zone(c, base.east(28)); camera(c, world, player, base.east(28), 6, 8, 14);
            command(c, "design place " + design); c.waitFor(mc -> ClientState.preview() != null);
            begin("04-preview-tools", "ADJUST AND INSPECT A PREVIEW");
            PreviewControlsGameTest.runOnLook(c, Vec3.atCenterOf(base.east(28).above(7)));
            hold(c, "Saved copies and new builds can move before placement.", null, 17);
            // Zoomed in, so the controls' labels can be read in the recording.
            int fov = c.computeOnClient(mc -> mc.options.fov().get());
            c.runOnClient(mc -> mc.options.fov().set(45));
            try {
                // Real right-clicks on the controls floating at the preview.
                PreviewControlsGameTest.aim(c, PreviewControls.Control.ROTATE);
                hold(c, "Look at a control at the preview's base and right-click it.", null, 14);
                press(c, PreviewControls.Control.ROTATE);
                hold(c, "Rotate turns the preview a quarter turn.", null, 14);
                PreviewControlsGameTest.aim(c, PreviewControls.Control.EAST);
                hold(c, "The arrows beside it move it one block, or five while sneaking.", null, 12);
                press(c, PreviewControls.Control.EAST);
                hold(c, "The rotated copy moves without another AI call.", null, 16);
            } finally {
                c.runOnClient(mc -> mc.options.fov().set(fov));
            }
            open(c, "Create");
            hold(c, "Floors lets you inspect inside the proposed building.", "Floors", 14);
            c.clickScreenButton("Floors"); hold(c, "Floors opens a top-down view of the proposed build.", "Lower layer", 14);
            for (int i = 0; i < 7; i++) {
                c.clickScreenButton("Lower layer"); hold(c, "Lower the ceiling to inspect rooms beneath the roof.", "Lower layer", 4);
            }
            hold(c, "Check the interior without changing the world.", null, 17);
            c.clickScreenButton("Back"); hold(c, "Return to the panel to build or discard the preview.", "Build it", 14);
            // The second cancel drops the discarded copy's kept plan, which would replace Editing tools on Create.
            close(c); command(c, "cancel"); command(c, "cancel"); finish();

            command(c, "select #" + house); camera(c, world, player, base, 10, 8, 14);
            begin("05-materials", "REPLACE MATERIALS VISUALLY");
            workshop(c); hold(c, "Editing tools → Workshop → Materials.", "Materials", 15);
            c.clickScreenButton("Materials"); c.waitFor(mc -> !ClientState.materials().isEmpty());
            c.clickScreenButton("Choose from building"); search(c, "stone_bricks");
            hold(c, "Pick the material already used by the building.", null, 16); chooseMaterial(c, "Stone Bricks");
            c.clickScreenButton("Choose replacement"); search(c, "deepslate_bricks");
            hold(c, "Search the block picker for a replacement.", null, 17); chooseMaterial(c, "Deepslate Bricks");
            hold(c, "Preview replacement shows the exact changes.", "Preview replacement", 16);
            c.clickScreenButton("Preview replacement"); c.waitFor(mc -> ClientState.preview() != null); close(c);
            hold(c, "Inspect the highlighted blocks before confirming.", null, 18);
            open(c, "Create"); c.clickScreenButton("Build it"); close(c);
            hold(c, "The palette changes without an AI call.", null, 32);
            finish();

            // Damage only this disposable demo world, then use the real repair workflow.
            world.getServer().runOnServer(server -> {
                for (BlockPos p : BlockPos.betweenClosed(base.offset(1, 1, 4), base.offset(3, 3, 4))) {
                    server.overworld().setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                }
            }); c.waitTicks(8);
            begin("06-repair", "REPAIR DAMAGE AND UNDO");
            hold(c, "A wall has been damaged in this saved building.", null, 20);
            workshop(c); hold(c, "Open Workshop → Repair.", "Repair", 13); c.clickScreenButton("Repair");
            hold(c, "Missing blocks only preserves manual material changes.", "Preview repair", 20);
            c.clickScreenButton("Preview repair"); c.waitFor(mc -> ClientState.preview() != null); close(c);
            hold(c, "Preview the missing blocks before replacing them.", null, 18);
            open(c, "Create"); c.clickScreenButton("Build it"); close(c);
            hold(c, "Confirm restores the missing wall. No AI call is needed.", null, 24);
            open(c, "Create"); hold(c, "Undo remains available for repairs and material swaps.", "Undo", 17);
            finish();

            begin("07-save-and-copy", "SAVE AND REUSE A DESIGN");
            c.clickScreenButton("Manage build");
            type(c, "Design name", "Village Cottage", "Name a reusable design in Manage build.");
            hold(c, "Save design captures the building's current block states.", "Save design", 16);
            c.clickScreenButton("Save design"); c.waitFor(mc -> ClientState.designs().size() >= 2);
            close(c); command(c, "select clear"); zone(c, base.east(28)); camera(c, world, player, base.east(28), 10, 8, 14);
            open(c, "Designs"); hold(c, "Find the saved design in your library.", "Village Cottage", 18);
            c.clickScreenButton("Village Cottage"); hold(c, "Set a rotation or optional material swaps.", "Rotation: 0°", 16);
            c.clickScreenButton("Rotation: 0°"); hold(c, "Preview copy prepares an independent building.", "Preview copy", 15);
            c.clickScreenButton("Preview copy"); c.waitFor(mc -> ClientState.preview() != null); close(c);
            hold(c, "Inspect the copy at its destination, then confirm.", null, 22);
            open(c, "Create"); c.clickScreenButton("Build it"); close(c);
            hold(c, "The copy has its own history and undo. No AI call.", null, 32);
            finish();

            begin("08-schematic-sharing", "SHARE AND IMPORT SCHEMATICS");
            open(c, "Designs"); c.clickScreenButton("Village Cottage");
            hold(c, "Open a saved design, then Export design.", "Export design", 18);
            c.clickScreenButton("Export design");
            hold(c, "Export Litematica, Sponge, or JustBuild JSON.", "Litematica · .litematic", 22);
            snapshot(c, "schematic-export");
            c.clickScreenButton("Litematica · .litematic"); c.waitTicks(8);
            c.clickScreenButton("Sponge schematic · .schem"); c.waitTicks(8);
            c.clickScreenButton("Shared library");
            c.waitFor(mc -> ClientState.list("libraryFiles").size() == 2);
            hold(c, "Files in justbuild/library appear in the shared library.", "Refresh", 22);
            snapshot(c, "schematic-import");
            int designs = c.computeOnClient(mc -> ClientState.designs().size());
            hold(c, "Air is skipped by default. Import adds a reusable design.", "Include schematic air: Off", 22);
            chooseMaterial(c, "Import ");
            c.waitFor(mc -> ClientState.designs().size() > designs);
            open(c, "Designs"); hold(c, "Imported schematics can be previewed and placed as copies.", null, 24);
            finish();

            command(c, "select clear"); zone(c, base);
            begin("09-polygon-footprint", "DRAW A POLYGON FOOTPRINT");
            open(c, "World"); c.clickScreenButton("Polygon");
            hold(c, "Choose Polygon for an irregular build area.", "Start polygon with wand", 20);
            c.clickScreenButton("Start polygon with wand"); c.waitTicks(8);
            for (int[] corner : new int[][]{{-14,-14},{14,-14},{14,0},{9,0},{9,14},{-14,14}}) {
                lookDown(c, world, player, base.offset(corner[0], -1, corner[1]));
                c.getInput().pressMouse(InputConstants.MOUSE_BUTTON_RIGHT); c.waitTicks(4);
                hold(c, "Right-click each corner in boundary order.", null, 7);
            }
            open(c, "World"); c.clickScreenButton("Polygon");
            hold(c, "Finish polygon closes the boundary; Undo removes a corner.", "Finish polygon", 20);
            c.clickScreenButton("Finish polygon"); c.waitFor(mc -> ClientState.zone() != null && ClientState.zone().polygon().size() == 6);
            camera(c, world, player, base, 23, 22, 26); c.runOnClient(mc -> dev.justbuild.client.TestHud.hidden(mc, false));
            hold(c, "The finished outline follows the polygon, including its notch.", null, 28);
            snapshot(c, "polygon-footprint"); finish();

            command(c, "select #" + house); emptyHand(c, world); camera(c, world, player, base, 10, 8, 14);
            open(c, "World"); snapshot(c, "world-panel");
            workshop(c); snapshot(c, "building-workshop");
            command(c, "constraints height 16"); command(c, "constraints floors 1");
            c.clickScreenButton("Requirements"); snapshot(c, "build-requirements"); close(c);
            command(c, "box wand"); command(c, "box 1 " + pos(base.offset(-4, 5, -5)));
            command(c, "box 2 " + pos(base.offset(4, 11, 5)));
            begin("10-named-parts", "SAVE AND REUSE A NAMED PART");
            c.runOnClient(mc -> dev.justbuild.client.TestHud.hidden(mc, false));
            hold(c, "With the roof selected in Box mode, save it for later edits.", null, 22);
            workshop(c); c.clickScreenButton("Named parts");
            type(c, "Part name", "Roof", "Give the selected region a memorable name.");
            hold(c, "Save part remembers this region on the selected building.", "Save part", 18);
            c.clickScreenButton("Save part"); c.waitFor(mc -> ClientState.namedParts().containsKey("Roof"));
            snapshot(c, "named-parts");
            command(c, "box clear");
            hold(c, "Select: Roof restores the saved region for another edit.", "Select: Roof", 20);
            c.clickScreenButton("Select: Roof"); c.waitFor(mc -> ClientState.part() != null); close(c);
            c.runOnClient(mc -> dev.justbuild.client.TestHud.hidden(mc, false));
            hold(c, "The purple outline shows the active part boundary.", null, 24); finish();

            command(c, "box clear"); emptyHand(c, world);
            begin("11-version-history", "COMPARE AND RESTORE A VERSION");
            workshop(c); c.clickScreenButton("Version history");
            hold(c, "Browse saved versions with thumbnails and request text.", null, 24);
            snapshot(c, "version-history");
            for (int page = 0; page < 16 && !hasButton(c, "v1 ·"); page++) { c.clickScreenButton("Next"); c.waitTicks(3); }
            hold(c, "Choose an older version to preview its restore.", null, 20);
            chooseMaterial(c, "v1 ·"); c.waitFor(mc -> ClientState.preview() != null); close(c);
            hold(c, "The restore previews changes before touching the world.", null, 22);
            open(c, "Create"); c.clickScreenButton("Floors");
            c.clickScreenButton("Before"); hold(c, "Before shows the current building.", "Before", 18);
            c.clickScreenButton("After"); hold(c, "After shows the earlier version.", "After", 18);
            c.clickScreenButton("Filter: all"); c.clickScreenButton("Filter: added");
            hold(c, "Filter: replaced isolates material changes.", "Filter: replaced", 20);
            c.clickScreenButton("Back"); hold(c, "Build it confirms the restore; Discard leaves the build as is.", "Build it", 22);
            c.clickScreenButton("Discard"); close(c); command(c, "cancel"); finish();

            workshop(c); c.clickScreenButton("AI usage");
            setField(c, "Maximum calls", "10"); setField(c, "Maximum reported tokens", "20000");
            c.clickScreenButton("Apply limits"); c.waitTicks(8); snapshot(c, "ai-usage");
            try { Files.writeString(output.resolve("clips.json"), clips.toString()); }
            catch (Exception e) { throw new AssertionError(e); }
        } finally {
            provider.stop(0);
            try { if (previous == null) Files.deleteIfExists(configFile); else Files.write(configFile, previous); }
            catch (Exception e) { throw new AssertionError("Could not restore test config", e); }
        }
    }

    void begin(String name, String label) {
        clip = name; title = label; frame = 0;
        try { Files.createDirectories(output.resolve(clip)); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    void finish() {
        JsonObject entry = new JsonObject(); entry.addProperty("name", clip); entry.addProperty("title", title);
        entry.addProperty("frames", frame); entry.addProperty("fps", 10); clips.add(entry);
        System.out.println("README DEMO: " + clip + " / " + frame + " frames");
    }
    void snapshot(ClientGameTestContext c, String name) {
        if (c.computeOnClient(mc -> mc.gui.screen() != null)) c.getInput().setCursorPos(0, 0);
        c.waitTicks(4);
        try {
            Path stills = output.resolve("stills"); Files.createDirectories(stills);
            Files.copy(c.takeScreenshot("readme-still-" + name), stills.resolve(name + ".png"), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) { throw new AssertionError("Could not save documentation screenshot", e); }
    }
    static boolean hasButton(ClientGameTestContext c, String prefix) {
        return c.computeOnClient(mc -> mc.gui.screen().children().stream().anyMatch(w -> w instanceof Button b && b.getMessage().getString().startsWith(prefix)));
    }
    static void setField(ClientGameTestContext c, String label, String value) {
        c.runOnClient(mc -> ((EditBox) mc.gui.screen().children().stream().filter(w -> w instanceof EditBox e && e.getMessage().getString().equals(label)).findFirst().orElseThrow()).setValue(value));
    }
    static void emptyHand(ClientGameTestContext c, TestSingleplayerContext world) {
        // Number keys control the spectator menu rather than the hotbar during camera shots.
        world.getServer().runOnServer(server -> server.getPlayerList().getPlayers().getFirst().getInventory().setSelectedSlot(1));
        c.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(1)); c.waitTicks(4);
    }
    void hold(ClientGameTestContext c, String caption, String highlight, int frames) {
        // Clicks leave the cursor on the clicked button; the top-left corner is clear of the sheet, so nothing looks
        // hovered. Only with a screen open: in the world, moving the cursor turns the camera.
        if (c.computeOnClient(mc -> mc.gui.screen() != null)) c.getInput().setCursorPos(0, 0);
        for (int i = 0; i < frames; i++) {
            c.waitTicks(2);
            double[] bounds = highlight == null ? null : c.computeOnClient(mc -> {
                if (mc.gui.screen() == null) return null;
                for (var widget : mc.gui.screen().children()) if (widget instanceof AbstractWidget w && w.getMessage().getString().equals(highlight)) {
                    double scale = (double) mc.getWindow().getWidth() / mc.getWindow().getGuiScaledWidth();
                    var r = w instanceof GlassEditBox box ? box.field() : w.getRectangle();
                    return new double[]{r.left() * scale, r.top() * scale, r.width() * scale, r.height() * scale};
                }
                return null;
            });
            Path raw = c.takeScreenshot("readme-" + clip + "-" + frame);
            try {
                BufferedImage input = ImageIO.read(raw.toFile());
                BufferedImage result = new BufferedImage(input.getWidth(), input.getHeight() + 76, BufferedImage.TYPE_INT_RGB);
                var g = result.createGraphics();
                g.drawImage(input, 0, 0, null);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                if (bounds != null) {
                    g.setColor(new Color(255, 191, 73)); g.setStroke(new BasicStroke(3));
                    g.drawRoundRect((int) bounds[0] - 2, (int) bounds[1] - 2, (int) bounds[2] + 4, (int) bounds[3] + 4, 7, 7);
                }
                int top = input.getHeight();
                g.setColor(new Color(18, 25, 34)); g.fillRect(0, top, result.getWidth(), 76);
                g.setColor(new Color(255, 191, 73)); g.fillRect(0, top, 4, 76);
                g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12)); g.drawString("JustBuild  /  " + title, 22, top + 24);
                g.setColor(new Color(237, 241, 245)); g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 20));
                if (g.getFontMetrics().stringWidth(caption) > result.getWidth() - 42) g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 17));
                g.drawString(caption, 22, top + 54); g.dispose();
                ImageIO.write(result, "png", output.resolve(clip).resolve(String.format("frame%04d.png", frame++)).toFile());
            } catch (Exception e) { throw new AssertionError("Could not save recording frame", e); }
        }
    }
    /** Right-clicks the control under the crosshair and waits for the preview to move. */
    private static void press(ClientGameTestContext c, PreviewControls.Control control) {
        String before = c.computeOnClient(mc -> ClientState.preview().position());
        if (c.computeOnClient(mc -> PreviewControls.hovered()) != control) throw new AssertionError(control + " is not under the crosshair");
        PreviewControlsGameTest.click(c);
        c.waitFor(mc -> !ClientState.preview().position().equals(before), 200);
        c.waitTicks(5);
    }
    void type(ClientGameTestContext c, String label, String value, String caption) {
        for (int i = 0; i < value.length(); i += 5) {
            String partial = value.substring(0, Math.min(value.length(), i + 5));
            c.runOnClient(mc -> ((EditBox) mc.gui.screen().children().stream().filter(w -> w instanceof EditBox e && e.getMessage().getString().equals(label)).findFirst().orElseThrow()).setValue(partial));
            hold(c, caption, label, 1);
        }
        hold(c, caption, label, 12);
    }
    private static void search(ClientGameTestContext c, String text) {
        c.runOnClient(mc -> ((EditBox) mc.gui.screen().children().stream().filter(w -> w instanceof EditBox).findFirst().orElseThrow()).setValue(text)); c.waitTicks(3);
    }
    static void chooseMaterial(ClientGameTestContext c, String label) {
        String actual = c.computeOnClient(mc -> mc.gui.screen().children().stream().filter(w -> w instanceof Button b && b.getMessage().getString().strip().startsWith(label))
                .map(w -> ((Button) w).getMessage().getString()).findFirst().orElseThrow());
        c.clickScreenButton(actual);
    }
    static void workshop(ClientGameTestContext c) { open(c, "Create"); c.clickScreenButton("Editing tools"); c.clickScreenButton("Workshop"); }
    static void open(ClientGameTestContext c, String tab) {
        c.runOnClient(mc -> { dev.justbuild.client.TestHud.hidden(mc, false); mc.gui.hud.getChat().clearMessages(false); mc.gui.setScreen(null); });
        c.getInput().pressKey(InputConstants.KEY_RSHIFT); c.waitForScreen(JustBuildScreen.class); c.tryClickScreenButton(tab); c.waitTicks(4);
    }
    static void close(ClientGameTestContext c) { c.runOnClient(mc -> { mc.gui.setScreen(null); dev.justbuild.client.TestHud.hidden(mc, true); mc.gui.hud.getChat().clearMessages(false); }); c.waitTicks(3); }
    static void command(ClientGameTestContext c, String command) { c.runOnClient(mc -> mc.player.connection.sendCommand("justbuild " + command)); c.waitTicks(8); }
    static void zone(ClientGameTestContext c, BlockPos at) {
        command(c, "zone 1 " + pos(at.offset(-13, -1, -13))); command(c, "zone 2 " + pos(at.offset(13, -1, 13)));
    }
    static String pos(BlockPos p) { return p.getX() + " " + p.getY() + " " + p.getZ(); }
    static void camera(ClientGameTestContext c, TestSingleplayerContext world, String player, BlockPos at, int x, int y, int z) {
        world.getServer().runCommand("gamemode spectator " + player);
        world.getServer().runCommand("tp " + player + " " + (at.getX() + x + .5) + " " + (at.getY() + y) + " " + (at.getZ() + z + .5)
                + " facing " + (at.getX() + .5) + " " + (at.getY() + 3) + " " + (at.getZ() + .5));
        close(c); c.waitTicks(8);
        c.runOnClient(mc -> mc.gui.hud.getChat().clearMessages(false));
    }
    private static void lookDown(ClientGameTestContext c, TestSingleplayerContext world, String player, BlockPos at) {
        world.getServer().runCommand("gamemode creative " + player);
        world.getServer().runCommand("tp " + player + " " + (at.getX() + .5) + " " + (at.getY() + 1) + " " + (at.getZ() + .5) + " 0 90");
        c.runOnClient(mc -> { dev.justbuild.client.TestHud.hidden(mc, false); mc.gui.hud.getChat().clearMessages(false); }); c.waitTicks(8);
        c.waitFor(mc -> mc.hitResult instanceof BlockHitResult hit && hit.getBlockPos().equals(at));
    }
    static void prepare(ClientGameTestContext c, TestSingleplayerContext world, String player, BlockPos base) {
        world.getServer().runCommand("time set noon"); world.getServer().runCommand("weather clear");
        world.getServer().runCommand("difficulty peaceful"); world.getServer().runCommand("gamemode creative " + player);
        world.getServer().runOnServer(server -> {
            for (BlockPos p : BlockPos.betweenClosed(base.offset(-25, -1, -25), base.offset(52, 15, 25))) {
                server.overworld().setBlock(p, p.getY() == base.getY() - 1 ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.AIR.defaultBlockState(),
                        Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            }
            // A small landscaped backdrop, separate from the editable cottage.
            for (int dx : new int[]{-18, -12, 20, 43}) {
                BlockPos tree = base.offset(dx, 0, -16);
                for (int y = 0; y <= 4; y++) server.overworld().setBlock(tree.above(y), Blocks.OAK_LOG.defaultBlockState(), Block.UPDATE_CLIENTS);
                for (BlockPos p : BlockPos.betweenClosed(tree.offset(-2, 3, -2), tree.offset(2, 5, 2))) {
                    if (p.getX() != tree.getX() || p.getZ() != tree.getZ() || p.getY() > tree.getY() + 4)
                        server.overworld().setBlock(p, Blocks.OAK_LEAVES.defaultBlockState().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.PERSISTENT, true), Block.UPDATE_CLIENTS);
                }
            }
        }); c.waitTicks(20);
        c.runOnClient(mc -> mc.gui.hud.getChat().clearMessages(false));
    }
    static String cottage(boolean edited) {
        List<Operation> ops = new ArrayList<>();
        ops.add(Operation.fill("cobblestone", new Cell(-3,-1,-4), new Cell(3,-1,4), false));
        ops.add(Operation.fill("oak_planks", new Cell(-3,0,-4), new Cell(3,0,4), false));
        ops.add(Operation.fill("stone_bricks", new Cell(-3,1,-4), new Cell(3,4,-4), false));
        ops.add(Operation.fill("stone_bricks", new Cell(-3,1,4), new Cell(3,4,4), false));
        for (int x : new int[]{-3,3}) ops.add(Operation.fill("stone_bricks", new Cell(x,1,-4), new Cell(x,4,4), false));
        for (int x : new int[]{-3,3}) for (int z : new int[]{-4,4}) ops.add(Operation.fill("stripped_spruce_log[axis=y]", new Cell(x,1,z), new Cell(x,4,z), false));
        for (int x : new int[]{-2,2}) for (int z : new int[]{-4,4}) ops.add(Operation.fill("glass", new Cell(x,2,z), new Cell(x,3,z), false));
        for (int x : new int[]{-3,3}) ops.add(Operation.fill("glass", new Cell(x,2,-1), new Cell(x,3,1), false));
        ops.add(Operation.fill("air", new Cell(0,1,4), new Cell(0,2,4), false));
        ops.add(Operation.set("oak_door[facing=south,half=lower]", new Cell(0,1,4)));
        ops.add(Operation.roof(edited ? "dark_oak_stairs" : "spruce_stairs", "oak_planks", new Cell(-4,5,-5), new Cell(4,5,5), "gable", "z"));
        ops.add(Operation.fill("gravel", new Cell(-1,0,5), new Cell(1,0,8), false));
        ops.add(Operation.set("lantern[hanging=true]", new Cell(0,3,4)));
        ops.add(Operation.set("blue_bed[facing=north,part=foot]", new Cell(-2,1,-1)));
        ops.add(Operation.set("crafting_table", new Cell(2,1,-3)));
        ops.add(Operation.fill("bookshelf", new Cell(1,1,-3), new Cell(1,2,-3), false));
        ops.add(Operation.set("lantern", new Cell(2,2,-3)));
        for (int x : new int[]{-4,4}) for (int z : new int[]{-4,4}) ops.add(Operation.set("oak_leaves[persistent=true]", new Cell(x,0,z)));
        if (edited) {
            ops.add(Operation.fill("bricks", new Cell(2,1,-2), new Cell(2,10,-2), false));
            ops.add(Operation.set("stone_brick_wall", new Cell(2,11,-2)));
        }
        return PlanWriter.write(new BuildPlan("Stone Cottage", "A compact cottage with a furnished interior.", ops));
    }
    private HttpServer provider() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", exchange -> {
                String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String reviewed = StubReview.draft(request);
                String response = reviewed != null ? reviewed : replies.poll();
                if (response == null) throw new IllegalStateException("Unexpected demo AI call");
                JsonObject message = new JsonObject(); message.addProperty("content", response);
                JsonObject choice = new JsonObject(); choice.add("message", message); JsonArray choices = new JsonArray(); choices.add(choice);
                JsonObject reply = new JsonObject(); reply.add("choices", choices);
                JsonObject usage = new JsonObject(); usage.addProperty("prompt_tokens", 1000); usage.addProperty("completion_tokens", 200);
                reply.add("usage", usage);
                byte[] bytes = reply.toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json"); exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes); exchange.close();
            }); server.start(); return server;
        } catch (Exception e) { throw new AssertionError(e); }
    }
    private static void configure(Path file, int port) {
        try {
            var config = JustBuildConfig.defaults(); var profile = new JustBuildConfig.ModelProfile("Demo", "openai", "example-plan");
            profile.baseUrl = "http://127.0.0.1:" + port + "/v1";
            config.replaceProfiles(List.of(profile), "Demo"); config.previewAbove = 0; config.blocksPerTick = 12;
            config.detail = "quick"; config.creativeDirection = false; config.maxQuestions = 0;
            new ConfigStore(file).save(config);
        } catch (Exception e) { throw new AssertionError(e); }
    }
}
