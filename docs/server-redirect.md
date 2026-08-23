# Server redirect — routing gameplay UDP through the proxy

The point of the launcher is not just to sign in — it is to put the Meridian proxy
**in the path of the game's multiplayer traffic automatically**, so that when you pick a
server in the in-game browser your gameplay QUIC/UDP flows through the proxy (for
inspection, module logic, a future community list) instead of going straight to the
server. No manual "Direct Connect to localhost", no driver.

## Why it works this way

Hytale multiplayer is **raw QUIC over UDP**, and the client does **not** honour any proxy
setting for it (`HTTPS_PROXY` only affects the HTTP backends, not gameplay). So there is
no way, in pure userspace without a kernel driver (WinDivert/pf/nfqueue), to transparently
intercept the game's outbound UDP and learn where it was going.

What we *can* do without a driver: the game gets its server addresses from the
**server-discovery** HTTP backend, which we already MITM. So we **rewrite the server list**
so every server points at a local port, and run the proxy in **multiplex** mode binding
one port per server. The game connects to loopback; the proxy relays to the real host.

## The pieces

| Piece | Role |
|-------|------|
| **Use proxy** checkbox + **Proxy** dropdown | turn it on and pick which `*proxy*.jar` (found next to the launcher) to run |
| `ServerDiscoveryRewriter` | MITM handler on `server-discovery.hytale.com`: rewrites each listing entry's `host`/`port` to `127.0.0.1:<localPort>` (all other fields preserved) |
| `RouteRegistry` | allocates a stable local port per `host:port` (from `16000`) and announces it |
| `ProxyControl` | the launcher's control channel to the proxy — commands over the proxy's **stdin** (a parent→child pipe, no files) |
| multiplex proxy (`--multiplex`) | binds each announced port and relays it to the real server; see the proxy's [launch-modes.md](../../meridian-proxy/docs/launch-modes.md#mode-4--multiplex-launcher-driven) |

## End-to-end flow

1. **Launch** with **Use proxy** on. The launcher:
   - starts the selected proxy jar with `--multiplex` (its stdin piped),
   - sends `TOKEN <player session token>` down that pipe,
   - starts a MITM proxy on `server-discovery.hytale.com` with `ServerDiscoveryRewriter`,
   - launches the game pointed at that MITM (via `HTTPS_PROXY`).
2. The game fetches the **server list** → the rewriter turns every entry into
   `127.0.0.1:<localPort>` and sends `ROUTE <localPort> <host> <port>` to the proxy, which
   binds that port (idle until used).
3. You **pick a server in-game**. The client Direct-Connects to `127.0.0.1:<localPort>` →
   the proxy relays to the real server. **All gameplay UDP now flows through the proxy.**
4. On the **first connection** to a port the proxy does its lazy pickup: derive the
   server-scope tokens and load that server's `<jar-dir>/<host_port>/modules`.
5. **Game exits** → the launcher closes the control pipe and kills the proxy.

## Manual servers (not in the browser)

A server you type into the game's Direct Connect is *not* in the rewritten list, so it
would bypass the proxy. To route it, use the proxy's **own window** — it is the normal
standalone UI (a Connect bar) shown alongside the multiplex. Type the server there and
Direct-Connect to `localhost` in the game. Same UI as running the proxy standalone; there
is no separate launcher field for this by design.

(A fully transparent manual redirect — type the real host in-game, we intercept the UDP —
would require a kernel driver like WinDivert. That is a possible future, Windows-first.)

## Token changes

The player token is pushed over the pipe (`TOKEN`), not baked in at start, so a re-mint
can be propagated to a running proxy without restarting it. The proxy re-derives its
server-scope tokens whenever the player token changes or the derived ones near expiry, so
it never runs on a stale token. (The launcher currently sends the token once at launch; a
background refresh that resends it for very long sessions is a small future add — the
mechanism is already there.)

## Requirements & notes

- **Proxy jar next to the launcher.** The dropdown lists `*proxy*.jar` files in the
  launcher's own folder. Drop a current `meridian-proxy-*-all.jar` there. An old proxy jar
  without `--multiplex` support falls back to its default single-target mode (`5520→5521`)
  — use a build that has multiplex.
- **CA trust.** Rewriting the list means MITM-ing an HTTPS backend, so the launcher's local
  CA must be trusted (installed automatically on Windows for the launch; `SSL_CERT_FILE` on
  other OSes). This is the same CA used by `probe` / `capture-params`.
- **Telemetry.** The **Block telemetry** checkbox composes with proxy mode — the same local
  proxy refuses the telemetry hosts at CONNECT.
- **Crypto is the proxy's job.** The proxy terminates QUIC with a self-signed cert and
  forwards the client's SNI to the backend; nothing to configure here.

See also: [README](../README.md) · proxy [launch-modes.md](../../meridian-proxy/docs/launch-modes.md) ·
proxy [modules.md](../../meridian-proxy/docs/modules.md)
