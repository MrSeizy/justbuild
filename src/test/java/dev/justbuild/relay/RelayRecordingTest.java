package dev.justbuild.relay;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class RelayRecordingTest {
    @Test
    void aRepairedPlanRecordsItsKeyAndThePair() {
        var r = RelayRecording.of("K", new RelayOutput("{}", null, "P"));
        assertEquals("P", r.planKey());
        assertEquals("K>P", r.repairPair());
        assertNull(r.question());
        assertNull(r.critiqueKey());
    }

    @Test
    void aPlanWithoutARepairRecordsNoPair() {
        assertNull(RelayRecording.of(null, new RelayOutput("{}", null, "P")).repairPair());
        assertNull(RelayRecording.of(" ", new RelayOutput("{}", null, "P")).repairPair());
        assertEquals("P", RelayRecording.of(null, new RelayOutput("{}", null, "P")).planKey());
    }

    @Test
    void aBlankPlanKeyRecordsNoPair() {
        assertNull(RelayRecording.of("K", new RelayOutput("{}", null, " ")).repairPair());
    }

    @Test
    void aCritiqueRecordsItsKeyOnly() {
        var r = RelayRecording.of("K", new RelayOutput("{}", null, null, null, "C"));
        assertEquals("C", r.critiqueKey());
        assertNull(r.planKey());
        assertNull(r.repairPair());
        assertNull(r.question());
    }

    @Test
    void aQuestionRecordsOnlyTheQuestion() {
        var r = RelayRecording.of("K", new RelayOutput("{}", "How tall?", null));
        assertEquals("How tall?", r.question());
        assertNull(r.planKey());
        assertNull(r.repairPair());
    }

    @Test
    void aDeclineOrNoOutputRecordsNothing() {
        assertEquals(new RelayRecording(null, null, null, null),
                RelayRecording.of("K", new RelayOutput("{}", null, null)));
        assertEquals(new RelayRecording(null, null, null, null), RelayRecording.of("K", null));
    }
}
