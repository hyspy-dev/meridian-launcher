package meridian.launcher.mitm;

import java.util.List;
import java.util.Map;

/**
 * One decrypted HTTP request/response pair seen by the MITM for an intercepted host.
 * Passed to an {@link ExchangeHandler}, which may observe it (dump) or rewrite the
 * response body (inject).
 */
public record HttpExchange(
        String host,
        String method,
        String path,
        Map<String, List<String>> requestHeaders,
        byte[] requestBody,
        int status,
        Map<String, List<String>> responseHeaders,
        String responseContentType,
        byte[] responseBody) {
}
