package meridian.launcher.launch;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The running proxy's command channel, as the launcher sets it up: a TCP port on the loopback
 * address and a token, both chosen here before the proxy starts and handed to it.
 *
 * <p>The port is the first one of the launch's route slot - the number the proxy's first UDP
 * listener has; TCP and UDP of one number do not collide. Single-player worlds join through it
 * (see {@link SingleplayerShim}), and so do local tools: the proxy also writes it and the token to
 * {@code <proxy dir>/control/<port>.json}.
 *
 * <p>Proxies before 1.0.10 only knew a single-player door ({@code --sp-control},
 * {@code MERIDIAN_SP_SECRET}, {@code MERIDIAN_SP_CONTROL}); the old names are passed alongside, so
 * an older proxy still takes its worlds.
 */
public record ProxyControlPort(int port, String token) {

    /** The token, for the proxy. */
    public static final String ENV_TOKEN = "MERIDIAN_CONTROL_TOKEN";
    /** Where the proxy is, {@code 127.0.0.1:<port>}, for whatever the game starts. */
    public static final String ENV_ADDRESS = "MERIDIAN_CONTROL";

    private static final String OLD_ENV_SECRET = "MERIDIAN_SP_SECRET";
    private static final String OLD_ENV_ADDRESS = "MERIDIAN_SP_CONTROL";

    /** A channel on {@code port} with a fresh token. */
    public static ProxyControlPort create(int port) {
        byte[] b = new byte[24];
        new SecureRandom().nextBytes(b);
        return new ProxyControlPort(port, HexFormat.of().formatHex(b));
    }

    /** What the proxy is started with, after {@code --multiplex}. */
    public List<String> proxyArgs() {
        String p = Integer.toString(port);
        return List.of("--control-port", p, "--sp-control", p);
    }

    /** What goes into the proxy's environment. */
    public Map<String, String> proxyEnv() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(ENV_TOKEN, token);
        m.put(OLD_ENV_SECRET, token);
        return m;
    }

    /** What goes into the game's environment, for the processes it starts to find the proxy. */
    public Map<String, String> clientEnv() {
        Map<String, String> m = new LinkedHashMap<>();
        String address = "127.0.0.1:" + port;
        m.put(ENV_ADDRESS, address);
        m.put(ENV_TOKEN, token);
        m.put(OLD_ENV_ADDRESS, address);
        m.put(OLD_ENV_SECRET, token);
        return m;
    }
}
