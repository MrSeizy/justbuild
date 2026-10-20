package dev.justbuild.provider;

import com.google.gson.JsonObject;
import java.util.IdentityHashMap;
import java.util.Map;

/** Per-player/session accounting. A call reserves its slot before the provider runs, including failed calls. */
public final class UsageTracker {
    public record Limits(int calls, long tokens) {
        public static final Limits NONE = new Limits(0,0);
        public Limits { if (calls < 0 || calls > 10_000 || tokens < 0 || tokens > 1_000_000_000L)
            throw new IllegalArgumentException("Use call limits 0–10,000 and token limits 0–1,000,000,000; 0 means unlimited."); }
    }
    public record Snapshot(int calls, long input, long output, long elapsedMillis, int reviews, int active, int unreported, Limits limits) {
        public JsonObject toJson() {
            var o=new JsonObject(); o.addProperty("calls",calls); o.addProperty("input",input); o.addProperty("output",output);
            o.addProperty("elapsedMillis",elapsedMillis); o.addProperty("reviews",reviews); o.addProperty("active",active);
            o.addProperty("unreported",unreported); o.addProperty("maxCalls",limits.calls()); o.addProperty("maxTokens",limits.tokens()); return o;
        }
        public static Snapshot parse(JsonObject o) {
            return new Snapshot(Math.clamp(o.get("calls").getAsLong(),0,Integer.MAX_VALUE), nonnegative(o,"input"), nonnegative(o,"output"),
                    nonnegative(o,"elapsedMillis"), Math.clamp(o.get("reviews").getAsLong(),0,Integer.MAX_VALUE),
                    Math.clamp(o.get("active").getAsLong(),0,100), Math.clamp(o.get("unreported").getAsLong(),0,Integer.MAX_VALUE),
                    new Limits(o.get("maxCalls").getAsBigDecimal().intValueExact(),o.get("maxTokens").getAsBigDecimal().longValueExact()));
        }
        private static long nonnegative(JsonObject o,String key) { return Math.max(0,o.get(key).getAsLong()); }
    }
    private Limits limits = Limits.NONE;
    private int calls, reviews, unreported;
    private long input, output, elapsedNanos;
    private final Map<Call,Long> active = new IdentityHashMap<>();
    public synchronized void limits(Limits value) { limits=value; }
    public synchronized Call begin(boolean review, ProgressListener delegate) throws ProviderException {
        if (limits.calls()>0 && calls>=limits.calls()) throw new ProviderException("Your AI session call limit has been reached. Adjust it in AI usage to continue.");
        if (limits.tokens()>0 && input+output>=limits.tokens()) throw new ProviderException("Your reported-token session limit has been reached. Adjust it in AI usage to continue.");
        Call call=new Call(delegate); active.put(call,System.nanoTime()); calls++; if(review) reviews++; return call;
    }
    public synchronized Snapshot snapshot() {
        long elapsed=elapsedNanos, now=System.nanoTime(); for(long start:active.values()) elapsed+=Math.max(0,now-start);
        return new Snapshot(calls,input,output,elapsed/1_000_000,reviews,active.size(),unreported,limits);
    }
    public synchronized void reset() {
        if(!active.isEmpty()) throw new IllegalStateException("Finish or cancel the current AI call before resetting usage.");
        calls=reviews=unreported=0; input=output=elapsedNanos=0;
    }
    public final class Call implements ProgressListener, AutoCloseable {
        private final ProgressListener delegate;
        private boolean reported, closed;
        private Call(ProgressListener delegate) { this.delegate=delegate; }
        @Override public void phase(Phase value) { delegate.phase(value); }
        @Override public void operations(int count) { delegate.operations(count); }
        @Override public void activity() { delegate.activity(); }
        @Override public void note(String text) { delegate.note(text); }
        @Override public void notice(String key,String text) { delegate.notice(key,text); }
        @Override public void tokens(long in,long out) {
            long cleanIn=Math.clamp(in,0,1_000_000_000_000L), cleanOut=Math.clamp(out,0,1_000_000_000_000L);
            synchronized(UsageTracker.this) {
                if(closed || reported) return;
                reported=true; input+=cleanIn; output+=cleanOut;
                if(cleanIn==0 && cleanOut==0) unreported++;
            }
            delegate.tokens(cleanIn,cleanOut);
        }
        @Override public void close() {
            synchronized(UsageTracker.this) {
                if(closed) return; closed=true;
                Long start=active.remove(this); if(start!=null) elapsedNanos+=Math.max(0,System.nanoTime()-start);
                if(!reported) unreported++;
            }
        }
    }
}
