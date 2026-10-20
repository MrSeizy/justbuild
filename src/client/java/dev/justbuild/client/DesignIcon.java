package dev.justbuild.client;

import dev.justbuild.world.DesignThumbnail;
import net.minecraft.client.gui.GuiGraphicsExtractor;

final class DesignIcon {
    private DesignIcon() { }
    static void draw(GuiGraphicsExtractor graphics, DesignThumbnail.Image image, int x, int y, int size) {
        graphics.fill(x - 1, y - 1, x + size + 1, y + size + 1, 0xFF555555);
        graphics.fill(x, y, x + size, y + size, 0xFF20252B);
        for (int row = 0; row < DesignThumbnail.SIZE; row++) for (int col = 0; col < DesignThumbnail.SIZE; col++) {
            char pixel = image.pixels().charAt(row * DesignThumbnail.SIZE + col);
            if (pixel == '.') continue;
            int color = 0xFF000000 | image.colors().get(Character.digit(pixel, 16));
            graphics.fill(x + col * size / DesignThumbnail.SIZE, y + row * size / DesignThumbnail.SIZE,
                    x + (col + 1) * size / DesignThumbnail.SIZE, y + (row + 1) * size / DesignThumbnail.SIZE, color);
        }
    }
}
