package dev.justbuild.world;

import dev.justbuild.plan.BuildPlan;
import dev.justbuild.plan.Cell;
import dev.justbuild.plan.Operation;
import dev.justbuild.plan.PlanException;
import dev.justbuild.plan.PlanRotation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

/** Freeze a finished build's known positions into small voxel tiles in its original coordinate frame. */
public final class PlanSnapshot {
    private static final int TILE = 16;
    private static final String SYMBOLS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz!@";

    private PlanSnapshot() { }

    public static BuildPlan capture(String title, BlockPos origin, Direction facing, Map<BlockPos, BlockState> blocks)
            throws PlanException {
        if (blocks.isEmpty()) throw new PlanException("This build has no known blocks to save.");
        PlanRotation inverse = PlanRotation.forFacing(facing.getName()).inverse();
        Rotation stateRotation = Rotation.valueOf(inverse.name());
        Map<Cell, Map<Cell, String>> tiles = new LinkedHashMap<>();
        for (var entry : blocks.entrySet()) {
            BlockPos p = entry.getKey().subtract(origin);
            Cell cell = inverse.apply(new Cell(p.getX(), p.getY(), p.getZ()));
            Cell tile = new Cell(Math.floorDiv(cell.x(), TILE), Math.floorDiv(cell.y(), TILE), Math.floorDiv(cell.z(), TILE));
            tiles.computeIfAbsent(tile, ignored -> new LinkedHashMap<>()).put(cell,
                    BlockStateParser.serialize(entry.getValue().rotate(stateRotation)));
        }
        List<Operation> operations = new ArrayList<>();
        for (Map<Cell, String> tile : tiles.values()) {
            List<String> states = tile.values().stream().distinct().toList();
            for (int first = 0; first < states.size(); first += SYMBOLS.length()) {
                Map<String, Character> symbols = new LinkedHashMap<>();
                Map<Character, String> palette = new LinkedHashMap<>();
                for (int i = first; i < Math.min(first + SYMBOLS.length(), states.size()); i++) {
                    char symbol = SYMBOLS.charAt(i - first);
                    symbols.put(states.get(i), symbol);
                    palette.put(symbol, states.get(i));
                }
                List<Cell> cells = tile.keySet().stream().filter(c -> symbols.containsKey(tile.get(c))).toList();
                int minX = cells.stream().mapToInt(Cell::x).min().orElseThrow();
                int maxX = cells.stream().mapToInt(Cell::x).max().orElseThrow();
                int minY = cells.stream().mapToInt(Cell::y).min().orElseThrow();
                int maxY = cells.stream().mapToInt(Cell::y).max().orElseThrow();
                int minZ = cells.stream().mapToInt(Cell::z).min().orElseThrow();
                int maxZ = cells.stream().mapToInt(Cell::z).max().orElseThrow();
                List<List<String>> layers = new ArrayList<>();
                for (int y = minY; y <= maxY; y++) {
                    List<String> rows = new ArrayList<>();
                    for (int z = minZ; z <= maxZ; z++) {
                        StringBuilder row = new StringBuilder();
                        for (int x = minX; x <= maxX; x++) row.append(symbols.getOrDefault(tile.get(new Cell(x, y, z)), '.'));
                        rows.add(row.toString());
                    }
                    layers.add(List.copyOf(rows));
                }
                operations.add(Operation.voxels(palette, new Cell(minX, minY, minZ), layers));
            }
        }
        return new BuildPlan(title, "Copy of a saved building.", List.copyOf(operations));
    }
}
