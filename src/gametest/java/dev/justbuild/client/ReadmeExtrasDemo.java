package dev.justbuild.client;

import static dev.justbuild.client.ReadmeDemo.*;

import dev.justbuild.plan.BuildPlan;
import dev.justbuild.plan.Cell;
import dev.justbuild.plan.Operation;
import dev.justbuild.plan.PlanWriter;
import dev.justbuild.world.BuildRecord;
import dev.justbuild.world.BuildStore;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;

/** Opt-in recordings of setup, newer editing tools and recovery; excluded from the release jar. */
public final class ReadmeExtrasDemo implements FabricClientGameTest {
    private ReadmeDemo film;
    private DemoFixture ai;
    private TestSingleplayerContext world;
    private String player;
    private BlockPos base;

    @Override public void runTest(ClientGameTestContext c) {
        System.setProperty("java.awt.headless", "true");
        film = new ReadmeDemo(captureDirectory());
        try (DemoFixture fixture = new DemoFixture(); TestSingleplayerContext scene = c.worldBuilder().create()) {
            ai = fixture; world = scene;
            world.getClientLevel().waitForChunksRender();
            base = c.computeOnClient(mc -> mc.player.blockPosition());
            player = c.computeOnClient(mc -> mc.player.getName().getString());
            prepare(c, world, player, base); camera(c, world, player, base, 10, 8, 14);
            setup(c);
            prompts(c);
            String house = alternatives(c);
            recovery(c);
            String adopted = adopt(c);
            protection(c, adopted);
            checks(c);
            projects(c, house, adopted);
            templates(c);
            texture(c);
            ai.verify();
            film.writeManifest("clips-extras.json");
        }
    }

    private void setup(ClientGameTestContext c) {
        film.begin("12-ai-setup", "TEST AND CHOOSE YOUR AI");
        open(c, "Create"); c.clickScreenButton("Guide");
        hold(c, "Guide walks through AI setup, area, request, and preview.", "1 · AI", 22);
        film.snapshot(c, "getting-started"); c.clickScreenButton("Open AI settings");
        hold(c, "Open Manage profiles to edit a provider and model.", "Manage profiles…", 18);
        c.clickScreenButton("Manage profiles…");
        setField(c, "Model (required)", "missing-model");
        hold(c, "Test the unsaved draft before using it for a build.", "Test connection", 18);
        c.clickScreenButton("Test connection"); c.waitFor(mc -> hasResult(mc.screen), 600);
        hold(c, "A failed model check explains what to fix.", "Back to profiles", 25);
        c.clickScreenButton("Back to profiles"); setField(c, "Model (required)", "example-plan");
        c.clickScreenButton("Test connection"); c.waitFor(mc -> hasResult(mc.screen), 600);
        hold(c, "The corrected model returns valid JSON. No blocks change.", "Back to profiles", 22);
        film.snapshot(c, "profile-test"); c.clickScreenButton("Back to profiles"); c.clickScreenButton("Save");
        c.waitForScreen(JustBuildScreen.class);
        chooseMaterial(c, "Profile:"); c.waitFor(mc -> ClientState.string("profile", "").equals("Second profile"));
        hold(c, "Save keeps the active profile. Switch it here in Settings.", "Profile: Second profile", 25);
        chooseMaterial(c, "Profile:"); c.waitFor(mc -> ClientState.string("profile", "").equals("Demo"));
        close(c); finish();
    }

