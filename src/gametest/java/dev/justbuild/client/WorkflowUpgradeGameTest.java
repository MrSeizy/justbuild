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
import dev.justbuild.world.BuildRecord;
import dev.justbuild.world.BuildStore;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;

/** Selection controls, real provider limits, protected repair previews, before/after, versions and schematic exchange. */
public final class WorkflowUpgradeGameTest implements FabricClientGameTest {
    private final Queue<String> replies = new ConcurrentLinkedQueue<>(), prompts = new ConcurrentLinkedQueue<>();
    private final AtomicInteger calls = new AtomicInteger();
    @Override public void runTest(ClientGameTestContext context) {
        HttpServer stub = stub(); var config = new ConfigStore(FabricLoader.getInstance().getConfigDir().resolve("justbuild.json"));
        configure(config, stub.getAddress().getPort());
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            world.getConnection().waitForChunksRender();
            BlockPos base = context.computeOnClient(mc -> mc.player.blockPosition().offset(0,18,10));
            String player = context.computeOnClient(mc -> mc.player.getName().getString());
            world.getServer().runOnServer(server -> {
                for(BlockPos p:BlockPos.betweenClosed(base.offset(-12,-1,-12),base.offset(36,8,12)))
                    server.overworld().setBlock(p,p.getY()==base.getY()-1?Blocks.STONE.defaultBlockState():Blocks.AIR.defaultBlockState(),Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE);
            });
            camera(context,world,player,base); zone(context,base,4); command(context,"zone wand");
            context.runOnClient(mc -> mc.gui.hud.getChat().clearMessages(false)); context.takeScreenshot("upgrade-wand-hud");
            // Corner relocation stays in-world; height bounds remain available through commands.
            PreviewControlsGameTest.runOnLook(context,context.computeOnClient(mc -> SelectionHandles.zoneCornerPosition(ClientState.zone(),0)));
            PreviewControlsGameTest.click(context); context.waitFor(mc -> SelectionHandles.moving());
            context.getInput().holdMouseFor(com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT, 3);
            context.waitFor(mc -> !SelectionHandles.moving());
            command(context,"zone adjust height " + (base.getY()-1) + " " + (base.getY()+4));
            context.waitFor(mc -> ClientState.zone().maxY()!=null);
            command(context,"constraints height 4"); command(context,"constraints floors 1");
            command(context,"constraints materials chain");
            check(context.computeOnClient(mc -> ClientState.constraints().materials().equals(List.of("minecraft:iron_chain"))),
                    "a legacy material ID was not canonicalized");
            command(context,"constraints materials stone,oak_door");
            context.runOnClient(mc -> mc.gui.setScreen(new ConstraintsScreen(new JustBuildScreen()))); context.takeScreenshot("upgrade-build-requirements"); close(context);
            // Each flagged draft below also gets one review call, which counts toward the limit: room for two builds.
            command(context,"usage 4 500");
            replies.add(plan("Workflow House",Operation.fill("stone",new Cell(-2,0,-2),new Cell(2,0,2),false),
                    Operation.set("oak_door[facing=north,half=lower]",new Cell(0,1,0)),Operation.set("stone",new Cell(0,1,-1))));
            command(context,"build a small house with a doorway"); preview(context);
            check(calls.get()==1 && prompts.peek().contains("Build requirements") && prompts.peek().contains("y="),"requirements did not reach the AI");
            String issue=context.computeOnClient(mc -> ClientState.qualityIssues().stream().filter(i->i.id().startsWith("DOOR:")).findFirst().orElseThrow().id());
            BlockPos obstruction=base.north().above();
            world.getServer().runOnServer(server -> server.overworld().setBlock(obstruction,Blocks.BARREL.defaultBlockState(),Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE));
            command(context,"quality repair "+issue);
            check(!context.computeOnClient(mc -> ClientState.flag("qualityUndo")),"repair bypassed a new container");
            check(state(world,obstruction).is(Blocks.BARREL),"repair changed a live container");
            world.getServer().runOnServer(server -> server.overworld().setBlock(obstruction,Blocks.AIR.defaultBlockState(),Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE));
            context.runOnClient(mc -> mc.gui.setScreen(new QualityScreen(new JustBuildScreen()))); context.takeScreenshot("upgrade-actionable-quality-check");
            context.clickScreenButton("Highlight location"); context.waitTicks(3);
            context.runOnClient(mc -> mc.gui.hud.getChat().clearMessages(false)); context.takeScreenshot("upgrade-quality-location");
            command(context,"quality repair "+issue); context.waitFor(mc -> ClientState.flag("qualityUndo"));
            check(state(world,base).isAir(),"repair suggestion placed without confirmation");
            check(context.computeOnClient(mc -> !FloorPreview.blocks().containsKey(obstruction)),"doorway suggestion did not remove the obstruction from the model");
            command(context,"quality revert"); check(context.computeOnClient(mc -> FloorPreview.blocks().get(obstruction).is(Blocks.STONE)),"revert lost the original proposal");
            command(context,"quality repair "+issue); command(context,"confirm"); awaitBlock(context,world,base,Blocks.STONE);
            check(state(world,obstruction).isAir(),"confirmed repair retained the blockage");
            context.waitFor(mc -> ClientState.string("selectedBuild",null)!=null);
            String id=context.computeOnClient(mc -> ClientState.string("selectedBuild",null));
            command(context,"rename Workflow House");
            var savedZone = record(world,id).zone();
            command(context,"zone adjust polygon start");
            check(context.computeOnClient(mc -> ClientState.polygonDraft().points().isEmpty()), "starting a polygon added a corner");
            command(context,"zone 2 "+pos(base.offset(-2,-1,-2)));
            check(context.computeOnClient(mc -> ClientState.polygonDraft().points().size()==1
                    && ClientState.polygonDraft().y()==base.getY()), "first right-click did not set the polygon ground height");
            command(context,"zone adjust polygon undo");
            check(context.computeOnClient(mc -> ClientState.polygonDraft().points().isEmpty()), "undo did not remove the first polygon corner");
            command(context,"zone adjust polygon cancel");
            check(record(world,id).zone().equals(savedZone) && context.computeOnClient(mc -> ClientState.zone().equals(savedZone)),
                    "cancelling a polygon changed the saved area");
            command(context,"box 1 "+pos(base)); command(context,"box 2 "+pos(base.offset(1,2,1)));
            context.runOnClient(mc -> mc.gui.setScreen(new NamedPartsScreen(new JustBuildScreen())));
            text(context,"Part name","Kitchen"); context.clickScreenButton("Save part"); context.waitFor(mc -> ClientState.namedParts().size()==1);
            context.takeScreenshot("upgrade-named-parts"); close(context); command(context,"box clear"); command(context,"box select Kitchen");
            check(context.computeOnClient(mc -> ClientState.part().max().equals(base.offset(1,2,1))),"saved part did not restore its box");
            command(context,"reload"); check(record(world,id).namedParts().containsKey("Kitchen"),"named part was not persisted");
            check(record(world,id).constraints().maxHeight()==4 && record(world,id).zone().maxY()==base.getY()+4,"requirements or height were not persisted");
            command(context,"box clear"); command(context,"materials replace minecraft:stone minecraft:gold_block");
            check(context.computeOnClient(mc -> ClientState.preview()==null),"local edit bypassed the allowed-material limit");
            command(context,"constraints materials any"); command(context,"materials replace minecraft:stone minecraft:gold_block"); preview(context);
            context.runOnClient(mc -> mc.gui.setScreen(new FloorPreviewScreen(new JustBuildScreen()))); context.clickScreenButton("Before");
            check(context.computeOnClient(mc -> FloorPreview.blocks().get(base).is(Blocks.STONE)),"before view is not the current world");
            context.takeScreenshot("upgrade-comparison-before"); context.clickScreenButton("After");
            check(context.computeOnClient(mc -> FloorPreview.blocks().get(base).is(Blocks.GOLD_BLOCK)),"after view is not the proposed result");
            context.clickScreenButton("Filter: all"); context.clickScreenButton("Filter: added");
            check(context.computeOnClient(mc -> GhostPreview.filter()==GhostPreview.Filter.REPLACED),"change filter did not cycle");
            context.takeScreenshot("upgrade-comparison-replacements"); close(context); command(context,"confirm"); awaitBlock(context,world,base,Blocks.GOLD_BLOCK);
            context.runOnClient(mc -> mc.gui.setScreen(new VersionHistoryScreen(new JustBuildScreen()))); context.takeScreenshot("upgrade-version-history"); close(context);
            String original=record(world,id).versions().getFirst().id(); command(context,"history preview "+original); preview(context);
            check(state(world,base).is(Blocks.GOLD_BLOCK),"version restore bypassed preview");
            command(context,"confirm"); awaitBlock(context,world,base,Blocks.STONE); command(context,"undo"); command(context,"undo confirm"); awaitBlock(context,world,base,Blocks.GOLD_BLOCK);
            command(context,"design save Workflow House"); context.waitFor(mc -> ClientState.designs().size()==1);
            context.runOnClient(mc -> mc.gui.setScreen(new DesignExportScreen(new JustBuildScreen(),ClientState.designs().getFirst())));
            context.takeScreenshot("upgrade-schematic-export"); context.clickScreenButton("Litematica · .litematic"); context.clickScreenButton("Sponge schematic · .schem");
            context.waitFor(mc -> ClientState.list("libraryFiles").stream().anyMatch(f->f.startsWith("workflow-house-")&&f.endsWith(".schem")));
            var files=context.computeOnClient(mc -> ClientState.list("libraryFiles").stream().filter(f->f.startsWith("workflow-house-")).toList());
            context.clickScreenButton("Shared library"); context.takeScreenshot("upgrade-schematic-import"); close(context);
            for(String file:files) command(context,"design import "+file+" air");
            check(context.computeOnClient(mc -> ClientState.designs().size())==3,"schematics did not import into the design library");
            check(calls.get()==1,"local tools unexpectedly called AI");
            context.runOnClient(mc -> mc.gui.setScreen(new WorkshopScreen(new JustBuildScreen()))); context.takeScreenshot("upgrade-workshop");
            context.clickScreenButton("AI usage"); context.waitTicks(25); context.takeScreenshot("upgrade-ai-usage");
            // The blocked doorway made AI review "When needed" send one review call after the draft.
            check(context.computeOnClient(mc -> ClientState.usage().calls()==2 && ClientState.usage().reviews()==1
                    && ClientState.usage().input()==200 && ClientState.usage().output()==40),"usage counters did not report the actual provider calls"); close(context);
            // A second build violates the build height, and the call budget refuses the third before the stub sees it.
            command(context,"select clear"); zone(context,base.east(20),4);
            replies.add(plan("Too tall",Operation.set("stone",new Cell(0,5,0)))); command(context,"build a too tall test marker");
            context.waitTicks(30); check(calls.get()==2 && context.computeOnClient(mc -> ClientState.preview()==null),"height restriction failed");
            replies.add(plan("Polygon notch",Operation.set("stone",new Cell(2,0,2)))); command(context,"build a capped marker"); context.waitTicks(30);
            check(calls.get()==2,"call cap allowed another provider call");
            command(context,"usage 6 1000"); command(context,"zone adjust polygon start");
            command(context,"build an unfinished polygon marker");
            check(calls.get()==2, "an empty polygon allowed a provider call");
            BlockPos corner=base.east(20).offset(-3,-1,-3); command(context,"zone 1 "+pos(corner)); command(context,"zone 2 "+pos(corner.east(6))); command(context,"zone 2 "+pos(corner.south(6)));
            context.runOnClient(mc -> mc.gui.setScreen(new PolygonScreen(new JustBuildScreen())));
            context.takeScreenshot("upgrade-polygon-controls"); context.clickScreenButton("Finish polygon"); context.waitFor(mc -> ClientState.zone()!=null && ClientState.zone().polygon().size()==3); close(context);
            camera(context,world,player,base.east(20)); command(context,"zone wand"); context.runOnClient(mc -> mc.gui.hud.getChat().clearMessages(false)); context.takeScreenshot("upgrade-polygon-outline");
            command(context,"build a polygon notch test marker"); context.waitTicks(30);
            check(calls.get()==3 && context.computeOnClient(mc -> ClientState.preview()==null),"polygon boundary did not reject an outside block");
            check(state(world,base.east(22).south(2)).isAir(),"rejected polygon plan touched the world");
        } finally {
            stub.stop(0); try { config.save(JustBuildConfig.defaults()); } catch(Exception e) { throw new AssertionError(e); }
        }
    }
    private static BuildRecord record(TestSingleplayerContext world,String id) {
        return world.getServer().computeOnServer(server -> new BuildStore(server.getWorldPath(LevelResource.ROOT).resolve("justbuild/builds")).loadAll().stream().filter(r->r.id().equals(id)).findFirst().orElseThrow());
    }
    private static void camera(ClientGameTestContext c,TestSingleplayerContext w,String player,BlockPos p) { w.getServer().runCommand("gamemode spectator "+player); w.getServer().runCommand("tp "+player+" "+(p.getX()+0.5)+" "+(p.getY()+6)+" "+(p.getZ()+9.5)+" 180 30"); c.waitTicks(10); }
    private static String pos(BlockPos p) { return p.getX()+" "+p.getY()+" "+p.getZ(); }
    private static void zone(ClientGameTestContext c,BlockPos p,int r) { command(c,"zone 1 "+pos(p.offset(-r,-1,-r))); command(c,"zone 2 "+pos(p.offset(r,-1,r))); }
    private static void command(ClientGameTestContext c,String command) { c.runOnClient(mc -> mc.player.connection.sendCommand("justbuild "+command)); c.waitTicks(8); }
    private static void close(ClientGameTestContext c) { c.runOnClient(mc -> mc.gui.setScreen(null)); }
    private static void text(ClientGameTestContext c,String label,String value) { c.runOnClient(mc -> ((EditBox)mc.gui.screen().children().stream().filter(w->w instanceof EditBox e && e.getMessage().getString().equals(label)).findFirst().orElseThrow()).setValue(value)); }
    private static void preview(ClientGameTestContext c) { c.waitFor(mc -> ClientState.preview()!=null,1200); c.waitTicks(5); }
    private static BlockState state(TestSingleplayerContext w,BlockPos p) { return w.getServer().computeOnServer(s->s.overworld().getBlockState(p)); }
    private static void awaitBlock(ClientGameTestContext c,TestSingleplayerContext w,BlockPos p,Block b) { for(int i=0;i<100&&!state(w,p).is(b);i++) c.waitTicks(5); check(state(w,p).is(b),"wrong block at "+p+": "+state(w,p)); c.waitTicks(10); }
    private static String plan(String title,Operation... operations) { return PlanWriter.write(new BuildPlan(title,"",List.of(operations))); }
    private static void check(boolean condition,String message) { if(!condition) throw new AssertionError(message); }
    private static void configure(ConfigStore store,int port) {
        try { var c=JustBuildConfig.defaults(); var profile=new JustBuildConfig.ModelProfile("Stub","openai","stub"); profile.baseUrl="http://127.0.0.1:"+port+"/v1";
            c.replaceProfiles(List.of(profile),"Stub"); c.detail="quick"; c.guides=false; c.creativeDirection=false; c.previewAbove=0; store.save(c);
            var library=FabricLoader.getInstance().getGameDir().resolve("justbuild/library");
            if(java.nio.file.Files.isDirectory(library)) try(var files=java.nio.file.Files.newDirectoryStream(library,"workflow-house-*")) { for(var file:files) java.nio.file.Files.delete(file); }
        } catch(Exception e) { throw new AssertionError(e); }
    }
    private HttpServer stub() {
        try { var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/v1/chat/completions",exchange->{ String body=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8), review=StubReview.draft(body);
                if(review==null) { prompts.add(body); calls.incrementAndGet(); }
                var message=new JsonObject(); String next=review!=null?review:replies.poll(); message.addProperty("content",next==null?"{\"kind\":\"decline\",\"summary\":\"Unexpected call\"}":next);
                var choice=new JsonObject(); choice.add("message",message); var choices=new JsonArray(); choices.add(choice); var reply=new JsonObject(); reply.add("choices",choices);
                var usage=new JsonObject(); usage.addProperty("prompt_tokens",100); usage.addProperty("completion_tokens",20); reply.add("usage",usage);
                byte[] bytes=reply.toString().getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().add("Content-Type","application/json"); exchange.sendResponseHeaders(200,bytes.length); exchange.getResponseBody().write(bytes); exchange.close(); }); server.start(); return server;
        } catch(Exception e) { throw new AssertionError(e); }
    }
}
