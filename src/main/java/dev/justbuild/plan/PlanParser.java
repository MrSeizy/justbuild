package dev.justbuild.plan;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Parses the shared build-plan JSON into records and checks each operation has the fields its shape needs. Lenient
 * about missing optional fields (defaults are applied), strict about values that would build the wrong thing.
 */
public final class PlanParser {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("JustBuild");
    private static final int MAX_OPTIONS = 4;
    private static final int MAX_OPTION_LENGTH = 80;
    private static final int MAX_TITLE_LENGTH = 60;
    /** The most blocks a roof's finial may stack. */
    static final int MAX_FINIAL = 8;
    /** The most blocks a "mix" may scatter. */
    static final int MAX_MIX = 4;
    private static final int MAX_TEXT_LENGTH = 400;
    static final Set<String> ROUND_STYLES = Set.of("full", "dome", "bowl");
    /** Shapes that take a "mix" of blocks. */
    private static final Set<OpType> MIXABLE = java.util.EnumSet.of(OpType.FILL, OpType.ROOF, OpType.SPHERE,
            OpType.ELLIPSOID, OpType.CYLINDER);
    static final Set<String> PORTAL_STYLES = Set.of("nether", "end");
    /** Minecraft's limits on a nether portal's opening. */
    public static final int PORTAL_MIN_WIDTH = 2;
    public static final int PORTAL_MIN_HEIGHT = 3;
    public static final int PORTAL_MAX = 21;
    static final Set<String> ROOF_STYLES = Set.of("gable", "hip", "flat", "gambrel", "spire");
    static final Set<String> ARCH_STYLES = Set.of("round", "pointed");
    static final Set<String> AXES = Set.of("x", "z");
    static final Set<String> DIRECTIONS = Set.of("north", "south", "east", "west");

    private PlanParser() {
    }

    public static PlanResponse parse(String text) throws PlanException {
        JsonObject root;
        try {
            JsonElement element = JsonParser.parseString(JsonText.extractObject(text));
            if (!element.isJsonObject()) {
                throw new PlanException("the response is not a JSON object");
            }
            root = element.getAsJsonObject();
        } catch (JsonParseException e) {
            throw new PlanException("the response is not valid JSON: " + e.getMessage());
        }
        String kind = string(root, "kind");
        if (kind == null) {
            throw new PlanException("missing \"kind\"");
        }
        return switch (kind) {
            case "blocks" -> {
                try { yield new PlanResponse.Blocks(BlockQuery.parse(root.getAsJsonObject("blockQuery"))); }
                catch (RuntimeException e) { throw new PlanException("invalid block query"); }
            }
            case "inspect" -> {
                try {
                    yield new PlanResponse.Inspect(InspectionRequest.parse(root.getAsJsonObject("inspection")));
                } catch (RuntimeException e) {
                    throw new PlanException("invalid inspection: " + e.getMessage());
                }
            }
            case "question" -> parseQuestion(root);
            case "build" -> new PlanResponse.Build(parsePlan(root));
            case "decline" -> {
                String reason = clean(string(root, "summary"), MAX_TEXT_LENGTH);
                yield new PlanResponse.Declined(reason.isEmpty() ? "No reason given." : reason);
            }
            case "guides" -> {
                java.util.List<String> ids = new java.util.ArrayList<>();
                if (root.get("guides") instanceof com.google.gson.JsonArray array) {
                    for (JsonElement e : array) {
                        String id = e.isJsonPrimitive() ? clean(e.getAsString(), 40).toLowerCase(java.util.Locale.ROOT) : "";
                        if (!id.isEmpty() && ids.size() < dev.justbuild.prompt.GuideLibrary.MAX_PER_REQUEST) {
                            ids.add(id);
                        }
                    }
                }
                if (ids.isEmpty()) {
                    throw new PlanException("kind is guides but \"guides\" is empty");
                }
                yield new PlanResponse.Guides(ids);
            }
            default -> throw new PlanException("unknown kind \"" + kind + "\"");
        };
    }