    private void prompts(ClientGameTestContext c) {
        film.begin("13-reusable-prompts", "WRITE AND REUSE A LONG REQUEST");
        open(c, "Create"); hold(c, "Use the expand button for a request with several lines.", "↗", 20);
        c.clickScreenButton("↗");
        String request = "Build a cozy village cottage.\nUse stone walls and a steep spruce roof.\nFace the entrance toward the path.\nLeave room for a bed, desk, and warm lighting.";
        for (String line : request.split("\n")) {
            c.runOnClient(mc -> { var edit = editor(mc.screen); edit.setValue(edit.getValue().isEmpty() ? line : edit.getValue() + "\n" + line); });
            hold(c, "Enter adds a line. Ctrl+Enter sends when you are ready.", null, 8);
        }
        film.snapshot(c, "prompt-editor"); hold(c, "Save prompt keeps this text for another build.", "Save prompt", 20);
        c.clickScreenButton("Save prompt"); c.runOnClient(mc -> editor(mc.screen).setValue(""));
        c.clickScreenButton("Reuse prompt…"); hold(c, "Choose saved text, then change it before sending.", request.lines().findFirst().orElseThrow(), 20);
        c.clickScreenButton(request.lines().findFirst().orElseThrow());
        check(c.computeOnClient(mc -> editor(mc.screen).getValue().equals(request)), "Saved prompt did not return");
        hold(c, "Reusing a prompt does not send an AI request.", "Send request", 18);
        c.clickScreenButton("Back"); close(c); open(c, "Create"); c.clickScreenButton("↗");
        check(c.computeOnClient(mc -> editor(mc.screen).getValue().equals(request)), "Draft was not retained");
        hold(c, "Your draft remains when you close and reopen the panel.", "Back", 22);
        c.runOnClient(mc -> editor(mc.screen).setValue("")); c.clickScreenButton("Back"); close(c); finish();
    }

    private String alternatives(ClientGameTestContext c) {
        area(c, base, 9); camera(c, world, player, base, 9, 6, 13); emptyHand(c, world);
        film.begin("14-alternatives-and-builder", "COMPARE OPTIONS WITH COBBLE");
        open(c, "Create"); c.clickScreenButton("Options: 1");
        film.type(c, "Request", "Design two cozy cottages with different roofs.", "Choose two options, then describe what they should share.");
        DemoFixture.Answer first = ai.answer(cottage(false), true); ai.answer(cottage(true));
        hold(c, "Each option uses separate AI calls; both wait for approval.", "Options: 2", 20);
        c.clickScreenButton("Build ×2"); close(c);
        String actor = c.computeOnClient(mc -> mc.player.getUUID().toString());
        c.waitFor(mc -> BuilderBots.position(actor) != null && BuilderBots.working(actor), 600);
        faceBot(c, actor); hold(c, "Meet Cobble. He hammers while the AI works on your request.", null, 38);
        c.waitFor(mc -> BuilderBots.walking(actor), 400);
        faceBot(c, actor); hold(c, "Cobble walks around the work area; his animation changes no blocks.", null, 24);
        first.release().countDown();
        c.waitFor(mc -> ClientState.preview() != null && ClientState.preview().options().size() == 2, 1200);
        camera(c, world, player, base, 10, 8, 14);
        hold(c, "Option 1 waits as a ghost preview. Cobble rests.", null, 22);
        command(c, "preview option 2"); hold(c, "Switch to option 2 without making another AI call.", null, 22);
        open(c, "Create"); hold(c, "Build it places only the currently selected option.", "Build it", 20);
        c.clickScreenButton("Build it"); close(c); idle(c);
        String id = selected(c); hold(c, "The chosen cottage is saved. Cobble leaves when done.", null, 24);
        film.snapshot(c, "builder-alternatives"); finish(); return id;
    }

