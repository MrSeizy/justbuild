package dev.justbuild.prompt;

import static org.junit.jupiter.api.Assertions.*;

import dev.justbuild.plan.Cell;
import dev.justbuild.plan.InspectionRequest;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PlanImagesTest {
    @BeforeAll
    static void registries() {
        dev.justbuild.world.McBootstrap.init();
    }

    /** A 3×2×3 box: a stone front wall at z=2 with one cell (x=1, y=0) set back to z=1. */
    static VoxelVolume recessedWall() {
        return VoxelVolume.capture(new InspectionRequest(new Cell(0, 0, 0), new Cell(2, 1, 2),
                InspectionRequest.Mode.EXACT, InspectionRequest.Target.CANDIDATE), 4096, true, c -> {
                    boolean front = c.z() == 2 && !(c.x() == 1 && c.y() == 0);
                    boolean recess = c.z() == 1 && c.x() == 1 && c.y() == 0;
                    return front || recess ? "minecraft:stone" : "minecraft:air";
                });
    }

    static int brightness(BufferedImage image, int x, int y) {
        int rgb = image.getRGB(x, y);
        return ((rgb >> 16) & 0xff) + ((rgb >> 8) & 0xff) + (rgb & 0xff);
    }

    @Test
    void facadesAreDeterministicAndDarkenSetBackBlocks() throws Exception {
        VoxelVolume volume = recessedWall();
        byte[] png = PlanImages.elevations(volume);
        assertArrayEquals(png, PlanImages.elevations(volume));
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        assertEquals(768, image.getWidth());
        // South panel: origin (14, 60), 118 px cells (354/3 wide, 270/2 tall); the bottom row is y = 0.
        int row = 60 + 118 + 59;
        assertTrue(brightness(image, 14 + 118 + 59, row) < brightness(image, 14 + 59, row),
                "the recessed cell must read darker than the wall beside it");
    }

    @Test
    void elevationsAreAddedOnlyWhenAsked() {
        VoxelVolume volume = recessedWall();
        PlanningFeedback feedback = new PlanningFeedback("a".repeat(64), List.of(), volume, volume, null, null);
        assertEquals(2, PlanImages.render(feedback).size());
        assertEquals(2, PlanImages.render(feedback, false).size());
        List<byte[]> three = PlanImages.render(feedback, true);
        assertEquals(3, three.size());
        assertTrue(Arrays.equals(three.get(2), PlanImages.elevations(volume)));
        PlanningFeedback sourceOnly = new PlanningFeedback(null, List.of(), volume, null, null, null);
        assertEquals(1, PlanImages.render(sourceOnly, true).size(), "no candidate, no facades");
    }
}
