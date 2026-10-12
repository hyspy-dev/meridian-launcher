package meridian.launcher.mitm;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Carries one WebSocket through the MITM. The client's upgrade request goes to the real server,
 * the server's answer comes back, and once it is a 101 the frames are relayed both ways.
 *
 * <p>What the client sends is copied byte for byte. What the server sends is read frame by frame
 * so that each whole text message - reassembled when it arrives in fragments - can pass through
 * a {@link WebSocketHandler} and be rewritten. Binary and control frames, and anything the
 * relay cannot read as plain text, go through untouched.
 *
 * <p>The upgrade is forwarded without {@code Sec-WebSocket-Extensions}, so the server does not
 * agree to compress: a compressed message could only be passed on, never read.
 */
final class WebSocketRelay {

    private static final Logger log = LoggerFactory.getLogger(WebSocketRelay.class);

    /** Longest message the relay will hold in memory to rewrite; a longer one is refused. */
    private static final int MAX_MESSAGE = 16 << 20;
    /** Characters of a message written to the request log. */
    private static final int LOG_CAP = 2000;

    private WebSocketRelay() {
    }

    /** Whether a request asks to become a WebSocket. */
    static boolean isUpgrade(Map<String, String> headers) {
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey().equalsIgnoreCase("upgrade")
                    && e.getValue().toLowerCase(Locale.ROOT).contains("websocket")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Opens the real connection, forwards the upgrade and its answer, and relays until either
     * side closes. Returns when the WebSocket is done.
     *
     * @param requestLog where each server message is written when request logging is on, or null
     */
    static void run(SSLSocket client, InputStream clientIn, OutputStream clientOut,
                    String host, int port, String method, String path, Map<String, String> headers,
                    SSLContext upstreamContext, WebSocketHandler handler, Consumer<String> requestLog)
            throws IOException {
        try (SSLSocket upstream = (SSLSocket) upstreamContext.getSocketFactory().createSocket(host, port)) {
            upstream.startHandshake();
            InputStream upIn = upstream.getInputStream();
            OutputStream upOut = upstream.getOutputStream();

            StringBuilder request = new StringBuilder(512);
            request.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
            request.append("Host: ").append(port == 443 ? host : host + ":" + port).append("\r\n");
            headers.forEach((k, v) -> {
                String n = k.toLowerCase(Locale.ROOT);
                if (!n.equals("host") && !n.equals("sec-websocket-extensions")
                        && !n.equals("proxy-connection")) {
                    request.append(k).append(": ").append(v).append("\r\n");
                }
            });
            request.append("\r\n");
            upOut.write(request.toString().getBytes(StandardCharsets.ISO_8859_1));
            upOut.flush();

            byte[] answer = readHead(upIn);
            clientOut.write(answer);
            clientOut.flush();
            String status = new String(answer, 0, Math.min(answer.length, 64), StandardCharsets.ISO_8859_1);
            if (!status.startsWith("HTTP/1.1 101")) {
                // Refused: whatever body follows goes to the client as it comes, then both close.
                upIn.transferTo(clientOut);
                return;
            }
            log.info("WebSocket {}{} open through the MITM", host, path);
            if (requestLog != null) {
                requestLog.accept("WS https://" + host + path + " (open)");
            }

            // Client to server: the client's frames are masked and are only ever passed on.
            Thread up = Thread.startVirtualThread(() -> {
                try {
                    clientIn.transferTo(upOut);
                } catch (IOException ignored) {
                    // the other direction notices and closes both
                } finally {
                    closeQuietly(upstream);
                }
            });
            try {
                relayFromServer(upIn, clientOut, handler, host, requestLog);
            } finally {
                closeQuietly(client);
                up.interrupt();
            }
        }
    }

    /** Server to client, frame by frame, text messages through the handler. */
    private static void relayFromServer(InputStream in, OutputStream out, WebSocketHandler handler,
                                        String host, Consumer<String> requestLog) throws IOException {
        ByteArrayOutputStream fragments = null;   // a text message arriving in pieces
        while (true) {
            int b0 = in.read();
            if (b0 < 0) return;
            int b1 = readByte(in);
            boolean fin = (b0 & 0x80) != 0;
            int rsv = b0 & 0x70;
            int opcode = b0 & 0x0F;
            boolean masked = (b1 & 0x80) != 0;
            long length = b1 & 0x7F;
            ByteArrayOutputStream header = new ByteArrayOutputStream(14);
            header.write(b0);
            header.write(b1);
            if (length == 126) {
                byte[] ext = readFully(in, 2);
                header.write(ext);
                length = ((ext[0] & 0xFF) << 8) | (ext[1] & 0xFF);
            } else if (length == 127) {
                byte[] ext = readFully(in, 8);
                header.write(ext);
                length = 0;
                for (byte b : ext) length = (length << 8) | (b & 0xFF);
            }
            byte[] mask = masked ? readFully(in, 4) : null;
            if (mask != null) header.write(mask);
            if (length > MAX_MESSAGE) {
                throw new IOException("WebSocket frame of " + length + " bytes from " + host);
            }
            byte[] payload = readFully(in, (int) length);

            boolean text = opcode == 0x1 || (opcode == 0x0 && fragments != null);
            if (rsv != 0 || masked || !text) {
                // Compressed, oddly masked, binary or control: not ours to read, passed on whole.
                out.write(header.toByteArray());
                out.write(payload);
                out.flush();
                if (opcode == 0x8) return;   // close
                continue;
            }
            if (!fin) {
                if (fragments == null) fragments = new ByteArrayOutputStream();
                fragments.write(payload);
                if (fragments.size() > MAX_MESSAGE) {
                    throw new IOException("WebSocket message over " + MAX_MESSAGE + " bytes from " + host);
                }
                continue;
            }
            byte[] whole = payload;
            if (fragments != null) {
                fragments.write(payload);
                whole = fragments.toByteArray();
                fragments = null;
            }
            String message = new String(whole, StandardCharsets.UTF_8);
            String sent;
            try {
                sent = handler.fromServer(message);
            } catch (RuntimeException e) {
                log.warn("WebSocket handler failed on a {} message: {} - passed on unchanged", host, e.toString());
                sent = message;
            }
            if (sent == null) sent = message;
            if (requestLog != null) {
                requestLog.accept("WS https://" + host + " <- " + cap(message)
                        + (sent.equals(message) ? "" : "\n    rewritten to: " + cap(sent)));
            }
            writeText(out, sent.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** One unmasked, unfragmented text frame - how a server sends to a client. */
    private static void writeText(OutputStream out, byte[] payload) throws IOException {
        ByteArrayOutputStream frame = new ByteArrayOutputStream(payload.length + 10);
        frame.write(0x81);
        if (payload.length < 126) {
            frame.write(payload.length);
        } else if (payload.length <= 0xFFFF) {
            frame.write(126);
            frame.write(payload.length >>> 8);
            frame.write(payload.length & 0xFF);
        } else {
            frame.write(127);
            long n = payload.length;
            for (int shift = 56; shift >= 0; shift -= 8) frame.write((int) (n >>> shift) & 0xFF);
        }
        frame.write(payload);
        out.write(frame.toByteArray());
        out.flush();
    }

    /** The server's answer to the upgrade: status line and headers, up to the blank line. */
    private static byte[] readHead(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream(512);
        int matched = 0;
        while (matched < 4) {
            int c = in.read();
            if (c < 0) throw new EOFException("upgrade answer cut short");
            head.write(c);
            matched = (c == '\r' || c == '\n') && "\r\n\r\n".charAt(matched) == c ? matched + 1
                    : (c == '\r' ? 1 : 0);
            if (head.size() > 65536) throw new IOException("upgrade answer too long");
        }
        return head.toByteArray();
    }

    private static int readByte(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) throw new EOFException();
        return b;
    }

    private static byte[] readFully(InputStream in, int n) throws IOException {
        byte[] buf = in.readNBytes(n);
        if (buf.length != n) throw new EOFException();
        return buf;
    }

    private static String cap(String s) {
        return s.length() <= LOG_CAP ? s : s.substring(0, LOG_CAP) + "… (" + s.length() + " chars)";
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }
}