    private void recovery(ClientGameTestContext c) {
        command(c, "new"); area(c, base.east(25), 2); camera(c, world, player, base.east(25), 10, 7, 14);
        film.begin("15-cancel-retry-undo", "STOP, RETRY, AND UNDO");
        DemoFixture.Answer waiting = ai.answer(cottage(false), true);
        open(c, "Create"); setField(c, "Request", "Build another cottage."); c.clickScreenButton("Build");
        c.waitFor(mc -> ClientState.planning() != null && waiting.started().getCount() == 0, 500);
        hold(c, "Stop request cancels planning before blocks are placed.", "Stop request", 20);
        c.clickScreenButton("Stop request"); waiting.release().countDown(); c.waitFor(mc -> ClientState.planning() == null, 500);
        ai.answer(cottage(false)); setField(c, "Request", "Build another cottage."); c.clickScreenButton("Build");
        c.waitFor(mc -> ClientState.string("retainedPlan", null) != null, 1200);
        hold(c, "This plan is wider than the small selected area.", "Retry saved plan", 24);
        int calls = ai.calls.get(); command(c, "zone expand 8");
        hold(c, "Enlarge the zone, then retry the retained plan.", "Retry saved plan", 22);
        c.clickScreenButton("Retry saved plan"); preview(c); close(c);
        hold(c, "Retry rechecks the same plan without another AI call.", null, 22);
        open(c, "Create"); c.clickScreenButton("Discard"); c.waitFor(mc -> ClientState.preview() == null);
        hold(c, "Discard leaves the world intact and keeps the plan for Retry.", "Retry saved plan", 22);
        c.clickScreenButton("Retry saved plan"); preview(c); c.clickScreenButton("Build it"); close(c); idle(c);
        check(ai.calls.get() == calls, "Retry unexpectedly called AI");
        open(c, "Create"); chooseMaterial(c, "Undo"); c.waitFor(mc -> ClientState.undoWaiting() != null);
        hold(c, "Undo asks first and names the action it will reverse.", "Confirm undo", 25);
        film.snapshot(c, "confirm-undo"); c.clickScreenButton("Confirm undo"); close(c); idle(c);
        hold(c, "Confirm undo removes this new cottage. The earlier one remains.", null, 24); finish();
    }

    private String adopt(ClientGameTestContext c) {
        BlockPos at = base.east(25); command(c, "new"); command(c, "zone clear");
        world.getServer().runOnServer(server -> {
            for (int x=-3;x<=3;x++) for(int z=-4;z<=4;z++) for(int y=0;y<=5;y++) {
                boolean shell = y==0 || y==5 || x==-3 || x==3 || z==-4 || z==4;
                var block = !shell ? Blocks.AIR : y==0 || y==5 ? Blocks.SPRUCE_PLANKS : Blocks.OAK_PLANKS;
                if (y>=2 && y<=3 && ((Math.abs(x)==3 && Math.abs(z)<=1) || (Math.abs(z)==4 && Math.abs(x)==2))) block=Blocks.GLASS;
                if (x==0 && z==4 && (y==1 || y==2)) block=Blocks.AIR;
                server.overworld().setBlock(at.offset(x,y,z),block.defaultBlockState(),Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE);
            }
        });
        camera(c, world, player, at, 10, 7, 14);
        film.begin("16-adopt-and-furnish", "ADD AN EXISTING BUILD AND FURNISH IT");
        command(c, "box wand"); command(c, "box 1 " + pos(at.offset(-3,0,-4))); command(c, "box 2 " + pos(at.offset(3,5,4)));
        c.runOnClient(mc -> mc.options.hideGui = false);
        hold(c, "Use Box mode to surround an existing building, including its roof.", null, 24);
        workshopHere(c); c.clickScreenButton("Add existing build"); setField(c, "Building name", "Village Workshop");
        hold(c, "Add build saves the original without changing its blocks.", "Add build and save original", 22);
        c.clickScreenButton("Add build and save original"); c.waitFor(mc -> ClientState.string("selectedTitle", "").equals("Village Workshop"));
        String id = selected(c); command(c, "box clear");
        BuildRecord saved = record(id);
        var ops = new ArrayList<>(saved.current().plan().operations());
        ops.add(Operation.set("crafting_table", new Cell(1,1,1)));
        ops.add(Operation.fill("bookshelf", new Cell(1,1,2), new Cell(1,2,3), false));
        ops.add(Operation.set("lantern", new Cell(1,3,2)));
        ai.answer(PlanWriter.write(new BuildPlan("Village Workshop", "Furnish the existing shell.", ops)));
        workshopHere(c); c.clickScreenButton("Plan interiors");
        setField(c, "Interior details", "Add a workbench, bookshelves, and a lantern.");
        hold(c, "Plan interiors keeps the shell and previews the room inside.", "Preview interior", 24);
        c.clickScreenButton("Preview interior"); preview(c); open(c, "Create"); c.clickScreenButton("Floors");
        for(int i=0;i<2;i++) c.clickScreenButton("Lower layer");
        hold(c, "Inspect the proposed furnishings before applying them.", null, 24);
        film.snapshot(c, "interior-planning"); c.clickScreenButton("Back"); c.clickScreenButton("Build it"); close(c); idle(c);
        open(c, "Create"); c.clickScreenButton("Manage build");
        hold(c, "The Original building checkpoint is available for restoration.", "Restore: Original building", 22);
        c.clickScreenButton("Restore: Original building"); preview(c); open(c, "Create");
        hold(c, "Build it confirms the restore, which also remains undoable.", "Build it", 20);
        c.clickScreenButton("Build it"); close(c); idle(c); finish(); return id;
    }

