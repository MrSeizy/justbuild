package dev.justbuild.client;

import dev.justbuild.config.JustBuildConfig;
import dev.justbuild.config.ConfigStore;
import java.nio.file.Files;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;

/** A config file the server couldn't read is never overwritten by a settings change; a reload that reads it unlocks saving. */
public final class ConfigGuardGameTest implements FabricClientGameTest {
    private static final String BROKEN = "{ \"activeProfile\": \"Mine\", \"timeoutSeconds\": \"abc\", ";

    @Override
    public void runTest(ClientGameTestContext context) {
        var file = FabricLoader.getInstance().getConfigDir().resolve("justbuild.json");
        byte[] original;
        try { original = Files.exists(file) ? Files.readAllBytes(file) : null; }
        catch (Exception e) { throw new AssertionError(e); }
        try {
            Files.writeString(file, BROKEN);
            try (TestSingleplayerContext world = context.worldBuilder().create()) {
                world.getConnection().waitForChunksRender();
                command(context, "style nordic");
                command(context, "playerai prefer");
                check(saw(context, "Settings weren't changed"), "a settings change was not refused");
                check(Files.readString(file).equals(BROKEN), "the unreadable config file was overwritten");
                check(context.computeOnClient(mc -> ClientState.string("playerAi", "").equals("require")),
                        "the policy changed while the config was unreadable");

                JustBuildConfig fixed = JustBuildConfig.defaults();
                new ConfigStore(file).save(fixed);
                command(context, "reload");
                command(context, "style nordic");
                command(context, "playerai prefer");
                check(new ConfigStore(file).load().style.equals("nordic"), "after a reload that read the file, the change did not save");
                check(new ConfigStore(file).load().playerAi.equals("prefer"), "the policy did not save after config recovery");
                corruptBuilds(context, world, file);
            }
        } catch (Exception e) {
            throw new AssertionError("Could not exercise the config guard", e);
        } finally {
            try {
                if (original == null) Files.deleteIfExists(file);
                else Files.write(file, original);
            } catch (Exception e) { throw new AssertionError("Could not restore test config", e); }
        }
    }

    private static void corruptBuilds(ClientGameTestContext context, TestSingleplayerContext world, java.nio.file.Path configFile)
            throws Exception {
        var data = Files.createTempDirectory("justbuild-corrupt-builds-");
        try {
            Files.createDirectories(data.resolve("builds"));
            var broken = data.resolve("builds/12345678.json.gz"); Files.writeString(broken, "not gzip");
            world.getServer().runOnServer(server -> {
                var manager = new dev.justbuild.session.BuildSessionManager(server, new ConfigStore(configFile),
                        JustBuildConfig.defaults(), new dev.justbuild.session.PlanStore(data.resolve("plans")), data);
                try {
                    var source = server.getPlayerList().getPlayers().getFirst().createCommandSourceStack();
                    manager.start(source, "build after corrupted ownership data");
                    manager.addExisting(source, "must also be refused");
                } finally { manager.shutdown(); }
            });
            context.waitTicks(10);
            check(saw(context, "saved builds could not be read"), "corrupt saved ownership did not disable placement");
            check(Files.readString(broken).equals("not gzip"), "the corrupt record was not preserved for recovery");
        } finally {
            try (var paths = Files.walk(data)) {
                for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static boolean saw(ClientGameTestContext context, String text) {
        return context.computeOnClient(mc -> ClientState.lines().stream().anyMatch(line -> line.getString().contains(text)));
    }

    private static void command(ClientGameTestContext context, String text) {
        context.runOnClient(mc -> mc.player.connection.sendCommand("justbuild " + text));
        context.waitTicks(10);
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
