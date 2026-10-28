package dev.justbuild.construction;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.*;
import static net.minecraft.world.level.block.state.properties.BlockStateProperties.*;

/** One purchase may create two door/bed cells; stacked blocks require their full item count. */
public final class ConstructionMaterials {
    private ConstructionMaterials() { }
    public record Unit(BlockPos anchor, Map<BlockPos, BlockState> cells, Item item, int count, String manual) { }
    public record Remaining(Map<Item, Integer> total, Map<Item, Integer> layer, int manual, int layerManual) { }
    /** Charge paired blocks once, on their anchor's course, and omit work already present in the world. */
    public static Remaining remaining(Collection<Unit> purchases, Set<BlockPos> done, int layer) {
        Map<Item, Integer> total = new HashMap<>(), course = new HashMap<>();
        int manual = 0, layerManual = 0;
        for (Unit unit : purchases) {
            if (done.containsAll(unit.cells().keySet())) continue;
            if (unit.manual() != null || unit.cells().size() > 1 && unit.cells().keySet().stream().anyMatch(done::contains)) {
                manual++;
                if (unit.cells().keySet().stream().anyMatch(p -> p.getY() == layer && !done.contains(p))) layerManual++;
                continue;
            }
            total.merge(unit.item(), unit.count(), Integer::sum);
            if (unit.anchor().getY() == layer) course.merge(unit.item(), unit.count(), Integer::sum);
        }
        return new Remaining(Map.copyOf(total), Map.copyOf(course), manual, layerManual);
    }
    public static Unit unit(Map<BlockPos, BlockState> blueprint, BlockPos selected) {
        BlockPos anchor = selected; BlockState state = blueprint.get(selected);
        if (state.hasProperty(DOUBLE_BLOCK_HALF) && state.getValue(DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) anchor = selected.below();
        if (state.hasProperty(BED_PART) && state.getValue(BED_PART) == BedPart.HEAD) anchor = selected.relative(state.getValue(HORIZONTAL_FACING).getOpposite());
        BlockState selectedState = state;
        state = blueprint.get(anchor);
        if (state == null || state.getBlock() != selectedState.getBlock()
                || state.hasProperty(DOUBLE_BLOCK_HALF) && state.getValue(DOUBLE_BLOCK_HALF) != DoubleBlockHalf.LOWER
                || state.hasProperty(BED_PART) && state.getValue(BED_PART) != BedPart.FOOT)
            return new Unit(selected, Map.of(selected, selectedState), Items.AIR, 0, "Complete this paired block by hand");
        var cells = new LinkedHashMap<BlockPos, BlockState>(); cells.put(anchor, state);
        if (state.hasProperty(DOUBLE_BLOCK_HALF)) {
            BlockState upper = blueprint.get(anchor.above());
            if (!state.setValue(DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER).equals(upper))
                return new Unit(anchor, cells, Items.AIR, 0, "Complete this paired block by hand");
            cells.put(anchor.above(), upper);
        }
        if (state.hasProperty(BED_PART)) {
            BlockPos head = anchor.relative(state.getValue(HORIZONTAL_FACING)); BlockState other = blueprint.get(head);
            if (!state.setValue(BED_PART, BedPart.HEAD).equals(other))
                return new Unit(anchor, cells, Items.AIR, 0, "Complete this bed by hand");
            cells.put(head, other);
        }
        Item item = state.getBlock().asItem(); int count = 1;
        String manual = null;
        if (state.isAir()) manual = "Clear this space by hand";
        else if (!(item instanceof BlockItem) || state.is(net.minecraft.world.level.block.Blocks.TNT)) manual = "Place or prepare this block by hand";
        else if (!state.getFluidState().isEmpty()) manual = "Place water and waterlogged blocks by hand";
        // One cauldron item covers every filled cauldron; lava has no level to charge for.
        else if (state.getBlock() instanceof net.minecraft.world.level.block.AbstractCauldronBlock
                && !state.is(net.minecraft.world.level.block.Blocks.CAULDRON)) manual = "Fill this cauldron by hand";
        // Growth, stored resources and transformed states must not be minted from an ordinary item.
        for (var property : state.getProperties()) {
            String name = property.getName();
            if ((name.startsWith("age") || Set.of("honey_level", "charges", "bites", "level", "stage", "hatch", "hydration", "dusted").contains(name))
                    && !state.getValue(property).equals(state.getBlock().defaultBlockState().getValue(property)))
                manual = "Grow or prepare this block by hand";
        }
        if (state.hasProperty(SLAB_TYPE) && state.getValue(SLAB_TYPE) == SlabType.DOUBLE) count = 2;
        for (IntegerProperty property : List.of(LAYERS, CANDLES, PICKLES, EGGS, FLOWER_AMOUNT, SEGMENT_AMOUNT))
            if (state.hasProperty(property)) count = state.getValue(property);
        return new Unit(anchor, Collections.unmodifiableMap(cells), item, count, manual);
    }
    /** Preserve named, enchanted, container-bearing and other customized items. */
    public static boolean usable(ItemStack stack, Item item) {
        return !stack.isEmpty() && stack.is(item) && stack.getComponentsPatch().isEmpty();
    }
    public static int available(Container inventory, Item item) { return available(inventory, item, Integer.MAX_VALUE); }
    /** Only the first {@code slots} slots: a player's 36 storage slots, never worn armor or the offhand. */
    public static int available(Container inventory, Item item, int slots) {
        if (inventory == null) return 0;
        int count = 0;
        for (int i = 0; i < Math.min(slots, inventory.getContainerSize()); i++) if (usable(inventory.getItem(i), item)) count += inventory.getItem(i).getCount();
        return count;
    }
    public static boolean consume(Container inventory, Item item, int count) { return consume(inventory, item, count, Integer.MAX_VALUE); }
    public static boolean consume(Container inventory, Item item, int count, int slots) {
        if (available(inventory, item, slots) < count) return false;
        for (int i = 0; i < Math.min(slots, inventory.getContainerSize()) && count > 0; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!usable(stack, item)) continue;
            int take = Math.min(count, stack.getCount()); stack.shrink(take); count -= take;
        }
        inventory.setChanged(); return true;
    }
}