    private void protection(ClientGameTestContext c, String id) {
        command(c, "select #"+id); camera(c,world,player,base.east(25),10,7,14);
        film.begin("17-requirements-and-locks", "SET LIMITS AND PROTECT A PART");
        workshopHere(c); c.clickScreenButton("Requirements");
        setField(c,"Maximum height (0 = unset)","12"); clickNth(c,"Apply",0); c.waitFor(mc -> ClientState.constraints().maxHeight()==12);
        setField(c,"Target floor count (0 = unset)","1"); clickNth(c,"Apply",1);
        hold(c,"Maximum height is enforced; floor count guides the AI's design.",null,28);
        command(c,"box 1 "+pos(base.east(25).offset(-3,5,-4))); command(c,"box 2 "+pos(base.east(25).offset(3,5,4)));
        c.clickScreenButton("Preserve selected part"); setField(c,"Locked area name","Workshop roof");
        hold(c,"Give this selected roof a name, then lock it.","Lock area",22);
        c.clickScreenButton("Lock area"); c.waitFor(mc -> !ClientState.locks().isEmpty()); close(c);
        c.runOnClient(mc -> mc.options.hideGui=false);
        hold(c,"The blue outline marks blocks protected from later edits.",null,26); film.snapshot(c,"protected-roof");
        open(c,"Create"); c.clickScreenButton("Editing tools"); c.clickScreenButton("Locked areas");
        hold(c,"Unlock the named area when you want to change it again.","Unlock: Workshop roof",22);
        c.clickScreenButton("Unlock: Workshop roof"); command(c,"box clear"); command(c,"constraints clear"); close(c); finish();
    }