    private static PlanResponse.Question parseQuestion(JsonObject root) throws PlanException {
        String question = clean(string(root, "question"), MAX_TEXT_LENGTH);
        if (question.isEmpty()) {
            throw new PlanException("kind is question but \"question\" is empty");
        }
        List<String> options = new ArrayList<>();
        JsonElement raw = root.get("options");
        if (raw != null && raw.isJsonArray()) {
            for (JsonElement option : raw.getAsJsonArray()) {
                String text = option.isJsonPrimitive() ? clean(option.getAsString(), MAX_OPTION_LENGTH) : "";
                if (!text.isEmpty() && options.size() < MAX_OPTIONS) {
                    options.add(text);
                }
            }
        }
        return new PlanResponse.Question(question, List.copyOf(options));
    }

    private static BuildPlan parsePlan(JsonObject root) throws PlanException {
        JsonElement raw = root.get("operations");
        if (raw == null || !raw.isJsonArray() || raw.getAsJsonArray().isEmpty()) {
            throw new PlanException("kind is build but \"operations\" is empty");
        }
        JsonArray array = raw.getAsJsonArray();
        List<Operation> operations = new ArrayList<>(array.size());
        for (int i = 0; i < array.size(); i++) {
            try {
                if (!array.get(i).isJsonObject()) {
                    throw new PlanException("not an object");
                }
                operations.add(parseOperation(array.get(i).getAsJsonObject()));
            } catch (PlanException e) {
                throw new PlanException("operation " + (i + 1) + ": " + e.getMessage());
            }
        }
        String title = clean(string(root, "title"), MAX_TITLE_LENGTH);
        try {
            return new BuildPlan(title.isEmpty() ? "Untitled build" : title, clean(string(root, "summary"), MAX_TEXT_LENGTH),
                    List.copyOf(operations), TextureSettings.parse(root.get("texture")));
        } catch (RuntimeException e) { throw new PlanException("invalid texture settings"); }
    }

