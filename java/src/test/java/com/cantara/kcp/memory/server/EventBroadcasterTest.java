package com.cantara.kcp.memory.server;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD tests for EventBroadcaster — fan-out of JSON events to WebSocket subscribers.
 */
class EventBroadcasterTest {

    private EventBroadcaster broadcaster;

    @BeforeEach
    void setUp() {
        broadcaster = new EventBroadcaster();
    }

    @Test
    void broadcastToZeroSubscribersDoesNotThrow() {
        assertDoesNotThrow(() -> broadcaster.broadcast("{\"type\":\"test\"}"));
    }

    @Test
    void broadcastDeliversMessageToOneSubscriber() throws IOException {
        PipedInputStream pipeIn = new PipedInputStream();
        PipedOutputStream pipeOut = new PipedOutputStream(pipeIn);

        broadcaster.subscribe(pipeOut);
        assertEquals(1, broadcaster.subscriberCount());

        broadcaster.broadcast("{\"event\":\"hello\"}");

        // Read the WebSocket frame from the pipe
        String received = WebSocketFrame.decode(pipeIn);
        assertEquals("{\"event\":\"hello\"}", received);
    }

    @Test
    void broadcastDeliversToMultipleSubscribers() throws IOException {
        PipedInputStream pipeIn1 = new PipedInputStream();
        PipedOutputStream pipeOut1 = new PipedOutputStream(pipeIn1);
        PipedInputStream pipeIn2 = new PipedInputStream();
        PipedOutputStream pipeOut2 = new PipedOutputStream(pipeIn2);

        broadcaster.subscribe(pipeOut1);
        broadcaster.subscribe(pipeOut2);
        assertEquals(2, broadcaster.subscriberCount());

        broadcaster.broadcast("{\"event\":\"multi\"}");

        assertEquals("{\"event\":\"multi\"}", WebSocketFrame.decode(pipeIn1));
        assertEquals("{\"event\":\"multi\"}", WebSocketFrame.decode(pipeIn2));
    }

    @Test
    void deadSubscriberIsSilentlyRemovedOnNextBroadcast() throws IOException {
        PipedInputStream pipeIn = new PipedInputStream();
        PipedOutputStream pipeOut = new PipedOutputStream(pipeIn);

        broadcaster.subscribe(pipeOut);
        assertEquals(1, broadcaster.subscriberCount());

        // Close the pipe to simulate a dead client
        pipeIn.close();
        pipeOut.close();

        // This broadcast should detect the dead subscriber and remove it
        broadcaster.broadcast("{\"event\":\"after-close\"}");
        assertEquals(0, broadcaster.subscriberCount());
    }

    @Test
    void subscriberCountReturnsCorrectValueAfterOperations() throws IOException {
        assertEquals(0, broadcaster.subscriberCount());

        PipedInputStream pipeIn1 = new PipedInputStream();
        PipedOutputStream pipeOut1 = new PipedOutputStream(pipeIn1);
        PipedInputStream pipeIn2 = new PipedInputStream();
        PipedOutputStream pipeOut2 = new PipedOutputStream(pipeIn2);

        broadcaster.subscribe(pipeOut1);
        assertEquals(1, broadcaster.subscriberCount());

        broadcaster.subscribe(pipeOut2);
        assertEquals(2, broadcaster.subscriberCount());

        broadcaster.unsubscribe(pipeOut1);
        assertEquals(1, broadcaster.subscriberCount());
    }

    @Test
    void unsubscribeRemovesSpecificSubscriber() throws IOException {
        PipedInputStream pipeIn1 = new PipedInputStream();
        PipedOutputStream pipeOut1 = new PipedOutputStream(pipeIn1);
        PipedInputStream pipeIn2 = new PipedInputStream();
        PipedOutputStream pipeOut2 = new PipedOutputStream(pipeIn2);

        broadcaster.subscribe(pipeOut1);
        broadcaster.subscribe(pipeOut2);
        broadcaster.unsubscribe(pipeOut1);

        broadcaster.broadcast("{\"event\":\"only-sub2\"}");

        // sub2 should still receive
        assertEquals("{\"event\":\"only-sub2\"}", WebSocketFrame.decode(pipeIn2));

        // sub1 pipe should have no data (nothing was written to it after unsubscribe)
        assertEquals(0, pipeIn1.available());
    }
}
