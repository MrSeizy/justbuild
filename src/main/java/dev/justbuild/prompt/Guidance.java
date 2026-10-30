package dev.justbuild.prompt;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.random.RandomGenerator;

/** How much effort, which style, and which creative twist to ask of the AI. */
public final class Guidance {
    /** Applies to every build, whatever the detail level. */
    public static final String QUALITY_RULES = """
            Design and planning:
            - Interpret the whole request: subject, purpose, style, scale, existing work and required functions. \
            Advice and references never add unrelated features or override scope, palette, budget or preservation.
            - Resolve the complete layout before ornament: main volumes, entrance, floor heights, roof clearance \
            and connected routes. Allocate space and operations to every required part; the budget is a ceiling, \
            not a target. Simplify secondary features before sacrificing necessary structure or access.
            - Make the subject recognizable through proportion and silhouette. Relate secondary forms to the \
            main ones, or use a deliberate family for a group. A castle, hall, statue and landscape need different \
            compositions; one house recipe does not fit them all. Simple forms and open space can be intentional.
            - Assign materials consistent roles and vary related textures sparingly. Organize openings, supports \
            and floor lines into a readable rhythm. Depth, trim, symmetry and asymmetry are choices for the \
            requested style, not universal requirements.
            - Follow or deliberately reshape measured terrain. Blend boundaries; anchor paths and scattered \
            features to local ground heights. Do not flatten a site, create a uniform mound or cluster an \
            area-wide request into one centerpiece merely for convenience.
            - Samples describe measured cells only. Unknown space is not air; surface height does not establish \
            support beneath a tree, cave or overhang. Use available inspection when placement is uncertain.
            - Furnish by activity around circulation. Choose lighting for use and mood. Scale detail to the \
            available space, leaving quiet surfaces and clear entrances. Dark scenes and restrained designs are valid.
            - Preserve useful existing geometry and character unless the request changes them. Check the final \
            effect of operation order, including late trim and carving. Plan privately, return the required JSON, \
            and make focused corrections instead of repeatedly restarting sound work.""";

    public enum DetailLevel {
        QUICK(0, "When needed", "Make a simple plan. Ask the AI for one correction only if checks find a problem.",
                "Detail level: quick. Make a simple, complete interpretation of the request. Prefer economical "
                + "geometry, usually about 60 operations or fewer. Add only the detail needed to make it recognizable and usable."),
        DETAILED(1, "One pass", "Plan with more care, then ask the AI to review it once. Takes an extra AI step.",
                "Detail level: detailed. Spend effort on composition, coherent materials, intentional proportions, "
                + "and appropriate detail. Depth, trim, interiors, and landscaping are possibilities, not required additions. "
                + "A restrained or minimalist request should stay restrained."),
        MASTERPIECE(2, "Two passes", "Plan carefully, then ask the AI to review it twice. Takes two extra AI steps.",
                "Detail level: masterpiece. Develop the player's idea carefully. Consider, where relevant: "
                + "1 concept and scope; 2 terrain fit; 3 layout and access; 4 silhouette and surfaces; 5 materials; "
                + "6 roof or upper form; 7 lighting and mood; 8 interior; 9 setting; 10 review of concrete defects. "
                + "These are considerations, not a checklist of features. Quality can come from restraint; "
                + "do not add ornament or fill the operation budget just because this level allows more effort.");

        private final int reviewRounds;
        private final String reviewLabel;
        private final String reviewHint;
        private final String prompt;

        DetailLevel(int reviewRounds, String reviewLabel, String reviewHint, String prompt) {
            this.reviewRounds = reviewRounds;
            this.reviewLabel = reviewLabel;
            this.reviewHint = reviewHint;
            this.prompt = prompt;
        }

        public String reviewLabel() { return reviewLabel; }
        public String reviewHint() { return reviewHint; }

        /** How many review rounds a new build gets at this level. */
        public int reviewRounds() {
            return reviewRounds;
        }

        public String prompt() {
            return prompt;
        }

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Optional<DetailLevel> parse(String text) {
            for (DetailLevel level : values()) {
                if (level.id().equals(text.trim().toLowerCase(Locale.ROOT))) {
                    return Optional.of(level);
                }
            }
            return Optional.empty();
        }
    }

    /** A palette and the details that suit it (roof shape, framing), sent only for the chosen style. */
    public enum Style {
        AUTO(null, null),
        MEDIEVAL("stone, stone bricks, cobblestone, oak or spruce timber, dark roof materials, iron and lanterns",
                "adapt to the building type: masonry fortifications, timber halls or mixed town buildings; "+
                "choose roof forms and defensive or domestic openings for the requested use"),
        MODERN("white or gray concrete, quartz, glass, wood and a restrained dark accent",
                "clear planes and aligned openings, flat or low roofs, deliberate solid-to-glass balance; "+
                "cantilevers and terraces only where they serve the layout"),
        FUTURISTIC("concrete, quartz, glass, iron, end rods and selected luminous accents",
                "a coherent technological form, integrated lighting and purposeful curves or planes"),
        JAPANESE("dark timber or red framing, pale infill, dark roof materials and selected stone",
                "consistent timber bays and layered roof lines appropriate to the requested building type"),
        NORDIC("spruce logs and planks, stripped spruce, stone, andesite and dark roof materials",
                "timber structure and prominent roofs fitted to usable hall or cabin interiors"),
        STEAMPUNK("brick, dark wood, stone, copper and iron",
                "supported mechanical systems and a readable relationship between machinery and architecture"),
        RUSTIC("oak or spruce, stripped logs, cobblestone, mud bricks and restrained natural accents",
                "simple construction, grounded forms and details suited to the actual building's work or habitation"),
        GOTHIC("stone, tuff, deepslate, dark wood and selective stained glass",
                "vertical bays, pointed openings and structural depth, with roof and spire hierarchy"),
        DESERT("sandstone, mud bricks, terracotta, pale surfaces and selected wood",
                "shaded openings, courtyards or terraces, adapted to the requested regional building type"),
        CLASSICAL("quartz, sandstone or calcite with restrained contrasting stone",
                "ordered bays, columns and horizontal bands, a clear base and ceremonial entrance"),
        FANTASY("a coherent selection of wood, masonry, colored roof materials and luminous accents",
                "one readable imaginative premise with purposeful exaggeration and supporting secondary forms");

        private final String palette;
        private final String details;

        Style(String palette, String details) {
            this.palette = palette;
            this.details = details;
        }

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** The prompt line for this style, or null for AUTO (the AI picks). */
        public String promptLine() {
            return palette == null ? null : id() + ", suggested palette: " + palette + "; details: " + details;
        }

        public static Optional<Style> parse(String text) {
            for (Style style : values()) {
                if (style.id().equals(text.trim().toLowerCase(Locale.ROOT))) {
                    return Optional.of(style);
                }
            }
            return Optional.empty();
        }
    }

    /** One is drawn per new build so the same words don't always give the same build. */
    public static final List<String> DIRECTIONS = List.of(
            "weathered and overgrown with moss and vines", "tall and narrow", "wide and low",
            "asymmetric and organic", "grand and symmetrical", "cozy and compact", "built into the terrain",
            "multi-level with balconies and walkways", "with a central courtyard", "lit warmly for the night",
            "minimal and clean", "partly ruined", "decorated for a festival with banners and flowers",
            "with water features", "with one strong contrasting color accent", "fortified with walls and towers");

    private Guidance() {
    }

    public static String direction(RandomGenerator random) {
        return DIRECTIONS.get(random.nextInt(DIRECTIONS.size()));
    }
}
