package dev.justbuild.prompt;

import java.util.List;

/** Shared request templates let the relay recognize exactly the interior work the player requested. */
public final class InteriorRequests {
    public static final List<String> PURPOSES = List.of("home", "workshop", "tavern", "custom");
    public static final List<String> STAGES = List.of("complete", "layout", "furnish");
    public record Choice(String purpose, String stage, String detail) { }
    private InteriorRequests() { }
    public static Choice parse(String text) {
        String[] words = text.strip().split("\\s+", 3);
        if (words.length < 2) throw new IllegalArgumentException("Choose an interior purpose and stage.");
        Choice c = new Choice(words[0], words[1], words.length == 3 ? words[2] : "");
        request(c.purpose(), c.stage(), c.detail()); return c;
    }
    public static String request(String purpose, String stage, String detail) {
        if (!PURPOSES.contains(purpose) || !STAGES.contains(stage) || detail == null || detail.length() > 180) {
            throw new IllegalArgumentException("Choose home, workshop, tavern, or custom; then complete, layout, or furnish.");
        }
        String use = switch (purpose) {
            case "home" -> "a family home with living, cooking, sleeping, and storage spaces";
            case "workshop" -> "a blacksmith workshop with workstations, storage, a counter, and a safe work area";
            case "tavern" -> "a tavern with a bar, tables, a kitchen, storage, and upstairs bedrooms when space allows";
            default -> "the purpose specified by the player";
        };
        String task = switch (stage) {
            case "layout" -> "Lay out rooms, doors, and stairs first; leave furnishing for a later pass.";
            case "furnish" -> "Keep the room layout; add suitable furniture, workstations, storage, and lighting.";
            default -> "Plan the rooms and circulation first, then add furniture and lighting suited to each room.";
        };
        return ("Plan the interior as " + use + ". " + task
                + " Preserve the outer walls, windows, roof, and ground floor. Keep a continuous entrance-to-room route, "
                + "two-block headroom, clear door approaches, and usable stairs with landings to every occupied floor. "
                + "Fit furniture to the available space; keep walkways clear and light every room. "
                + (detail.isBlank() ? "" : "Player details: " + detail.strip())).strip();
    }
}