    private static Operation parseOperation(JsonObject o) throws PlanException {
        String name = string(o, "op");
        if (name == null) {
            throw new PlanException("missing \"op\"");
        }
        OpType type = OpType.parse(name);
        String block = blankToNull(string(o, "block"));
        if (block == null && type != OpType.VOXELS && type != OpType.PORTAL) {
            throw new PlanException("missing \"block\"");
        }
        Cell from = cell(o, "from");
        if (from == null) {
            throw new PlanException("missing \"from\"");
        }
        Cell to = cell(o, "to");
        Double radius = number(o, "radius");
        Double rawHeight = number(o, "height");
        Integer height = rawHeight == null ? null : (int) Math.round(rawHeight);
        boolean hollow = bool(o, "hollow");
        String trim = blankToNull(string(o, "trim"));
        String style = lower(string(o, "style"));
        String axis = lower(string(o, "axis"));
        String direction = lower(string(o, "direction"));
        String target = blankToNull(string(o, "target"));
        String edge = blankToNull(string(o, "edge"));
        Double rawPitch = number(o, "pitch");
        Integer pitch = rawPitch == null ? null : (int) Math.round(rawPitch);
        Boolean rim = o.get("rim") != null && o.get("rim").isJsonPrimitive() ? o.get("rim").getAsBoolean() : null;
        Boolean eaves = o.get("eaves") != null && o.get("eaves").isJsonPrimitive() ? o.get("eaves").getAsBoolean() : null;
        Boolean flare = o.get("flare") != null && o.get("flare").isJsonPrimitive() ? o.get("flare").getAsBoolean() : null;
        List<String> mix = blocks(o, "mix", MAX_MIX);
        String beam = blankToNull(string(o, "beam"));
        List<String> finial = null;
        if (o.get("finial") instanceof com.google.gson.JsonArray array) {
            finial = new java.util.ArrayList<>();
            for (JsonElement e : array) {
                String part = e.isJsonPrimitive() ? blankToNull(e.getAsString()) : null;
                if (part == null) {
                    throw new PlanException("roof \"finial\" must list block ids");
                }
                finial.add(part);
            }
            if (finial.size() > MAX_FINIAL) {
                throw new PlanException("roof \"finial\" has more than " + MAX_FINIAL + " blocks");
            }
            finial = finial.isEmpty() ? null : finial;
        }
        Radii radii = null;
        Map<Character, String> palette = null;
        List<List<String>> layers = null;

        switch (type) {
            case FILL, LINE, REPLACE, FOUNDATION, ROOF, STAIRS -> {
                if (to == null) {
                    throw new PlanException(type.id() + " needs \"to\"");
                }
            }
            default -> {
            }
        }
        switch (type) {
            case SPHERE -> {
                requirePositive(radius, "sphere needs a positive \"radius\"");
                style = oneOf(style, "full", ROUND_STYLES, "unknown sphere style");
            }
            case ELLIPSOID -> {
                radii = radii(o);
                style = oneOf(style, "full", ROUND_STYLES, "unknown ellipsoid style");
            }
            case CYLINDER -> {
                requirePositive(radius, "cylinder needs a positive \"radius\"");
                if (height == null || height < 1) {
                    throw new PlanException("cylinder needs \"height\" of at least 1");
                }
            }
            case ROOF -> {
                style = oneOf(style, "gable", ROOF_STYLES, "unknown roof style");
                if (axis != null && !AXES.contains(axis)) {
                    throw new PlanException("\"axis\" must be x or z");
                }
                if (pitch != null && (pitch < 0 || pitch > 2)) {
                    throw new PlanException("roof \"pitch\" must be 0, 1, or 2");
                }
                if (height != null && height < 2 && "spire".equals(style)) {
                    throw new PlanException("a spire's \"height\" must be at least 2");
                }
            }
            case STAIRS -> {
                if (direction == null || !DIRECTIONS.contains(direction)) {
                    throw new PlanException("stairs needs a \"direction\" of north, south, east, or west");
                }
                if (height == null || height < 1) {
                    throw new PlanException("stairs needs \"height\" of at least 1");
                }
            }
            case ARCH -> {
                requirePositive(radius, "arch needs a positive \"radius\"");
                if (height == null) {
                    height = 0;
                } else if (height < 0) {
                    throw new PlanException("arch \"height\" must not be negative");
                }
                axis = oneOf(axis, "x", AXES, "\"axis\" must be x or z");
                style = oneOf(style, "round", ARCH_STYLES, "unknown arch style");
            }
            case VOXELS -> {
                palette = palette(o);
                layers = layers(o, palette);
            }
            case REPLACE -> {
                if (target == null) {
                    throw new PlanException("replace needs a \"target\" block");
                }
            }
            case PORTAL -> {
                style = oneOf(style, "nether", PORTAL_STYLES, "portal \"style\" must be nether or end");
                block = null;
                if (style.equals("end")) {
                    to = null;
                } else {
                    netherOpening(from, to);
                }
            }
            default -> {
            }
        }
        return new Operation(type, block, from, to, radius, height, hollow, trim, style, axis, direction, radii,
                palette, layers, target, repeat(o), mirror(o), type == OpType.ROOF ? edge : null,
                type == OpType.ROOF ? pitch : null, type == OpType.ROOF ? rim : null, type == OpType.ROOF ? eaves : null,
                type == OpType.ROOF ? flare : null, MIXABLE.contains(type) ? mix : null,
                type == OpType.ROOF ? beam : null, type == OpType.ROOF ? finial : null);
    }

    private static void requirePositive(Double value, String message) throws PlanException {
        if (value == null || value <= 0) {
            throw new PlanException(message);
        }
    }

    private static String oneOf(String value, String fallback, Set<String> allowed, String message)
            throws PlanException {
        if (value == null) {
            return fallback;
        }
        if (!allowed.contains(value)) {
            throw new PlanException(message + " \"" + value + "\"");
        }
        return value;
    }

    private static Radii radii(JsonObject o) throws PlanException {
        JsonElement e = o.get("radii");
        String message = "ellipsoid needs three positive \"radii\"";
        if (e == null || !e.isJsonArray() || e.getAsJsonArray().size() != 3) {
            throw new PlanException(message);
        }
        double[] v = new double[3];
        for (int i = 0; i < 3; i++) {
            JsonElement c = e.getAsJsonArray().get(i);
            if (!c.isJsonPrimitive() || !c.getAsJsonPrimitive().isNumber() || c.getAsDouble() <= 0) {
                throw new PlanException(message);
            }
            v[i] = c.getAsDouble();
        }
        return new Radii(v[0], v[1], v[2]);
    }

