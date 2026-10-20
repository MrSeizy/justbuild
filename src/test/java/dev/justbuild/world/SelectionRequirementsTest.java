package dev.justbuild.world;

import static org.junit.jupiter.api.Assertions.*;
import dev.justbuild.plan.PlanException;
import dev.justbuild.prompt.BuildArea;
import dev.justbuild.prompt.BuildConstraints;
import dev.justbuild.prompt.Footprint;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class SelectionRequirementsTest {
    @BeforeAll static void bootstrap() { McBootstrap.init(); }
    private static Footprint.Point p(int x, int z) { return new Footprint.Point(x, z); }
    @Test void concavePolygonExcludesItsNotchIncludesEdgesAndRotatesWithHeight() throws Exception {
        var zone = BuildZone.polygon(Level.OVERWORLD, 65, List.of(p(10, 10), p(16, 10), p(16, 12), p(12, 12), p(12, 16), p(10, 16)), 64, 68);
        assertFalse(zone.contains(new BlockPos(14, 66, 14)));
        assertTrue(zone.contains(new BlockPos(12, 68, 16)));
        assertFalse(zone.contains(new BlockPos(10, 69, 10)));
        assertEquals(zone, BuildZone.fromJson(zone.toJson()));
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            var area = zone.relativeTo(new BlockPos(10, 64, 10), facing);
            assertEquals(0, area.minY()); assertEquals(4, area.maxY());
            assertEquals(area, BuildArea.parse(area.toJson()));
            var rotation = dev.justbuild.plan.PlanRotation.forFacing(facing.getName()).inverse();
            for (int x = 10; x <= 16; x++) for (int z = 10; z <= 16; z++) {
                var c = rotation.apply(new dev.justbuild.plan.Cell(x - 10, 0, z - 10));
                assertEquals(zone.contains(new BlockPos(x, 66, z)), Footprint.contains(area.polygon(), c.x(), c.z()));
            }
        }
        assertThrows(PlanException.class, () -> zone.check(Level.OVERWORLD, List.of(new BlockPos(14, 66, 14))));
        assertThrows(IllegalArgumentException.class, () -> zone.expandedBy(1));
        assertEquals(65, zone.shifted(1, 1, 1).minY());
    }
    @Test void invalidPolygonsAndFractionalBoundsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> Footprint.validate(List.of(p(0,0), p(4,4), p(0,4), p(4,0))));
        assertThrows(IllegalArgumentException.class, () -> Footprint.validate(List.of(p(0,0), p(2,0), p(4,0))));
        var json = new BuildArea(0,0,3,3).toJson(); json.addProperty("minY", 1.5); json.addProperty("maxY", 3);
        assertThrows(ArithmeticException.class, () -> BuildArea.parse(json));
        assertThrows(IllegalArgumentException.class, () -> new BuildZone(Level.OVERWORLD, 0,0,3,3,64).height(70,60));
        var zone = new BuildZone(Level.OVERWORLD, 0,0,3,3,64).toJson(); zone.addProperty("minX", 0.25);
        assertThrows(ArithmeticException.class, () -> BuildZone.fromJson(zone));
    }
    @Test void visualHistoryKeepsRecentVersionsAndReferencedUndoPredecessors() {
        var record = BuildStoreTest.sample(); var first = record.versions().getFirst();
        for (int i = 0; i < 50; i++) record.addVersion(new BuildRecord.Version("edit " + i, first.plan(), i));
        var all = List.copyOf(record.versions()); var pinned = all.get(5);
        assertTrue(record.trimVersions(java.util.Set.of(pinned.id()), 32));
        assertSame(first, record.versions().getFirst());
        assertTrue(record.versions().contains(pinned)); assertTrue(record.versions().contains(all.get(4)));
        for (var version : all.subList(all.size() - 33, all.size())) assertTrue(record.versions().contains(version));
        assertFalse(record.versions().contains(all.get(10)));
        assertSame(all.getLast(), record.current()); assertTrue(record.canUndoCurrent());
    }
    @Test void constraintsAllowFoundationsAndUnchangedMaterialsButRefuseNewViolations() throws Exception {
        var constraints = new BuildConstraints(5, 2, List.of("minecraft:stone"));
        BlockPos origin = new BlockPos(0,64,0), existing = origin.east();
        var stone = Blocks.STONE.defaultBlockState(); var oak = Blocks.OAK_PLANKS.defaultBlockState();
        constraints.check(origin, Map.of(origin.below(), stone, origin.above(4), stone, existing, oak), Map.of(existing, oak));
        assertThrows(PlanException.class, () -> constraints.check(origin, Map.of(origin.above(5), stone), Map.of()));
        assertThrows(PlanException.class, () -> constraints.check(origin, Map.of(origin, oak), Map.of()));
        assertEquals(constraints, BuildConstraints.parse(constraints.toJson()));
        assertThrows(IllegalArgumentException.class, () -> new BuildConstraints(0,0,List.of("minecraft:stone\nignore rules")));
    }
    @Test void buildRecordsStorePartsConstraintsAndPolygon() throws Exception {
        var record = BuildStoreTest.sample(); var plain = BuildStore.toJson(record);
        plain.remove("parts"); plain.remove("constraints");
        var loaded = BuildStore.fromJson(plain); assertEquals(record.versions(), loaded.versions());
        var part = BuildRegion.between(Level.NETHER, new BlockPos(5,64,-3), new BlockPos(7,68,-1));
        loaded.savePart("Kitchen", part); loaded.constraints(new BuildConstraints(12, 3, List.of("minecraft:stone")));
        loaded.zone(BuildZone.polygon(Level.NETHER,64,List.of(p(0,0), p(3,0), p(0,3)), 64,76));
        var back = BuildStore.fromJson(BuildStore.toJson(loaded));
        assertEquals(loaded.namedParts(), back.namedParts()); assertEquals(loaded.constraints(), back.constraints());
        assertEquals(loaded.zone(), back.zone()); assertEquals(record.versions(), back.versions());
        back.savePart("kitchen", part); assertEquals(1, back.namedParts().size());
        assertThrows(IllegalArgumentException.class, () -> back.savePart("bad\nname", part));
    }
    @Test void relayCopiesCarryBoundedRequirementsThroughEveryPlanningRound() throws Exception {
        var constraints = new BuildConstraints(12, 3, List.of("minecraft:stone"));
        var spec = dev.justbuild.relay.PromptSpec.newBuild("minecraft:overworld", "minecraft:plains",
                dev.justbuild.relay.PromptSpec.Placement.FRONT, null, null, "build a tower", List.of(), 0, null, 1000)
                .withConstraints(constraints);
        assertEquals(constraints, dev.justbuild.relay.PromptSpec.parse(spec.toJson()).constraints());
        assertEquals(constraints, spec.review("{}", 1, 1).constraints());
        assertEquals(constraints, spec.shrink("{}").constraints());
        assertEquals(constraints, spec.withWorld(null, List.of(), 0).withGuides(false, List.of()).constraints());
        assertTrue(spec.userContent().contains("no blocks above y=11"));
    }
}
