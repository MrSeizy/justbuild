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

    static VoxelVolume box(int nx, int ny, int nz, int budget, boolean exact, java.util.function.Function<Cell, String> lookup) {
        return VoxelVolume.capture(new InspectionRequest(new Cell(0, 0, 0), new Cell(nx - 1, ny - 1, nz - 1),
                InspectionRequest.Mode.EXACT, InspectionRequest.Target.CANDIDATE), budget, exact, lookup);
    }

    static final int BACKGROUND = 0x15 + 0x1b + 0x23;

    /** Centre pixel of column {@code col} (of {@code width}) on the bottom row of panel {@code side}, with ny rows. */
    static int[] centre(int side, int col, int width, int ny) {
        double scale = Math.min(354.0 / width, 270.0 / ny);
        int ox = side % 2 == 0 ? 14 : 400, oy = (side < 2 ? 40 : 340) + 20;
        return new int[] {(int) (ox + (col + 0.5) * scale), (int) (oy + (ny - 1 + 0.5) * scale)};
    }

    static boolean yellowish(BufferedImage image, int side, int col, int width, int ny) {
        int[] p = centre(side, col, width, ny);
        int rgb = image.getRGB(p[0], p[1]);
        return ((rgb >> 16) & 0xff) > 150 && ((rgb >> 8) & 0xff) > 150 && (rgb & 0xff) < 120;
    }

    @Test
    void everySideShowsTheCornerBlockOnTheCorrectEnd() throws Exception {
        // 4 (x) by 1 (y) by 2 (z): one gold block at the far corner (3, 0, 1).
        VoxelVolume volume = box(4, 1, 2, 4096, true, c -> c.x() == 3 && c.z() == 1 ? "minecraft:gold_block" : "minecraft:air");
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(PlanImages.elevations(volume)));
        // South (viewer at +z): x ascends, so x=3 is the rightmost of 4.
        assertTrue(yellowish(image, 0, 3, 4, 1));
        assertFalse(yellowish(image, 0, 0, 4, 1));
        // East (viewer at +x): z descends, so z=1 is the leftmost of 2.
        assertTrue(yellowish(image, 1, 0, 2, 1));
        assertFalse(yellowish(image, 1, 1, 2, 1));
        // North (viewer at -z): x descends, so x=3 is the leftmost of 4.
        assertTrue(yellowish(image, 2, 0, 4, 1));
        assertFalse(yellowish(image, 2, 3, 4, 1));
        // West (viewer at -x): z ascends, so z=1 is the rightmost of 2.
        assertTrue(yellowish(image, 3, 1, 2, 1));
        assertFalse(yellowish(image, 3, 0, 2, 1));
        int[] empty = centre(0, 0, 4, 1);
        assertEquals(BACKGROUND, brightness(image, empty[0], empty[1]));
    }

    @Test
    void darkAndSetBackBlocksStayVisible() throws Exception {
        VoxelVolume wool = box(1, 1, 1, 4096, true, c -> "minecraft:black_wool");
        BufferedImage a = ImageIO.read(new ByteArrayInputStream(PlanImages.elevations(wool)));
        int[] p = centre(0, 0, 1, 1);
        assertTrue(brightness(a, p[0], p[1]) - BACKGROUND >= 60, "black wool must not vanish into the background");
        // Stone at the front on the top row, and another stone block set back by 5 on the bottom row.
        VoxelVolume deep = box(1, 2, 6, 4096, true, c -> c.y() == 1 && c.z() == 5 || c.y() == 0 && c.z() == 0
                ? "minecraft:stone" : "minecraft:air");
        BufferedImage b = ImageIO.read(new ByteArrayInputStream(PlanImages.elevations(deep)));
        int[] q = centre(0, 0, 1, 2);
        assertTrue(brightness(b, q[0], q[1]) - BACKGROUND >= 60, "a block set back by 5 must stay distinct");
    }

    @Test
    void sampledVolumesStillDrawFacades() throws Exception {
        VoxelVolume sampled = box(8, 4, 8, 64, false, c -> "minecraft:stone");
        assertTrue(sampled.step() > 1);
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(PlanImages.elevations(sampled)));
        int[] p = centre(0, 0, sampled.nx(), sampled.ny());
        assertTrue(brightness(image, p[0], p[1]) - BACKGROUND >= 60);
    }
}