    private void checks(ClientGameTestContext c) {
        command(c,"new"); area(c,base.south(18),5); camera(c,world,player,base.south(18),7,5,10);
        film.begin("18-checks-and-walkability","FIND AND PREVIEW A LOCAL REPAIR");
        String plan = plan("Doorway example",Operation.fill("stone_bricks",new Cell(-2,0,-2),new Cell(2,0,2),false),
                Operation.set("oak_door[facing=north,half=lower]",new Cell(0,1,0)),Operation.set("stone_bricks",new Cell(0,1,-1)));
        ai.answer(plan); command(c,"build a small doorway with a low stone foundation"); preview(c);
        workshopHere(c); c.clickScreenButton("Walkability"); c.waitFor(mc -> ClientState.route()!=null);
        hold(c,"Check walkability before approving the proposed layout.","View markers",20);
        c.clickScreenButton("View markers");
        hold(c,"Inspect the highlighted doorway and any route warnings.",null,20);
        open(c,"Create"); chooseMaterial(c,"Checks (");
        for(int i=0;i<30 && !c.computeOnClient(mc -> buttonEnabled(mc.screen,"Preview repair"));i++) c.clickScreenButton("Next");
        hold(c,"Checks explains a possible obstruction and offers a local repair.","Highlight location",26);
        c.clickScreenButton("Highlight location"); hold(c,"Highlight location marks the exact place to inspect.",null,22);
        open(c,"Create"); chooseMaterial(c,"Checks (");
        for(int i=0;i<30 && !c.computeOnClient(mc -> buttonEnabled(mc.screen,"Preview repair"));i++) c.clickScreenButton("Next");
        c.clickScreenButton("Preview repair"); c.waitFor(mc -> ClientState.flag("qualityUndo"));
        hold(c,"The repair changes only the proposal. Revert is available.","Revert repair suggestion",22);
        film.snapshot(c,"quality-repair"); close(c); open(c,"Create"); c.clickScreenButton("Build it"); close(c); idle(c);
        workshopHere(c); c.clickScreenButton("Walkability"); c.waitFor(mc -> ClientState.route()!=null);
        hold(c,"Walkability checks routes and headroom without an AI call.","View markers",26);
        film.snapshot(c,"walkability"); c.clickScreenButton("View markers");
        hold(c,"Route checks are advisory: inspect the result in the world.",null,22);
        command(c,"walkability clear"); finish();
    }

    private void projects(ClientGameTestContext c, String house, String adopted) {
        command(c,"select #"+house); camera(c,world,player,base.east(12),25,18,30);
        film.begin("19-village-connections","GROUP BUILDINGS AND CONNECT THEM");
        workshopHere(c); c.clickScreenButton("Village projects"); setField(c,"Project name","Riverside Village");
        hold(c,"Create a project to share a style and palette across builds.","Create",22);
        c.clickScreenButton("Create"); c.waitFor(mc -> ClientState.activeProject()!=null);
        chooseMaterial(c,"✓ Riverside Village"); c.clickScreenButton("Add selected build"); c.waitTicks(8);
        c.clickScreenButton("Style: auto"); c.clickScreenButton("Shared palette…"); c.clickScreenButton("From selected build"); c.waitTicks(8);
        hold(c,"Use the selected building's materials as the shared palette.",null,24);
        film.snapshot(c,"village-palette"); c.clickScreenButton("Back");
        command(c,"select #"+adopted); c.clickScreenButton("Add selected build"); c.waitTicks(8);
        c.clickScreenButton("Buildings"); hold(c,"Each building keeps its own versions and undo.",null,22); c.clickScreenButton("Back");
        c.clickScreenButton("Connections");
        clickNth(c,"A",c.computeOnClient(mc -> ClientState.builds().stream().map(ClientState.Build::id).toList().indexOf(house)));
        clickNth(c,"B",c.computeOnClient(mc -> ClientState.builds().stream().map(ClientState.Build::id).toList().indexOf(adopted))); c.waitTicks(8);
        world.getServer().runCommand("execute as "+player+" at @s run tp @s ~ ~ ~ 180 0"); c.waitTicks(8);
        ai.answer(connectionPlan(record(house), record(adopted)));
        hold(c,"Choose A and B, then describe a path between their entrances.","Preview connection",26);
        c.clickScreenButton("Preview connection"); preview(c); camera(c,world,player,base.east(12),25,18,30);
        hold(c,"The connector previews as its own editable build.",null,26);
        open(c,"Create"); c.clickScreenButton("Build it"); close(c); idle(c);
        hold(c,"Both buildings remain intact, joined by the new path.",null,26); finish(); command(c,"project clear");
    }

