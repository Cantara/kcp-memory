package com.cantara.kcp.memory.server;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

/**
 * Fan-out broadcaster for new events to all connected WebSocket clients.
 *
 * <p>Each subscriber is an {@link OutputStream} representing a WebSocket connection.
 * When {@link #broadcast(String)} is called, the JSON event is encoded as a WebSocket
 * text frame and written to every subscriber. Dead subscribers (closed streams) are
 * silently removed.
 */
public class EventBroadcaster {

    private static final Logger LOG = Logger.getLogger(EventBroadcaster.class.getName());

    private final List<OutputStream> subscribers = new CopyOnWriteArrayList<>();

    /**
     * Add a WebSocket client's output stream as a subscriber.
     */
    public void subscribe(OutputStream clientOut) {
        subscribers.add(clientOut);
    }

    /**
     * Remove a WebSocket client's output stream on disconnect.
     */
    public void unsubscribe(OutputStream clientOut) {
        subscribers.remove(clientOut);
    }

    /**
     * Broadcast a JSON event string to all connected WebSocket clients.
     * The string is encoded as a WebSocket text frame before sending.
     * Dead subscribers are silently removed.
     *
     * @param jsonEvent the JSON string to broadcast
     */
    public void broadcast(String jsonEvent) {
        if (subscribers.isEmpty()) return;

        byte[] frame = WebSocketFrame.encode(jsonEvent);

        for (OutputStream out : subscribers) {
            try {
                out.write(frame);
                out.flush();
            } catch (IOException e) {
                LOG.fine("Removing dead subscriber: " + e.getMessage());
                subscribers.remove(out);
            }
        }
    }

    /**
     * Return the number of currently subscribed clients.
     */
    public int subscriberCount() {
        return subscribers.size();
    }
}
