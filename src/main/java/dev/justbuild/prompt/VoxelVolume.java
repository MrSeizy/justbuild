package dev.justbuild.prompt;

import com.google.gson.*;
import dev.justbuild.plan.Cell;
import dev.justbuild.plan.InspectionRequest;
import java.util.*;
import java.util.function.Function;

/** Bounded palette snapshot. Unknown cells remain unknown; no entities, text, files, or chunks are read. */
public record VoxelVolume(InspectionRequest box, int step, List<String> palette, List<Integer> cells) {
    public static final int MAX_CELLS = 32768;
    public VoxelVolume {
        palette = List.copyOf(palette); cells = List.copyOf(cells);
        if (step < 1 || step > 128 || palette.size() > 4096 || palette.stream().distinct().count() != palette.size()
                || palette.stream().anyMatch(s -> !s.matches("[a-z0-9_.-]{1,40}:[a-z0-9_./-]{1,80}(\\[[a-z0-9_=,.-]{1,400}\\])?")))
            throw new IllegalArgumentException("Invalid voxel palette or step.");
        long count = (long) size(box.from().x(), box.to().x(), step) * size(box.from().y(), box.to().y(), step)
                * size(box.from().z(), box.to().z(), step);
        int paletteSize = palette.size();
        if (count > MAX_CELLS || count != cells.size() || cells.stream().anyMatch(i -> i < -1 || i >= paletteSize))
            throw new IllegalArgumentException("Invalid voxel cells.");
    }
    public int nx() { return size(box.from().x(), box.to().x(), step); }
    public int ny() { return size(box.from().y(), box.to().y(), step); }
    public int nz() { return size(box.from().z(), box.to().z(), step); }
    private static int size(int a, int b, int step) { return (b-a)/step+1; }
    public String at(int x, int y, int z) {
        int index = cells.get((y*nz()+z)*nx()+x); return index < 0 ? null : palette.get(index);
    }
    public Cell position(int x, int y, int z) {
        return new Cell(box.from().x()+x*step, box.from().y()+y*step, box.from().z()+z*step);
    }
    public static VoxelVolume capture(InspectionRequest box, int budget, boolean exact, Function<Cell,String> lookup) {
        if (budget < 1 || budget > MAX_CELLS) throw new IllegalArgumentException("Invalid snapshot budget.");
        int step = 1;
        while ((long)size(box.from().x(),box.to().x(),step)*size(box.from().y(),box.to().y(),step)
                *size(box.from().z(),box.to().z(),step) > budget) step++;
        if (exact && step != 1) throw new IllegalArgumentException("Request a smaller exact region.");
        Map<String,Integer> indices = new LinkedHashMap<>(); List<Integer> cells = new ArrayList<>();
        for (int y=box.from().y(); y<=box.to().y(); y+=step)
            for (int z=box.from().z(); z<=box.to().z(); z+=step)
                for (int x=box.from().x(); x<=box.to().x(); x+=step) {
                    String state=lookup.apply(new Cell(x,y,z));
                    if (state == null) cells.add(-1);
                    else if (indices.containsKey(state)) cells.add(indices.get(state));
                    else if (indices.size()<4096) { indices.put(state,indices.size()); cells.add(indices.get(state)); }
                    else if (exact) throw new IllegalArgumentException("Too many distinct states for exact inspection.");
                    else cells.add(-1);
                }
        return new VoxelVolume(box,step,new ArrayList<>(indices.keySet()),cells);
    }
    public String format() {
        return "Block region in plan coordinates, x="+box.from().x()+".."+box.to().x()+", y="+box.from().y()+".."+box.to().y()+", z="+box.from().z()+".."+box.to().z()+", spacing="+step
                +". -1 means unknown, never air. Values run x fastest, then z, then y. Palette="+palette+"; indices="+cells;
    }
    public String surfaces() {
        StringBuilder out = new StringBuilder("Column survey within the requested y bounds. Coordinates are plan-relative. ")
                .append("Top is highest known non-air; support and liquid above it use the local block registry. Unknown cells above the first solid support invalidate the column. ")
                .append("Columns: [x,z,topY,supportY,liquidY,airAboveSupport]; null means none within these bounds.\n");
        for (int z=0;z<nz();z++) for(int x=0;x<nx();x++) {
            Integer top=null,ground=null,liquid=null; int air=0; boolean unknown=false;
            for(int y=ny()-1;y>=0;y--) {
                String state=at(x,y,z); if(state==null) { unknown=true; break; }
                var resolved=dev.justbuild.world.BlockResolver.parse(state);
                if(resolved.isEmpty()) { unknown=true; break; }
                var block=resolved.get(); int py=position(x,y,z).y();
                if(!block.isAir() && top==null) top=py;
                if(!block.getFluidState().isEmpty() && liquid==null) liquid=py;
                if(ground==null && block.isSolidRender()) { ground=py; break; }
                air=block.isAir()?air+1:0;
            }
            Cell p=position(x,0,z);
            out.append('[').append(p.x()).append(',').append(p.z()).append(',');
            if(unknown) out.append("unknown"); else out.append(top).append(',').append(ground).append(',').append(liquid).append(',').append(ground==null?0:air);
            out.append("]\n");
        }
        return out.toString();
    }
    public JsonObject toJson() {
        JsonObject out=box.toJson(); out.addProperty("step",step);
        JsonArray p=new JsonArray();palette.forEach(p::add);out.add("palette",p);
        JsonArray c=new JsonArray();cells.forEach(c::add);out.add("cells",c);return out;
    }
    public static VoxelVolume parse(JsonObject o) {
        var p=o.getAsJsonArray("palette");var c=o.getAsJsonArray("cells");
        if(p.size()>4096||c.size()>MAX_CELLS)throw new IllegalArgumentException("Snapshot too large.");
        List<String> palette=new ArrayList<>();p.forEach(v->{if(!v.isJsonPrimitive()||!v.getAsJsonPrimitive().isString())throw new IllegalArgumentException("Invalid state.");palette.add(v.getAsString());});
        List<Integer> cells=new ArrayList<>();c.forEach(v->cells.add(integer(v)));
        return new VoxelVolume(InspectionRequest.parse(o),integer(o.get("step")),palette,cells);
    }
    static int integer(JsonElement v) {
        if(!v.isJsonPrimitive()||!v.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException("Expected integer.");
        return v.getAsBigDecimal().intValueExact();
    }
}
