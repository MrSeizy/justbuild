package dev.justbuild.plan;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class TextureSettingsTest {
    private static final BuildPlan MIXED = new BuildPlan("Weathered paving", "", List.of(
            Operation.fill("minecraft:stone", new Cell(0, 0, 0), new Cell(20, 0, 20), false).withMix("minecraft:andesite")));

    @Test void seedChangesMaterialsWithoutChangingGeometryAndRoundTrips() throws Exception {
        var first = MIXED.withTexture(new TextureSettings(42, Map.of()));
        var second = first.withTexture(new TextureSettings(43, Map.of()));
        var a = draw(first); var b = draw(second);
        assertEquals(a.keySet(), b.keySet()); assertNotEquals(a, b);
        assertEquals(a, draw(first));
        var parsed = ((PlanResponse.Build) PlanParser.parse(PlanWriter.write(first))).plan();
        assertEquals(first.texture(), parsed.texture()); assertEquals(a, draw(parsed));
        assertEquals(Rasterizer.rasterize(MIXED.operations(), 1000), draw(MIXED), "legacy plans retain their appearance");
    }

    @Test void zeroAndFullWeightsControlExistingPaletteOnly() throws Exception {
        var onlyStone = MIXED.withTexture(new TextureSettings(12, Map.of("minecraft:andesite", 0)));
        assertTrue(draw(onlyStone).values().stream().allMatch(s -> s.spec().equals("minecraft:stone")));
        var onlyAndesite = MIXED.withTexture(new TextureSettings(12, Map.of("minecraft:stone", 0)));
        assertTrue(draw(onlyAndesite).values().stream().allMatch(s -> s.spec().equals("minecraft:andesite")));
        assertEquals(Set.of("minecraft:stone", "minecraft:andesite"), PlanTextures.materials(MIXED));
        assertThrows(IllegalArgumentException.class, () -> new TextureSettings(0, Map.of("minecraft:stone", 101)));
        assertThrows(ArithmeticException.class, () -> TextureSettings.parse(com.google.gson.JsonParser.parseString("{\"seed\":1.1,\"weights\":[]}")));
    }

    private Map<Cell, BlockRef> draw(BuildPlan plan) throws PlanException {
        return Rasterizer.rasterize(plan.operations(), 1000, WorldQuery.NONE, plan.texture());
    }
}
