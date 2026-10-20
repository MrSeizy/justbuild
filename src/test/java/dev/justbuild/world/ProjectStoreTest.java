package dev.justbuild.world;

import static org.junit.jupiter.api.Assertions.*;

import dev.justbuild.prompt.ProjectTheme;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectStoreTest {
    static final String OWNER = "11111111-1111-1111-1111-111111111111";
    static final String FRIEND = "22222222-2222-2222-2222-222222222222";
    static final String STRANGER = "33333333-3333-3333-3333-333333333333";

    @TempDir
    Path dir;

    @BeforeAll
    static void bootstrap() {
        McBootstrap.init();
    }

    static ProjectStore.Project team(List<String> builds, Map<String, String> collaborators) {
        return new ProjectStore.Project("abcdef12", OWNER, "Riverside", Level.OVERWORLD, ProjectTheme.EMPTY, builds,
                collaborators);
    }

    static BuildRecord build(String id, String owner) {
        return BuildRegistryTest.record(id, owner, BlockPos.ZERO, 1, 1);
    }

    @Test
    void collaboratorsSurviveSaving() throws Exception {
        Map<String, String> collaborators = new LinkedHashMap<>();
        collaborators.put(FRIEND, "Alex");
        var project = team(List.of("abcdef34"), collaborators);
        var store = new ProjectStore(dir.resolve("projects.json"));
        store.save(List.of(project));
        assertEquals(List.of(project), store.load());
        assertEquals(Map.of(FRIEND, "Alex"), store.load().getFirst().collaborators());
    }

    @Test
    void collaboratorsMustBePlayersAndFew() {
        assertThrows(IllegalArgumentException.class, () -> team(List.of(), Map.of("not-a-uuid", "Alex")));
        assertThrows(IllegalArgumentException.class, () -> team(List.of(), Map.of(OWNER, "Me")), "the owner isn't a collaborator");
        Map<String, String> many = new LinkedHashMap<>();
        for (int i = 0; i <= ProjectStore.MAX_COLLABORATORS; i++) {
            many.put(String.format("00000000-0000-0000-0000-%012d", i), "P" + i);
        }
        assertThrows(IllegalArgumentException.class, () -> team(List.of(), many));
    }

    @Test
    void ownersAndTheirProjectTeamsMayChangeABuild() {
        BuildRecord house = build("abcdef34", OWNER);
        BuildRecord barn = build("abcdef56", FRIEND);
        BuildRecord shed = build("abcdef78", STRANGER);
        var project = team(List.of("abcdef34", "abcdef56"), Map.of(FRIEND, "Alex"));
        List<ProjectStore.Project> projects = List.of(project);
        assertTrue(ProjectStore.mayModify(OWNER, house, projects), "a build's owner");
        assertTrue(ProjectStore.mayModify(FRIEND, house, projects), "a collaborator, on a project build");
        assertTrue(ProjectStore.mayModify(OWNER, barn, projects), "the project owner, on a collaborator's project build");
        assertFalse(ProjectStore.mayModify(STRANGER, house, projects), "someone outside the project");
        assertFalse(ProjectStore.mayModify(FRIEND, shed, projects), "a build outside the project");
        assertTrue(ProjectStore.mayModify(STRANGER, shed, projects));
    }

    @Test
    void editingPermissionDoesNotGrantTheRightToReshare() {
        BuildRecord house = build("abcdef34", OWNER);
        var shared = team(List.of(house.id()), Map.of(FRIEND, "Alex"));
        var friendsProject = new ProjectStore.Project("abcdef56", FRIEND, "Mine", Level.OVERWORLD,
                ProjectTheme.EMPTY, List.of());
        assertTrue(ProjectStore.mayModify(FRIEND, house, List.of(shared)));
        assertFalse(ProjectStore.mayChangeMembership(FRIEND, house, shared, false));
        assertFalse(ProjectStore.mayChangeMembership(FRIEND, house, friendsProject, true));
        assertTrue(ProjectStore.mayChangeMembership(OWNER, house, shared, false));
        assertTrue(ProjectStore.mayChangeMembership(OWNER, house, friendsProject, true));
        BuildRecord contribution = build("abcdef78", FRIEND);
        assertTrue(ProjectStore.mayChangeMembership(OWNER, contribution, shared, false));
        assertFalse(ProjectStore.mayChangeMembership(OWNER, contribution, shared, true));
    }
}