    private void templates(ClientGameTestContext c) {
        command(c,"new"); area(c,base.west(17),5); camera(c,world,player,base.west(17),8,5,11);
        film.begin("20-templates-and-autobuild","START FROM A TEMPLATE OR AUTO-BUILD");
        open(c,"Templates"); hold(c,"Templates put an editable starting request into Create.",null,24);
        c.clickScreenButton("Country cottage"); c.clickScreenButton("Create");
        c.waitTicks(4); hold(c,"Change the template text before deciding to send it.","Build",24);
        open(c,"Settings"); hold(c,"Auto-build places ordinary AI results as soon as they are ready.","Auto-build: Off",24);
        c.clickScreenButton("Auto-build: Off"); ai.answer(plan("Garden seat",Operation.fill("spruce_planks",new Cell(-2,0,0),new Cell(2,0,1),false),
                Operation.fill("spruce_fence",new Cell(-2,1,1),new Cell(2,1,1),false)));
        open(c,"Create"); setField(c,"Request","Build a small garden seat."); c.clickScreenButton("Build"); close(c); idle(c);
        c.waitFor(mc -> ClientState.string("selectedTitle","").equals("Garden seat"),600);
        hold(c,"The result places directly. Undo is still available.",null,26);
        open(c,"Settings"); c.clickScreenButton("Auto-build: On");
        hold(c,"Turn Auto-build off to inspect ordinary previews again.","Auto-build: Off",22); close(c); finish();
    }

    private void texture(ClientGameTestContext c) {
        command(c,"new"); area(c,base.west(17).south(14),5); camera(c,world,player,base.west(17).south(14),8,6,10);
        ai.answer("{\"kind\":\"build\",\"title\":\"Textured courtyard\",\"summary\":\"\",\"operations\":[{\"op\":\"fill\",\"block\":\"stone_bricks\",\"mix\":[\"mossy_stone_bricks\",\"cracked_stone_bricks\"],\"from\":[-4,0,-4],\"to\":[4,0,4]}]}");
        command(c,"build a weathered stone courtyard"); preview(c);
        film.begin("21-textures-and-usage","REROLL MATERIALS AND LIMIT AI USE");
        hold(c,"This proposed courtyard has a procedural material mix.",null,22);
        workshopHere(c); c.clickScreenButton("Materials"); setField(c,"Texture seed","42");
        hold(c,"A texture seed makes the same material pattern repeatable.","Preview reroll",24);
        int calls=ai.calls.get(); c.clickScreenButton("Preview reroll"); c.waitTicks(10); close(c);
        hold(c,"Reroll changes the mix pattern without calling the AI.",null,22);
        check(ai.calls.get()==calls,"Texture reroll called AI");
        open(c,"Create"); c.clickScreenButton("Build it"); close(c); idle(c);
        workshopHere(c); c.clickScreenButton("AI usage");
        setField(c,"Maximum calls","20"); setField(c,"Maximum reported tokens","50000");
        hold(c,"Review calls, reported tokens, and time before setting limits.","Apply limits",25);
        c.clickScreenButton("Apply limits"); c.waitTicks(8);
        hold(c,"Limits stop the next call; a running call can exceed its limit.",null,26);
        film.snapshot(c,"ai-session-limits"); close(c); finish();
    }

