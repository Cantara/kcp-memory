package com.cantara.kcp.memory.handler;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.logging.Logger;

/**
 * Sends Slack messages via the IronClaw bot token.
 *
 * <p>Reads {@code SLACK_MIMIR_BOT_TOKEN} from the environment.
 * If the token is not set, all calls are silent no-ops.
 *
 * <p>All sends are fire-and-forget on a virtual thread.
 */
public class SlackNotifier {

    private static final Logger LOG = Logger.getLogger(SlackNotifier.class.getName());
    private static final String TOKEN_ENV = "SLACK_MIMIR_BOT_TOKEN";
    private static final String API_URL   = "https://slack.com/api/chat.postMessage";

    private SlackNotifier() {}

    /**
     * Send a message asynchronously. Returns immediately; does nothing if token is absent.
     *
     * @param channel Slack user ID (e.g. {@code U0388UPS9HN}) or channel ID
     * @param text    message text (Slack markdown supported)
     */
    public static void notifyAsync(String channel, String text) {
        String token = System.getenv(TOKEN_ENV);
        if (token == null || token.isBlank()) {
            LOG.fine("SLACK_MIMIR_BOT_TOKEN not set — skipping Slack notification");
            return;
        }
        Thread.ofVirtual().start(() -> {
            try {
                String escaped = text
                        .replace("\\", "\\\\")
                        .replace("\"", "\\\"")
                        .replace("\n", "\\n")
                        .replace("\r", "");
                String payload = "{\"channel\":\"" + channel + "\",\"text\":\"" + escaped + "\"}";

                HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .build();
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(API_URL))
                        .timeout(Duration.ofSeconds(15))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/json; charset=UTF-8")
                        .POST(HttpRequest.BodyPublishers.ofString(payload))
                        .build();

                HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200 && resp.body().contains("\"ok\":true")) {
                    LOG.fine("Slack notification sent to " + channel);
                } else {
                    LOG.warning("Slack notification failed (" + resp.statusCode() + "): " + resp.body());
                }
            } catch (Exception e) {
                LOG.warning("Slack notification error: " + e.getMessage());
            }
        });
    }
}
