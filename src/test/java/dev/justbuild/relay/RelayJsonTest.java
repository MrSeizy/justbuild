package dev.justbuild.relay;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class RelayJsonTest {
    @Test
    void requestsRoundTrip() throws Exception {
        RelayJson.Request request = new RelayJson.Request(PromptSpecTest.newBuild(), "masterpiece", 2000, 50000, 96, 300);
        RelayJson.Request back = RelayJson.parseRequest(RelayJson.toJson(request));
        assertEquals(request.spec().userContent(), back.spec().userContent());
        assertEquals("masterpiece", back.detail());
        assertEquals(300, back.timeoutSeconds());
    }

    @Test
    void requestValuesAreClamped() throws Exception {
        RelayJson.Request r = RelayJson.parseRequest("{\"spec\": " + PromptSpecTest.newBuild().toJson() + ","
                + "\"detail\": \"epic\", \"maxOperations\": -5, \"maxBlocks\": 99999999, \"maxSize\": 0,"
                + "\"timeoutSeconds\": 100000}");
        assertEquals("detailed", r.detail());
        assertEquals(1, r.maxOperations());
        assertEquals(RelayJson.MAX_LIMIT, r.maxBlocks());
        assertEquals(1, r.maxSize());
        assertEquals(RelayJson.MAX_TIMEOUT, r.timeoutSeconds());
        assertThrows(RelayJson.RelayException.class, () -> RelayJson.parseRequest("{\"detail\": \"quick\"}"));
        assertThrows(RelayJson.RelayException.class, () -> RelayJson.parseRequest("nope"));
    }

    @Test
    void helloRoundTripsAndToleratesJunk() {
        RelayJson.Hello hello = new RelayJson.Hello(RelayJson.PROTOCOL, true, "Claude (Claude CLI)");
        assertEquals(hello, RelayJson.parseHello(RelayJson.toJson(hello)));
        assertEquals(new RelayJson.Hello(0, false, ""), RelayJson.parseHello("{garbage"));
        assertEquals(new RelayJson.Hello(1, false, "x".repeat(RelayJson.MAX_LABEL)),
                RelayJson.parseHello("{\"protocol\":1,\"label\":\"" + "x".repeat(200) + "\"}"));
    }
}
