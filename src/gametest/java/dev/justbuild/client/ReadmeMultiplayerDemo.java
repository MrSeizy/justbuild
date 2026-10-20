package dev.justbuild.client;

import static dev.justbuild.client.ReadmeDemo.*;

import com.mojang.authlib.GameProfile;
import dev.justbuild.JustBuildMod;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/** A real dedicated connection, with a server-side collaborator fixture for the co-building segment. */
public final class ReadmeMultiplayerDemo implements FabricClientGameTest {
    // A made-up teammate: no real account has this UUID or joins the demo server.
    private static final UUID FRIEND = UUID.fromString("9931d8e0-34e0-4ca7-9b88-6ec6c776733b");
    private static final String FRIEND_NAME = "GuestBuilder";
    private ReadmeDemo film;
    private BlockPos base;
    private String player;

    @Override public void runTest(ClientGameTestContext c) {
        System.setProperty("java.awt.headless", "true");
        film = new ReadmeDemo(captureDirectory());
        try (var ai = new DemoFixture(); TestDedicatedServerContext server = c.worldBuilder().createServer();
                var connection = server.connect()) {
            connection.getClientLevel().waitForChunksRender();
            base = c.computeOnClient(mc -> mc.player.blockPosition());
            player = c.computeOnClient(mc -> mc.player.getName().getString());
            server.runCommand("op " + player); server.runCommand("time set noon"); server.runCommand("weather clear");
            server.runOnServer(s -> {
                for (BlockPos p : BlockPos.betweenClosed(base.offset(-18,-1,-18),base.offset(18,16,18)))
                    s.overworld().setBlock(p,p.getY()==base.getY()-1 ? Blocks.GRASS_BLOCK.defaultBlockState():Blocks.AIR.defaultBlockState(),Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE);
            });
            c.waitTicks(25); camera(c,server);
            command(c,"zone 1 "+pos(base.offset(-9,-1,-9))); command(c,"zone 2 "+pos(base.offset(9,-1,9)));
            // Dismiss this disposable server's login/tutorial notifications before recording the controls.
            c.runOnClient(mc -> mc.getToastManager().clear()); c.waitTicks(4);
            film.begin("22-multiplayer-ai","USE YOUR OWN AI ON A SERVER");
            open(c,"Settings");
            film.hold(c,"Install matching JustBuild versions on the client and server.",null,22);
            film.hold(c,"My profiles edits the AI account on this computer.","My profiles…",22);
            c.clickScreenButton("My profiles…");
            film.hold(c,"Your API key or CLI login stays on your computer.","Save",22);
            c.clickScreenButton("Save"); c.waitForScreen(JustBuildScreen.class);
            film.hold(c,"Local AI starts disabled for each server. Opt in here.","Use my AI here: Off",24);
            c.clickScreenButton("Use my AI here: Off"); c.waitTicks(12);
            c.clickScreenButton("Review before sending: Off");
            film.hold(c,"Optional response review lets you inspect each outgoing reply.","Review before sending: On",24);
            film.snapshot(c,"multiplayer-ai-settings");
            open(c,"Create"); setField(c,"Request","Build a small stone cottage for this village."); ai.answer(cottage(false));
            c.clickScreenButton("Build"); c.waitForScreen(ResponseReviewScreen.class);
            film.hold(c,"This response is still local. Inspect it before sending.","Send to server",28);
            film.snapshot(c,"multiplayer-response-review"); c.clickScreenButton("Send to server");
            for(int round=0;round<6;round++) {
                c.waitFor(mc -> ClientState.preview()!=null || mc.screen instanceof ResponseReviewScreen,1200);
                if(c.computeOnClient(mc -> ClientState.preview()!=null)) break;
                film.hold(c,"Additional AI rounds wait for approval separately.","Send to server",18);
                c.clickScreenButton("Send to server");
            }
            c.waitFor(mc -> ClientState.preview()!=null,1200); close(c); camera(c,server);
            film.hold(c,"The server validates the reply and sends a normal build preview.",null,25);
            open(c,"Create"); c.clickScreenButton("Build it"); close(c);
            c.waitFor(mc -> ClientState.preview()==null && ClientState.placing()==null && ClientState.string("selectedBuild",null)!=null,1200);
            String house=c.computeOnClient(mc -> ClientState.string("selectedBuild",null));
            film.hold(c,"The server places the blocks using your approved plan.",null,26); film.finish();

            film.begin("23-team-building","SHARE A PROJECT AND KEEP YOUR OWN UNDO");
            open(c,"Create"); c.clickScreenButton("Workshop"); c.clickScreenButton("Village projects");
            setField(c,"Project name","Shared village"); c.clickScreenButton("Create");
            c.waitFor(mc -> ClientState.activeProject()!=null); chooseMaterial(c,"✓ Shared village");
            c.clickScreenButton("Add selected build"); c.waitTicks(8); c.clickScreenButton("Team");
            film.hold(c,"The project owner invites builders through Team.","Invite",22);
            // As if the teammate had joined this server before: it remembers their name while they're offline.
            server.runOnServer(s -> s.services().nameToIdCache().add(new NameAndId(FRIEND,FRIEND_NAME)));
            setField(c,"Player name",FRIEND_NAME);
            film.hold(c,"Use the name of anyone who joined this server recently, or their UUID.","Invite",22);
            c.clickScreenButton("Invite"); c.waitFor(mc -> ClientState.projects().stream().anyMatch(p -> !p.team().isEmpty()));
            film.hold(c,"Team members may edit the buildings shared with this project.","Remove",26);
            film.snapshot(c,"project-team"); close(c);
            friend(server,house,(m,s)-> { m.replaceMaterial(s,"stone_bricks","deepslate_bricks"); m.confirm(s); });
            waitForIdle(c,server,house);
            film.hold(c,"A simulated teammate changes the walls through the real edit tools.",null,30);
            friend(server,house,(m,s)-> { m.undo(s); m.confirmUndo(s); });
            waitForIdle(c,server,house);
            film.hold(c,"Their own undo reverses that edit and keeps the original build.",null,28);
            open(c,"Create"); c.clickScreenButton("Workshop"); c.clickScreenButton("Village projects"); chooseMaterial(c,"✓ Shared village"); c.clickScreenButton("Team");
            c.clickScreenButton("Remove"); c.waitFor(mc -> ClientState.projects().stream().allMatch(p -> p.team().isEmpty()));
            film.hold(c,"Removing a member revokes their permission to edit this project.",null,26);
            close(c); film.finish(); ai.verify(); film.writeManifest("clips-multiplayer.json");
        }
    }

