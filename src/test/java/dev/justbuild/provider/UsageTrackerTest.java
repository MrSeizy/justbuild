package dev.justbuild.provider;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class UsageTrackerTest {
    @Test void forwardsActivityWithoutInventingTokenUsage() throws Exception {
        var tracker = new UsageTracker();
        var progress = new RecordingListener();
        try (var call = tracker.begin(false, progress)) {
            call.activity(); call.activity();
            assertEquals(2, progress.activities.get());
            assertEquals(0, tracker.snapshot().input() + tracker.snapshot().output());
        }
        assertEquals(1, tracker.snapshot().unreported());
    }
    @Test void callsReserveBeforeExecutionIncludingFailuresAndUnknownTokenCounts() throws Exception {
        var tracker=new UsageTracker(); tracker.limits(new UsageTracker.Limits(2,0));
        try(var first=tracker.begin(false,ProgressListener.NONE)) { assertEquals(1,tracker.snapshot().active()); }
        try(var second=tracker.begin(true,ProgressListener.NONE)) { second.tokens(12,4); second.tokens(12,4); }
        var snapshot=tracker.snapshot(); assertEquals(2,snapshot.calls()); assertEquals(1,snapshot.reviews());
        assertEquals(12,snapshot.input()); assertEquals(4,snapshot.output()); assertEquals(1,snapshot.unreported());
        assertThrows(ProviderException.class,()->tracker.begin(false,ProgressListener.NONE));
        tracker.reset(); assertEquals(0,tracker.snapshot().calls()); assertEquals(2,tracker.snapshot().limits().calls());
    }
    @Test void tokensStopTheNextCallAndStaleCallbacksCannotMutateClosedCalls() throws Exception {
        var tracker=new UsageTracker(); tracker.limits(new UsageTracker.Limits(0,100));
        var call=tracker.begin(false,ProgressListener.NONE); call.tokens(80,40);
        assertThrows(IllegalStateException.class,tracker::reset);
        call.close(); call.tokens(500,500); call.close();
        assertEquals(120,tracker.snapshot().input()+tracker.snapshot().output());
        assertThrows(ProviderException.class,()->tracker.begin(false,ProgressListener.NONE));
        tracker.limits(UsageTracker.Limits.NONE); try(var next=tracker.begin(false,ProgressListener.NONE)) { next.tokens(-1,-1); }
        assertEquals(1,tracker.snapshot().unreported());
        assertEquals(tracker.snapshot(),UsageTracker.Snapshot.parse(tracker.snapshot().toJson()));
    }
}
