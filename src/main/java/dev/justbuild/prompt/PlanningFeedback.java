package dev.justbuild.prompt;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.justbuild.plan.BlockQuery;
import dev.justbuild.plan.Cell;
import dev.justbuild.plan.InspectionRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Typed evidence only. Text, image labels, and registry results belong to the receiving game. */
public record PlanningFeedback(String planKey, List<Problem> problems, VoxelVolume source, VoxelVolume candidate,
        List<VoxelVolume> inspections, List<BlockQuery> blockQueries, ReadIssue readIssue) {
    public static final PlanningFeedback EMPTY = new PlanningFeedback(null, List.of(), null, null, null, null);
    public enum Kind { INVALID_BLOCKS, LIMIT, OUTSIDE_WORLD, ZONE, CONSTRAINT, DOOR, STAIR, SUPPORT, FOUNDATION, UNKNOWN }
    public enum ReadIssue { NO_CANDIDATE, REGION_TOO_LARGE }

    public record Problem(Kind kind, Cell position) {
        public Problem {
            Objects.requireNonNull(kind);
            if (position != null && (Math.abs((long) position.x()) > 4096 || Math.abs((long) position.y()) > 4096
                    || Math.abs((long) position.z()) > 4096)) throw new IllegalArgumentException("Invalid diagnostic position.");
        }
    }

    public PlanningFeedback(String planKey, List<Problem> problems, VoxelVolume source, VoxelVolume candidate,
            VoxelVolume inspected, BlockQuery blockQuery) {
        this(planKey, problems, source, candidate, inspected == null ? List.of() : List.of(inspected),
                blockQuery == null ? List.of() : List.of(blockQuery), null);
    }

    public PlanningFeedback {
        problems = List.copyOf(problems);
        inspections = List.copyOf(inspections);
        blockQueries = List.copyOf(blockQueries);
        if (problems.size() > 32 || planKey != null && !planKey.matches("[0-9a-f]{64}")
                || inspections.size() + blockQueries.size() > InspectionRequest.MAX_ROUNDS)
            throw new IllegalArgumentException("Invalid planning feedback.");
        if (planKey == null && (!problems.isEmpty() || candidate != null
                || inspections.stream().anyMatch(v -> v.box().target() == InspectionRequest.Target.CANDIDATE)))
            throw new IllegalArgumentException("Candidate evidence requires a plan identity.");
    }

    public PlanningFeedback withRead(VoxelVolume read) {
        var values = new ArrayList<>(inspections); values.add(read);
        return new PlanningFeedback(planKey, problems, source, candidate, values, blockQueries, null);
    }

    public PlanningFeedback withQuery(BlockQuery query) {
        var values = new ArrayList<>(blockQueries); values.add(query);
        return new PlanningFeedback(planKey, problems, source, candidate, inspections, values, null);
    }

    public PlanningFeedback withReadIssue(ReadIssue issue) {
        return new PlanningFeedback(planKey, problems, source, candidate, inspections, blockQueries, issue);
    }

    /** Earlier source observations/catalog lookups still matter; candidate reads belong to their previous draft. */
    public PlanningFeedback withSourceReads(PlanningFeedback earlier) {
        if (earlier == null) return this;
        return new PlanningFeedback(planKey, problems, source, candidate,
                earlier.inspections.stream().filter(v -> v.box().target() == InspectionRequest.Target.SOURCE).toList(),
                earlier.blockQueries, null);
    }

    /**
     * A problem a review can fix. UNKNOWN means some world data was unloaded: the checks read it as air, so construction
     * warnings may be false then, and only technical failures count.
     */
    public boolean needsCorrection() {
        boolean unknown = problems.stream().anyMatch(p -> p.kind() == Kind.UNKNOWN);
        return unknown ? technicalFailures() > 0 : !problems.isEmpty();
    }

    public long technicalFailures() {
        return problems.stream().filter(p -> switch (p.kind()) {
            case INVALID_BLOCKS, LIMIT, OUTSIDE_WORLD, ZONE, CONSTRAINT -> true;
            default -> false;
        }).count();
    }

    public String format() {
        StringBuilder out = new StringBuilder();
        if (planKey != null) {
            out.append("\n\nDraft checks (advisory quality findings require judgment; technical failures require correction):\n");
            if (problems.isEmpty()) out.append("No detected problems. This is not proof of design quality or access.\n");
            for (Problem p : problems) out.append(p.kind()).append(p.position() == null ? "" : " at " + p.position())
                    .append(": ").append(description(p.kind())).append('\n');
        }
        if (source != null || candidate != null) out.append("\nAttached images are simplified block-color views: ORIGINAL source and CANDIDATE draft, with isometric, top, and central sections. They are not textured screenshots. Labels state bounds and sampling; unsampled/unknown cells are not air. Final placement protections can further limit a proposal.\n");
        if (readIssue != null) out.append("\nRead request could not be completed: ")
                .append(readIssue == ReadIssue.NO_CANDIDATE ? "Submit a draft before inspecting the candidate."
                        : "Request a smaller region (exact: 4096 cells, surface: 32768 cells), or use sample mode.").append('\n');
        for (VoxelVolume inspected : inspections) out.append("\nInspection target: ").append(inspected.box().target())
                .append('\n').append(inspected.box().mode() == InspectionRequest.Mode.SURFACE ? inspected.surfaces() : inspected.format());
        for (BlockQuery query : blockQueries) out.append("\n\n").append(query.format());
        return out.toString();
    }

    private static String description(Kind kind) {
        return switch (kind) {
            case INVALID_BLOCKS -> "Some block IDs, states, or materials cannot be placed. Use the block lookup and correct the plan.";
            case LIMIT -> "The plan exceeds a geometry, cell, operation, or work limit. Simplify it.";
            case OUTSIDE_WORLD -> "Some placements are outside world height.";
            case ZONE -> "The proposed changes cross the selected footprint.";
            case CONSTRAINT -> "The proposed changes violate the requested height or material limits.";
            case DOOR -> "Check two-block headroom and clear approaches on both sides of the doorway.";
            case STAIR -> "Check the landing height, support, and headroom. Roof stairs may be decorative.";
            case SUPPORT -> "A section has no solid contact with existing blocks. Intentional floating designs may be valid.";
            case FOUNDATION -> "Check that the foundation meets the actual terrain.";
            case UNKNOWN -> "Some needed world data is unloaded or unavailable. Do not treat it as empty space.";
        };
    }

    public JsonObject toJson() {
        JsonObject out = new JsonObject(); out.addProperty("planKey", planKey);
        if (readIssue != null) out.addProperty("readIssue", readIssue.name());
        JsonArray issues = new JsonArray();
        for (Problem p : problems) {
            JsonObject value = new JsonObject(); value.addProperty("kind", p.kind().name());
            if (p.position() != null) {
                JsonArray at = new JsonArray(); at.add(p.position().x()); at.add(p.position().y()); at.add(p.position().z());
                value.add("at", at);
            }
            issues.add(value);
        }
        out.add("problems", issues);
        if (source != null) out.add("source", source.toJson());
        if (candidate != null) out.add("candidate", candidate.toJson());
        JsonArray reads = new JsonArray(); inspections.forEach(v -> reads.add(v.toJson())); out.add("inspections", reads);
        JsonArray queries = new JsonArray(); blockQueries.forEach(q -> queries.add(q.toJson())); out.add("blockQueries", queries);
        return out;
    }

    public static PlanningFeedback parse(JsonObject object) {
        List<Problem> issues = new ArrayList<>();
        JsonArray array = object.getAsJsonArray("problems");
        if (array.size() > 32) throw new IllegalArgumentException("Too many findings.");
        for (JsonElement value : array) {
            JsonObject entry = value.getAsJsonObject(); Cell pos = null;
            if (entry.has("at")) {
                var p = entry.getAsJsonArray("at");
                if (p.size() != 3) throw new IllegalArgumentException("Invalid coordinate.");
                pos = new Cell(VoxelVolume.integer(p.get(0)), VoxelVolume.integer(p.get(1)), VoxelVolume.integer(p.get(2)));
            }
            issues.add(new Problem(Kind.valueOf(entry.get("kind").getAsString()), pos));
        }
        JsonArray reads = object.getAsJsonArray("inspections"), queries = object.getAsJsonArray("blockQueries");
        if (reads.size() + queries.size() > InspectionRequest.MAX_ROUNDS) throw new IllegalArgumentException("Too many reads.");
        List<VoxelVolume> inspections = new ArrayList<>(); reads.forEach(v -> inspections.add(VoxelVolume.parse(v.getAsJsonObject())));
        List<BlockQuery> blockQueries = new ArrayList<>(); queries.forEach(q -> blockQueries.add(BlockQuery.parse(q.getAsJsonObject())));
        return new PlanningFeedback(object.has("planKey") && !object.get("planKey").isJsonNull() ? object.get("planKey").getAsString() : null,
                issues, volume(object, "source"), volume(object, "candidate"), inspections, blockQueries,
                object.has("readIssue") ? ReadIssue.valueOf(object.get("readIssue").getAsString()) : null);
    }

    private static VoxelVolume volume(JsonObject object, String name) {
        return object.has(name) ? VoxelVolume.parse(object.getAsJsonObject(name)) : null;
    }
}
