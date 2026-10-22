package dev.justbuild.session;

import static org.junit.jupiter.api.Assertions.*;
import dev.justbuild.plan.*;
import dev.justbuild.prompt.BuildConstraints;
import dev.justbuild.world.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreviewStoreTest {
    private static final String ACTOR = "11111111-1111-1111-1111-111111111111";
    @TempDir Path dir;
    @BeforeAll static void bootstrap() { McBootstrap.init(); }
    private static PreviewStore.Choice choice(String title, boolean edit) {
        BuildPlan plan = new BuildPlan(title, "Keep this summary", List.of(Operation.fill("gold_block", new Cell(-1, 0, 0), new Cell(1, 0, 0), false)));
        BlockPos origin = new BlockPos(8, 70, 12);
        return new PreviewStore.Choice(plan, "Request\nwith answers", "Original request", Level.OVERWORLD, origin,
                Direction.WEST, edit ? "0123abcd" : null, edit ? "source-version" : null,
                BuildZone.between(Level.OVERWORLD, origin.offset(-5, 0, -5), origin.offset(5, 0, 5)),
                BuildRegion.between(Level.OVERWORLD, origin, origin.above(2)),
                List.of(BuildRegion.between(Level.OVERWORLD, origin.east(3), origin.east(4))),
                new BuildConstraints(12, 2, List.of("minecraft:gold_block")), "project-id", edit ? plan : null);
    }
    @Test void alternativesFrameScopeAndLocalChangesSurviveAReopenedStore() throws Exception {
        PreviewStore.Saved expected = new PreviewStore.Saved(List.of(choice("One", false), choice("Two", true), choice("Three", false)), 1);
        new PreviewStore(dir).save(ACTOR, expected);
        PreviewStore reopened = new PreviewStore(dir);
        assertEquals(expected, reopened.load(ACTOR));
        assertNull(reopened.load("22222222-2222-2222-2222-222222222222"));
        reopened.delete(ACTOR);
        assertNull(new PreviewStore(dir).load(ACTOR));
    }
    @Test void corruptOrUnsupportedFilesArePreservedUntilExplicitDeletion() throws Exception {
        PreviewStore store = new PreviewStore(dir);
        Path file = dir.resolve(ACTOR + ".json.gz");
        Files.writeString(file, "unreadable original");
        assertThrows(IOException.class, () -> store.load(ACTOR));
        assertThrows(IOException.class, () -> store.save(ACTOR, new PreviewStore.Saved(List.of(choice("New", false)), 0)));
        assertEquals("unreadable original", Files.readString(file));
        for (String json : List.of("{\"format\":2,\"owner\":\"" + ACTOR + "\"}",
                "{\"format\":1,\"owner\":\"console\"}", "{\"format\":1,\"owner\":\"" + ACTOR + "\",\"selected\":0,\"choices\":[]}")) {
            try (var out = new GZIPOutputStream(Files.newOutputStream(file))) { out.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
            byte[] original = Files.readAllBytes(file);
            assertThrows(IOException.class, () -> store.load(ACTOR));
            assertArrayEquals(original, Files.readAllBytes(file));
        }
        store.delete(ACTOR);
        store.save(ACTOR, new PreviewStore.Saved(List.of(choice("New", false)), 0));
        assertEquals("New", store.load(ACTOR).title());
    }
    @Test void boundedChoicesAndPlainActorNames() {
        PreviewStore store = new PreviewStore(dir);
        assertThrows(IllegalArgumentException.class, () -> store.load("../outside"));
        assertThrows(IllegalArgumentException.class, () -> new PreviewStore.Saved(Collections.nCopies(4, choice("One", false)), 0));
        assertThrows(IllegalArgumentException.class, () -> new PreviewStore.Saved(List.of(choice("One", false)), 1));
    }
}
