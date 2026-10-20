package dev.justbuild.world;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.level.block.state.BlockState;

/** Places an ordered list of blocks a slice at a time, remembering what it replaced, then refreshes their shapes. */
public final class PlacementJob {
    /** BUILD and EDIT record what they replace (for the build's baseline); UNDO and REVERT restore. */
    public enum Kind { BUILD, EDIT, UNDO, REVERT }

    private final String owner;
    private final Kind kind;
    private final String title;
    private final BlockAccess world;
    private final List<Placement> placements;
    private final List<Placement> previous = new ArrayList<>();
    private int next;
    private int refreshed;
    private int skippedOccupied;
    private boolean waiting;

    public PlacementJob(String owner, Kind kind, String title, BlockAccess world, List<Placement> placements) {
        this.owner = owner;
        this.kind = kind;
        this.title = title;
        this.world = world;
        this.placements = List.copyOf(placements);
    }

    /**
     * Places (then refreshes) up to {@code budget} blocks and returns how many it handled. It stops at a position whose
     * chunk isn't loaded (the player left) and waits, rather than loading chunks from the server thread.
     */
    public int step(int budget) {
        int handled = 0;
        waiting = false;
        while (handled < budget && next < placements.size()) {
            if (!world.loaded(placements.get(next).pos())) {
                waiting = true;
                return handled;
            }
            Placement p = placements.get(next++);
            BlockState old = world.get(p.pos());
            if (kind == Kind.BUILD || kind == Kind.EDIT) {
                previous.add(new Placement(p.pos(), old));
            }
            if (old != p.state() && !world.set(p.pos(), p.state())) {
                skippedOccupied++;
            }
            handled++;
        }
        while (handled < budget && next >= placements.size() && refreshed < placements.size()) {
            if (!world.loaded(placements.get(refreshed).pos())) {
                waiting = true;
                return handled;
            }
            world.refresh(placements.get(refreshed++).pos());
            handled++;
        }
        return handled;
    }

    /** All blocks are placed and the shape-refresh pass is running. */
    public boolean refreshing() {
        return next >= placements.size() && refreshed < placements.size();
    }

    public boolean done() {
        return next >= placements.size() && refreshed >= placements.size();
    }

    /** Blocks left open because a player, pet, or villager was in the way. */
    public int skippedOccupied() {
        return skippedOccupied;
    }

    /** The last step stopped at an unloaded chunk; the job resumes once it is loaded again. */
    public boolean waiting() {
        return waiting;
    }

    public int placedCount() {
        return next;
    }

    public int total() {
        return placements.size();
    }

    public String owner() {
        return owner;
    }

    public Kind kind() {
        return kind;
    }

    public String title() {
        return title;
    }

    public BlockAccess world() {
        return world;
    }

    /** What each placed position held before this job touched it. */
    public List<Placement> previousStates() {
        return List.copyOf(previous);
    }
}