    private void camera(ClientGameTestContext c,TestDedicatedServerContext server) {
        server.runCommand("gamemode spectator "+player);
        server.runCommand("tp "+player+" "+(base.getX()+10.5)+" "+(base.getY()+8)+" "+(base.getZ()+14.5)+" facing "+(base.getX()+.5)+" "+(base.getY()+3)+" "+(base.getZ()+.5));
        close(c); c.waitTicks(8);
    }
    private void friend(TestDedicatedServerContext server,String build,
            java.util.function.BiConsumer<dev.justbuild.session.BuildSessionManager,net.minecraft.commands.CommandSourceStack> action) {
        server.runOnServer(s -> {
            var friend=new Collaborator(s.overworld()); friend.setPos(base.getX(),base.getY()+1,base.getZ()+12);
            var source=friend.createCommandSourceStack(); JustBuildMod.manager().selectBuild(source,build); action.accept(JustBuildMod.manager(),source);
        });
    }
    private static void waitForIdle(ClientGameTestContext c,TestDedicatedServerContext server,String house) {
        for(int i=0;i<300 && server.computeOnServer(s -> JustBuildMod.manager().isBusy(house));i++) c.waitTicks(2);
        if(server.computeOnServer(s -> JustBuildMod.manager().isBusy(house))) throw new AssertionError("Collaborator action did not finish");
        c.waitTicks(8);
    }
    private static final class Collaborator extends FakePlayer {
        Collaborator(ServerLevel level) { super(level,new GameProfile(FRIEND,FRIEND_NAME)); }
        @Override public net.minecraft.server.permissions.PermissionSet permissions() { return net.minecraft.server.permissions.LevelBasedPermissionSet.GAMEMASTER; }
        @Override public void sendSystemMessage(Component message) { System.out.println("DEMO TEAM: "+message.getString()); }
    }
}
