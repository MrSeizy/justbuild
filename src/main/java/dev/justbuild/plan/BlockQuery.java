package dev.justbuild.plan;

import com.google.gson.JsonObject;
import java.util.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.commands.arguments.blocks.BlockStateParser;

/** A bounded read-only query; results are reconstructed from this game's block registry. */
public record BlockQuery(String query, int offset) {
    public BlockQuery {
        if(query==null || !query.matches("[a-z0-9_:./-]{0,80}") || offset<0 || offset>10000)
            throw new IllegalArgumentException("Use a block ID or short lowercase search, with offset 0..10000.");
    }
    public String format() {
        var matches=BuiltInRegistries.BLOCK.keySet().stream().map(Object::toString).filter(s->s.contains(query)).sorted().toList();
        StringBuilder out=new StringBuilder("Block lookup: ").append(query).append("; total=").append(matches.size())
                .append("; offset=").append(offset).append("; nextOffset=").append(offset+24<matches.size()?offset+24:-1).append('\n');
        var exact=matches.stream().filter(id->id.equals(query)||id.equals("minecraft:"+query)).findFirst();
        if(exact.isPresent()) {
            var state=dev.justbuild.world.BlockResolver.parse(exact.get()).orElseThrow();
            out.append("Default: ").append(BlockStateParser.serialize(state)).append("\nAccepted properties:\n");
            state.getProperties().stream().sorted(Comparator.comparing(p->p.getName())).forEach(p->out.append(p.getName()).append('=').append(p.getPossibleValues()).append('\n'));
        } else matches.stream().skip(offset).limit(24).forEach(id->out.append(id).append('\n'));
        return out.append("Registry membership does not override placement restrictions.").toString();
    }
    public JsonObject toJson(){JsonObject o=new JsonObject();o.addProperty("query",query);o.addProperty("offset",offset);return o;}
    public static BlockQuery parse(JsonObject o){
        var offset=o.get("offset");
        if(!offset.isJsonPrimitive()||!offset.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException("Invalid offset.");
        if(!o.get("query").isJsonPrimitive()||!o.get("query").getAsJsonPrimitive().isString())throw new IllegalArgumentException("Invalid query.");
        return new BlockQuery(o.get("query").getAsString(),offset.getAsBigDecimal().intValueExact());
    }
}