    private static Map<Character, String> palette(JsonObject o) throws PlanException {
        JsonElement e = o.get("palette");
        if (e == null || !e.isJsonArray() || e.getAsJsonArray().isEmpty()) {
            throw new PlanException("voxels needs a \"palette\"");
        }
        Map<Character, String> palette = new LinkedHashMap<>();
        for (JsonElement entry : e.getAsJsonArray()) {
            if (!entry.isJsonObject()) {
                throw new PlanException("palette entries must be objects");
            }
            String symbol = string(entry.getAsJsonObject(), "char");
            String block = blankToNull(string(entry.getAsJsonObject(), "block"));
            if (symbol == null || symbol.length() != 1 || symbol.equals(".") || symbol.equals(" ")) {
                throw new PlanException("palette \"char\" must be one character other than '.' or space");
            }
            if (block == null) {
                throw new PlanException("palette entry '" + symbol + "' needs a \"block\"");
            }
            palette.put(symbol.charAt(0), block);
        }
        return Map.copyOf(palette);
    }

    private static List<List<String>> layers(JsonObject o, Map<Character, String> palette) throws PlanException {
        JsonElement e = o.get("layers");
        if (e == null || !e.isJsonArray() || e.getAsJsonArray().isEmpty()) {
            throw new PlanException("voxels needs \"layers\"");
        }
        List<List<String>> layers = new ArrayList<>();
        java.util.Set<Character> unknown = new java.util.TreeSet<>();
        for (JsonElement layer : e.getAsJsonArray()) {
            if (!layer.isJsonArray()) {
                throw new PlanException("each layer must be a list of rows");
            }
            List<String> rows = new ArrayList<>();
            for (JsonElement row : layer.getAsJsonArray()) {
                if (!row.isJsonPrimitive()) {
                    throw new PlanException("each row must be a string");
                }
                // A character the palette doesn't define is left unchanged, like '.', rather than rejecting the
                // whole plan over one cell (models occasionally forget a palette entry).
                StringBuilder text = new StringBuilder(row.getAsString());
                for (int i = 0; i < text.length(); i++) {
                    char c = text.charAt(i);
                    if (c != '.' && c != ' ' && !palette.containsKey(c)) {
                        text.setCharAt(i, '.');
                        unknown.add(c);
                    }
                }
                rows.add(text.toString());
            }
            layers.add(List.copyOf(rows));
        }
        if (!unknown.isEmpty()) {
            LOGGER.warn("Voxel characters {} are not in the palette; those cells were left unchanged", unknown);
        }
        return List.copyOf(layers);
    }

    private static Repeat repeat(JsonObject o) throws PlanException {
        JsonElement e = o.get("repeat");
        if (e == null || e.isJsonNull()) {
            return null;
        }
        if (!e.isJsonObject()) {
            throw new PlanException("\"repeat\" must be an object");
        }
        Double count = number(e.getAsJsonObject(), "count");
        if (count == null || Math.round(count) < 1) {
            throw new PlanException("\"repeat\" needs a positive \"count\"");
        }
        // Checked before the cast: 2^32 would become 0, and a saved plan with count 0 never parses again.
        if (Math.round(count) > Integer.MAX_VALUE) {
            throw new PlanException("\"repeat\" has a \"count\" that is far too large");
        }
        Cell step = cell(e.getAsJsonObject(), "step");
        if (step == null) {
            throw new PlanException("\"repeat\" needs a \"step\"");
        }
        return new Repeat((int) Math.round(count), step);
    }

    private static MirrorPlane mirror(JsonObject o) throws PlanException {
        JsonElement e = o.get("mirror");
        if (e == null || e.isJsonNull()) {
            return null;
        }
        if (!e.isJsonObject()) {
            throw new PlanException("\"mirror\" must be an object");
        }
        String axis = lower(string(e.getAsJsonObject(), "axis"));
        if (axis == null || !AXES.contains(axis)) {
            throw new PlanException("\"mirror\" needs axis x or z");
        }
        Double at = number(e.getAsJsonObject(), "at");
        if (at == null) {
            at = 0.0;
        }
        if (Math.abs(at * 2 - Math.round(at * 2)) > 1e-9) {
            throw new PlanException("\"mirror.at\" must be a multiple of 0.5");
        }
        return new MirrorPlane(axis, at);
    }

