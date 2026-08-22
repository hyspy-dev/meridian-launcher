package meridian.launcher.mitm;

/**
 * Sees a decrypted {@link HttpExchange} for an intercepted host and returns the response
 * body to send to the client — unchanged to merely observe (dump), or rewritten to inject.
 */
@FunctionalInterface
public interface ExchangeHandler {

    /** @return the response body to relay to the client (return {@code ex.responseBody()} to pass through) */
    byte[] handle(HttpExchange ex);
}
