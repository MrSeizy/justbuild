package dev.justbuild.plan;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PlanWriterTest {
    static final BuildPlan EVERY_OP = new BuildPlan("Everything", "One of each.", List.of(
            Operation.set("stone", new Cell(1, 2, 3)).withRepeat(new Repeat(3, new Cell(2, 0, 0))),
            Operation.fill("oak_planks", new Cell(0, 0, 0), new Cell(4, 3, 4), true).withMirror(new MirrorPlane("x", -0.5)),
            Operation.line("oak_fence", new Cell(0, 0, 0), new Cell(0, 5, 0)),
            Operation.sphere("glass", new Cell(0, 8, 0), 3.5, "dome", true),
            Operation.ellipsoid("pink_wool", new Cell(0, 5, 0), new Radii(3, 2, 4.5), "full", false),
            Operation.cylinder("stone_bricks", new Cell(5, 0, 5), 2, 7, true),
            Operation.roof("oak_stairs", "oak_planks", new Cell(0, 4, 0), new Cell(4, 4, 6), "gable", null),
            Operation.roof("spruce_stairs", null, new Cell(0, 4, 0), new Cell(4, 4, 4), "hip", "z"),
            Operation.stairs("stone_stairs", null, new Cell(0, 0, 0), new Cell(1, 0, 0), "west", 4),
            Operation.arch("air", new Cell(0, 0, 0), 1.5, 2, "z", true),
            Operation.voxels(Map.of('P', "pink_wool", 'e', "black_wool"), new Cell(0, 0, 0),
                    List.of(List.of("PeP", "P.P"), List.of(" P "))),
            Operation.replace("dirt_path", "grass_block", new Cell(-3, -1, -3), new Cell(3, -1, 3)),
            Operation.foundation("cobblestone", new Cell(0, -1, 0), new Cell(4, -1, 4))));

    @Test
    void roundTripsEveryOperationType() throws Exception {
        var parsed = (PlanResponse.Build) PlanParser.parse(PlanWriter.write(EVERY_OP));
        assertEquals(EVERY_OP, parsed.plan());
    }

    @Test
    void writesOnlyTheVariantFields() {
        var json = PlanWriter.operation(Operation.set("stone", new Cell(0, 0, 0)));
        assertEquals(List.of("op", "block", "from", "repeat", "mirror"), List.copyOf(json.keySet()));
    }
}
