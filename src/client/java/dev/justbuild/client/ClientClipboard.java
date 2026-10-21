package dev.justbuild.client;

import dev.justbuild.JustBuildMod;
import org.lwjgl.sdl.SDLClipboard;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.system.MemoryUtil;

/** Copies complete responses without SDL's CharSequence overload's small thread-local stack. */
final class ClientClipboard {
    private ClientClipboard() { }

    static void set(String text) {
        var utf8 = MemoryUtil.memUTF8(text);
        try {
            if (!SDLClipboard.SDL_SetClipboardText(utf8)) {
                JustBuildMod.LOGGER.error("Could not set the clipboard: {}", SDLError.SDL_GetError());
            }
        } finally {
            MemoryUtil.memFree(utf8);
        }
    }
}