    private static String string(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || !e.isJsonPrimitive() ? null : e.getAsString();
    }

    /**
     * Text the AI wrote that ends up in chat or in a click command: drops § formatting codes and control characters,
     * collapses whitespace, and caps the length.
     */
    public static String clean(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        String s = text.replaceAll("§.?", "").replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").strip();
        return s.length() <= maxLength ? s : s.substring(0, maxLength).strip();
    }

    /** Keep international text, but remove invisible formatting/control characters before counting visible text. */
    public static String visible(String text, int limit) {
        String clean = clean(text, Integer.MAX_VALUE)
                .replaceAll("[\\p{C}\\p{Z}]+", " ").replaceAll(" +", " ").strip();
        int count = clean.codePointCount(0, clean.length());
        return count <= limit ? clean : clean.substring(0, clean.offsetByCodePoints(0, limit)).strip();
    }

    /** A nether portal's opening must be flat (one x or one z) and within Minecraft's size limits. */
    private static void netherOpening(Cell from, Cell to) throws PlanException {
        if (to == null) {
            throw new PlanException("a nether portal needs \"to\": the opposite corner of its opening");
        }
        int dx = Math.abs(to.x() - from.x()) + 1;
        int dz = Math.abs(to.z() - from.z()) + 1;
        int height = Math.abs(to.y() - from.y()) + 1;
        if (dx > 1 && dz > 1) {
            throw new PlanException("a nether portal's opening must be flat: \"from\" and \"to\" share x or z");
        }
        int width = Math.max(dx, dz);
        if (width < PORTAL_MIN_WIDTH || height < PORTAL_MIN_HEIGHT || width > PORTAL_MAX || height > PORTAL_MAX) {
            throw new PlanException("a nether portal's opening must be " + PORTAL_MIN_WIDTH + " to " + PORTAL_MAX
                    + " wide and " + PORTAL_MIN_HEIGHT + " to " + PORTAL_MAX + " tall (it is " + width + " by "
                    + height + ")");
        }
    }

    /** A block id, or a list of up to {@code max} of them (null when absent or empty). */
    private static List<String> blocks(JsonObject o, String key, int max) throws PlanException {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) {
            return null;
        }
        List<String> out = new ArrayList<>();
        if (e.isJsonPrimitive()) {
            String one = blankToNull(e.getAsString());
            if (one != null) {
                out.add(one);
            }
        } else if (e.isJsonArray()) {
            for (JsonElement x : e.getAsJsonArray()) {
                String one = x.isJsonPrimitive() ? blankToNull(x.getAsString()) : null;
                if (one == null) {
                    throw new PlanException("\"" + key + "\" must list block ids");
                }
                out.add(one);
            }
        }
        if (out.size() > max) {
            throw new PlanException("\"" + key + "\" has more than " + max + " blocks");
        }
        return out.isEmpty() ? null : out;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String lower(String s) {
        String v = blankToNull(s);
        return v == null ? null : v.toLowerCase(Locale.ROOT);
    }

    private static boolean bool(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsBoolean();
    }

    private static Double number(JsonObject o, String key) throws PlanException {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) {
            return null;
        }
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            throw new PlanException("\"" + key + "\" must be a number");
        }
        return e.getAsDouble();
    }

    private static Cell cell(JsonObject o, String key) throws PlanException {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) {
            return null;
        }
        if (!e.isJsonArray() || e.getAsJsonArray().size() != 3) {
            throw new PlanException("\"" + key + "\" must be [x, y, z]");
        }
        int[] v = new int[3];
        for (int i = 0; i < 3; i++) {
            JsonElement c = e.getAsJsonArray().get(i);
            if (!c.isJsonPrimitive() || !c.getAsJsonPrimitive().isNumber()) {
                throw new PlanException("\"" + key + "\" must contain numbers");
            }
            v[i] = (int) Math.round(c.getAsDouble());
        }
        return new Cell(v[0], v[1], v[2]);
    }
}
