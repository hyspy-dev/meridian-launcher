package meridian.launcher.mitm;

/**
 * Sees each whole text message a server sends down a WebSocket the MITM carries, before the
 * client gets it, and returns what the client should get instead - the message itself to leave
 * it alone.
 */
@FunctionalInterface
public interface WebSocketHandler {

    /** Leaves every message as it is. */
    WebSocketHandler PASS = message -> message;

    String fromServer(String message);
}