    private void hold(ClientGameTestContext c,String text,String highlight,int frames) { film.hold(c,text,highlight,frames); }
    private void finish() { film.finish(); }
    private static void workshopHere(ClientGameTestContext c) { open(c,"Create"); c.clickScreenButton("Workshop"); }
    private static void area(ClientGameTestContext c,BlockPos p,int r) { command(c,"zone 1 "+pos(p.offset(-r,-1,-r))); command(c,"zone 2 "+pos(p.offset(r,-1,r))); }
    private static void preview(ClientGameTestContext c) { c.waitFor(mc -> ClientState.preview()!=null,1200); c.waitTicks(5); }
    private static void idle(ClientGameTestContext c) { c.waitFor(mc -> ClientState.preview()==null && ClientState.placing()==null && ClientState.planning()==null,1200); c.waitTicks(8); }
    private static String selected(ClientGameTestContext c) { return c.computeOnClient(mc -> ClientState.string("selectedBuild",null)); }
    private BuildRecord record(String id) { return world.getServer().computeOnServer(s -> new BuildStore(s.getWorldPath(LevelResource.ROOT).resolve("justbuild/builds")).loadAll().stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow()); }
    private static String plan(String title,Operation... ops) { return PlanWriter.write(new BuildPlan(title,"",List.of(ops))); }
    private static MultiLineEditBox editor(net.minecraft.client.gui.screens.Screen s) { return s.children().stream().filter(MultiLineEditBox.class::isInstance).map(MultiLineEditBox.class::cast).findFirst().orElseThrow(); }
    private static boolean hasResult(net.minecraft.client.gui.screens.Screen s) { return s != null && s.children().stream().anyMatch(w -> w instanceof Button b && b.getMessage().getString().equals("Back to profiles")); }
    private static boolean buttonEnabled(net.minecraft.client.gui.screens.Screen s,String label) { return s.children().stream().anyMatch(w -> w instanceof Button b && b.active && b.getMessage().getString().equals(label)); }
    static void clickNth(ClientGameTestContext c,String label,int n) { c.runOnClient(mc -> mc.screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast).filter(b -> b.getMessage().getString().equals(label)).skip(n).findFirst().orElseThrow().onPress(null)); c.waitTicks(4); }
    private static void check(boolean condition,String text) { if(!condition) throw new AssertionError(text); }
    private void faceBot(ClientGameTestContext c,String id) {
        Vec3 bot=c.computeOnClient(mc -> BuilderBots.position(id)); Vec3 forward=Vec3.atCenterOf(base).subtract(bot).multiply(1,0,1).normalize();
        Vec3 eye=bot.add(forward.scale(3.1)).add(forward.z*1.1,1.7,-forward.x*1.1),delta=bot.add(0,.8,0).subtract(eye);
        double height=c.computeOnClient(mc -> (double)mc.player.getEyeHeight());
        world.getServer().runCommand("tp "+player+" "+eye.x+" "+(eye.y-height)+" "+eye.z+" "+Math.toDegrees(Math.atan2(-delta.x,delta.z))+" "+-Math.toDegrees(Math.atan2(delta.y,Math.hypot(delta.x,delta.z)))); c.waitTicks(5);
    }
    private static String connectionPlan(BuildRecord a,BuildRecord b) {
        var points=new ArrayList<BlockPos>(a.baseline().keySet()); points.addAll(b.baseline().keySet());
        int minX=points.stream().mapToInt(BlockPos::getX).min().orElseThrow(),maxX=points.stream().mapToInt(BlockPos::getX).max().orElseThrow();
        int minZ=points.stream().mapToInt(BlockPos::getZ).min().orElseThrow(),maxZ=points.stream().mapToInt(BlockPos::getZ).max().orElseThrow();
        int ox=minX+(maxX-minX)/2,oz=minZ+(maxZ-minZ)/2;
        int routeZ=maxZ+3;
        int ax=a.origin().getX(),bx=b.baseline().keySet().stream().mapToInt(BlockPos::getX).min().orElseThrow()+3;
        int az=a.baseline().keySet().stream().mapToInt(BlockPos::getZ).max().orElseThrow()+1;
        int bz=b.baseline().keySet().stream().mapToInt(BlockPos::getZ).max().orElseThrow()+1;
        return plan("Village path",Operation.fill("gravel",new Cell(ax-ox-1,0,routeZ-oz),new Cell(bx-ox+1,0,routeZ-oz+2),false),
                Operation.fill("gravel",new Cell(ax-ox-1,0,az-oz),new Cell(ax-ox+1,0,routeZ-oz+2),false),
                Operation.fill("gravel",new Cell(bx-ox-1,0,bz-oz),new Cell(bx-ox+1,0,routeZ-oz+2),false));
    }
}
