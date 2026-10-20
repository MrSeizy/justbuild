package dev.justbuild.relay;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RelayGateTest {
    static final Instant T0 = Instant.parse("2026-10-12T12:00:00Z");

    @org.junit.jupiter.api.BeforeAll
    static void registries() {
        dev.justbuild.world.McBootstrap.init();
    }

    @Test
    void nothingIsAllowedUntilThePlayerStartsSomething() {
        RelayGate gate = new RelayGate();
        assertFalse(gate.allow(T0));
        gate.command("justbuild status", T0);
        gate.command("justbuild undo confirm", T0);
        gate.command("justbuild templates castle", T0);
        gate.command("give @s stone", T0);
        gate.command("justbuild", T0);
        for (String command : new String[] {"select abc", "select clear", "rename House", "checkpoint save Original",
                "checkpoint restore Original", "design save Cottage", "design place abc 90 oak_planks=spruce_planks", "retry",
                "design export abc", "history", "zone 1 0 64 0", "template no_such_template",
                "help", "help 2", "help zone 2", "help topics", "new"}) {
            gate.command("justbuild " + command, T0);
        }
        gate.chat(T0);
        assertFalse(gate.allow(T0));
    }

    @Test
    void aBuildCommandAllowsFourCallsForFifteenMinutes() {
        RelayGate gate = new RelayGate();
        gate.command("justbuild a small stone well", T0);
        for (int i = 0; i < RelayGate.CALLS; i++) {
            assertTrue(gate.allow(T0.plusSeconds(i)), "call " + i);
        }
        assertFalse(gate.allow(T0.plusSeconds(10)));

        gate.command("justbuild edit #e4c91fbd add a garden", T0);
        assertFalse(gate.allow(T0.plus(RelayGate.WINDOW).plusSeconds(1)), "the window expired");
    }

    @Test
    void everyAiStartingSubcommandArms() {
        for (String command : new String[] {"justbuild edit make it taller", "justbuild template castle",
                "justbuild again #abc", "justbuild answer three floors", "  JUSTBUILD   a tower  "}) {
            RelayGate gate = new RelayGate();
            gate.command(command, T0);
            assertTrue(gate.allow(T0), command);
        }
    }

    @Test
    void shortAliasSharesConsentLimitsAndOnlyArmsForAiWork() {
        for (String body : new String[] {"a stone hall", "build a hut", "edit make it taller", "template cottage",
                "again #abc", "answer stone", "alternatives build 2 a tower"}) {
            RelayGate gate = new RelayGate();
            gate.command("jb " + body, T0);
            int calls = body.startsWith("alternatives") ? 2 * RelayGate.CALLS : RelayGate.CALLS;
            for (int i = 0; i < calls; i++) assertTrue(gate.allow(T0), body);
            assertFalse(gate.allow(T0), body);
        }
        for (String command : new String[] {"jb", "jb status", "jb confirm", "jb box clear", "jb undo confirm",
                "aibuild build a hut", "jbother build a hut", "justbuilder build a hut"}) {
            RelayGate gate = new RelayGate();
            gate.command(command, T0);
            assertFalse(gate.allow(T0), command);
        }
    }

    @Test
    void resetForgetsTheArming() {
        RelayGate gate = new RelayGate();
        gate.command("justbuild a castle", T0);
        gate.reset();
        assertFalse(gate.allow(T0), "joining another server must not carry the arming over");
        gate.chat(T0);
        assertFalse(gate.allow(T0));
    }

    @Test
    void chatReArmsOnlyAfterTheAiAskedAQuestion() {
        RelayGate gate = new RelayGate();
        gate.command("justbuild a castle", T0);
        for (int i = 0; i < RelayGate.CALLS; i++) {
            gate.allow(T0);
        }
        gate.chat(T0.plus(Duration.ofMinutes(1)));
        assertFalse(gate.allow(T0.plus(Duration.ofMinutes(1))), "ordinary chat must not renew the allowance");
        assertFalse(gate.awaitingAnswer());

        gate.questionAsked();
        assertTrue(gate.awaitingAnswer());
        gate.chat(T0.plus(Duration.ofMinutes(5)));
        assertTrue(gate.allow(T0.plus(Duration.ofMinutes(6))), "an answer typed in chat continues the conversation");
        assertFalse(gate.awaitingAnswer(), "one answer per question");
        for (int i = 0; i < RelayGate.CALLS; i++) {
            gate.allow(T0.plus(Duration.ofMinutes(6)));
        }
        gate.chat(T0.plus(Duration.ofMinutes(7)));
        assertFalse(gate.allow(T0.plus(Duration.ofMinutes(7))));
    }

    @Test
    void anHourlyCeilingHoldsEvenWhenThePlayerKeepsBuilding() {
        RelayGate gate = new RelayGate();
        int allowed = 0;
        for (int i = 0; i < 40; i++) {
            Instant t = T0.plusSeconds(i * 60L);
            gate.command("justbuild tower " + i, t);
            while (gate.allow(t)) {
                allowed++;
            }
        }
        assertEquals(RelayGate.HOURLY, allowed);
        gate.command("justbuild one more", T0.plus(Duration.ofMinutes(125)));
        assertTrue(gate.allow(T0.plus(Duration.ofMinutes(125))), "the ceiling is a rolling hour");
    }

    @Test
    void reportsTheFirstCallOfEachStart() {
        RelayGate gate = new RelayGate();
        gate.command("justbuild a castle", T0);
        assertTrue(gate.allow(T0));
        assertTrue(gate.lastWasFirst());
        assertTrue(gate.allow(T0));
        assertFalse(gate.lastWasFirst());
    }

    @Test
    void activePlanningExtendsTheWindowWithoutAddingCallsOrCrossingAReset() {
        RelayGate gate = new RelayGate();
        gate.activity(T0);
        assertFalse(gate.allow(T0), "activity alone must never authorize a call");
        gate.command("justbuild a castle", T0);
        assertTrue(gate.allow(T0));
        gate.activity(T0.plus(Duration.ofMinutes(14)));
        gate.activity(T0.plus(Duration.ofMinutes(28)));
        for (int i = 1; i < RelayGate.CALLS; i++) assertTrue(gate.allow(T0.plus(Duration.ofMinutes(30))));
        gate.activity(T0.plus(Duration.ofMinutes(31)));
        assertFalse(gate.allow(T0.plus(Duration.ofMinutes(31))), "activity replenished the call budget");
        gate.reset();
        gate.activity(T0.plus(Duration.ofMinutes(32)));
        assertFalse(gate.allow(T0.plus(Duration.ofMinutes(32))), "old output revived a revoked grant");
    }
}
