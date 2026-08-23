# meridian-launcher

Makes Meridian the Hytale **launcher**: it signs in to the account, mints a game
session, and starts the game client with that session in its environment. The point is
legitimate — own the session mint so the proxy is handed a fresh token instead of
snooping one out of a running game process, keep the player in control of what the
client does at startup, and lay the groundwork for a community server list later.

Nothing here is an exploit: the account flow is the standard OAuth2 the official
launcher uses (`client_id=hytale-launcher`), ported faithfully from the reference Rust
client (`hytale-monitor`).

## What it does today (Part 1)

- **Sign in** — OAuth2 authorization-code + PKCE over a loopback redirect.
- **Refresh** — renew silently from a stored refresh token; interactive login only when
  there is nothing to refresh.
- **Mint** — `get-launcher-data` → first profile → `game-session/new`, yielding the
  `sessionToken` + `identityToken` the client needs.
- **Persist** — store accounts (owner-only) in `accounts.json`, so login is a one-time
  step. Everything the launcher writes — accounts, captured server params, the MITM CA,
  settings — lives in a `meridian/` folder **next to the launcher jar**, not the home dir
  and **not the registry**; delete the jar's folder and nothing is left behind.
- **Launch** — start the client with the session in its environment.

Beyond Part 1, the launcher now also drives the proxy so the whole in-game server browser
routes through it automatically — see **[docs/server-redirect.md](docs/server-redirect.md)**.

## The launch contract

Confirmed against the official launcher's own log. Two halves, both required — the tokens
in the environment, the identity and paths as arguments. Passing only the env (which is
what the proxy's snooper reads off a running game) makes the client reject the launch with
*"Authentication credentials required. The game must be launched through the official
launcher."*

Environment:

| Variable | Value |
|----------|-------|
| `HYTALE_SESSION_TOKEN`  | player session token (also what the proxy consumes) |
| `HYTALE_IDENTITY_TOKEN` | identity token |

Arguments (built by `ClientInstall` from the executable's location):

```
--app-dir   <install>/<patchline>/package/game/latest
--user-dir  %APPDATA%/Hytale/data/<patchline>
--java-exec <install>/<patchline>/package/jre/latest/bin/java.exe
--auth-mode authenticated
--uuid      <profile uuid>
--name      <profile name>
```

The client passes the env tokens down to the singleplayer server it spawns, which reads
them "from environment" — so both the client and its server authenticate off one launch.

## Accounts & token sharing

The launcher stores several **accounts** (`accounts.json` next to the jar, owner-only) so
any of them starts in a couple of clicks — pick one from the dropdown (or `--account NAME`)
and Launch. "Account" is the login; one account can hold several in-game **profiles**
(distinct `{uuid, username}`), each shown as its own row in the dropdown. On open, the
launcher refreshes each account's profile list from the account service where the stored
refresh token still works — so newly added/renamed profiles appear without re-adding.

Hytale allows one active session per account, and **minting** (`game-session/new`)
invalidates the account's other live sessions. The game never mints on its own — it uses
the token the launcher puts in its environment — so the only thing that can kill a running
window's session is another mint by the launcher.

A launch therefore **reuses the account's stored token when it is still live**, and mints
only when it is not. Liveness is confirmed against the backend (a JWT that has not expired
can still be dead server-side, superseded by a later mint); only a confirmed-live token is
reused, and any doubt mints a fresh one so the game is never handed a dead token. Reuse is
what lets several windows of one account share a session: the second window reuses the
first's live token instead of minting a new one that would evict it.

## CLI

```bash
java -jar meridian-launcher-*.jar <command>

  gui                            open the window (default)
  accounts                       list stored accounts (with their profiles)
  login                          add an account (interactive sign-in)
  session [--account NAME]       return a usable session (reuse/refresh), print a summary
  launch  [--account NAME] [--hytale ROOT] [--version NAME]   start the client
  servers [--sort featured|random|favorite] [--version V]     list servers (no game launch)
  capture-params [--version NAME]   capture a version's server-list params (one launch)
  logout  [--account NAME]       remove an account
```

The GUI has two tabs — **Launch** (account/profile, version, proxy jar, Use proxy / Block
telemetry, Launch) and **Servers** (browse Featured / Random / Favorites per captured
version).

The client path is auto-detected, with `--client <path>` or `-Dmeridian.client=<path>`
as the override.

## Hytale folder & version

You point the launcher at the **Hytale folder** (the root holding `install/` and `data/`;
`%APPDATA%\Hytale` by default, editable for a custom install) and pick a **version** from
the dropdown. Versions are the `install/` subfolders that contain a runnable client —
`pre-release`, `release`, `v0.4`, … — with the active one (from `patchline.json`) first.

Each version resolves to:

```
<root>/install/<version>/package/game/latest/Client/HytaleClient.exe   ← client
<root>/install/<version>/package/game/latest                            ← --app-dir
<root>/install/<version>/package/jre/latest/bin/java.exe                ← --java-exec
<root>/data/<version>                                                   ← --user-dir
```

The per-version `--user-dir` means different versions keep separate saves/settings.
CLI selectors: `--hytale <root> --version <name>` (or `--client <exe>` to point straight
at an executable). Verified against a real install on Windows; macOS/Linux use the
equivalent `Application Support` / `~/.local/share` roots (best-effort).

## Server redirect (Use proxy)

The launch arguments carry no server address — the client reaches multiplayer through its
own Direct Connect UI and the **server browser**. So rather than route a single server, the
launcher routes the **whole browser**: tick **Use proxy**, pick a proxy jar (found next to
the launcher), and Launch. The launcher rewrites the in-game server list so every server
points at a local **multiplex proxy**, and drives that proxy over its stdin. You just pick a
server in-game — its gameplay UDP flows through the proxy automatically.

Full mechanism, manual servers, and token handling: **[docs/server-redirect.md](docs/server-redirect.md)**.
Servers not in the browser are handled in the proxy's own Connect bar (Direct-Connect to
`localhost`), the same UI as running the proxy standalone.

## Part 2 — telemetry / server list

The client honours the `HTTPS_PROXY` env we set when we launch it (confirmed), so its
HTTP(S) backends route through a local proxy. The backend map, from a live capture:

| Host | Purpose |
|------|---------|
| `telemetry.hytale.com` | game telemetry |
| `sentry.hytale.com` | crash / error reporting |
| `server-discovery.hytale.com` | server browser |
| `mod-browser.hytale.com` | mod browser |
| `social.hytale.com` | friends / party |
| `account-data.hytale.com` | profile / launcher data |
| `liveconfig.hytale.com` | feature flags / live config |
| `socket-gateway.hytale.com` | realtime websocket gateway |
| `sessions.hytale.com` | session / auth |
| `media.forgecdn.net`, `*.r2.cloudflarestorage.com` | mod media / asset CDN |

**Telemetry control (built).** Blocking a host needs no decryption, so it is immune to
certificate pinning: the proxy refuses the `CONNECT` to the telemetry hosts (502), their
fire-and-forget requests fail, and the game is unaffected.

```bash
java -jar meridian-launcher-*.jar play --block-telemetry     # blocks telemetry + sentry
java -jar meridian-launcher-*.jar play --block host1,host2    # block an explicit list
java -jar meridian-launcher-*.jar capture                     # route through, block nothing (recon)
```

**Server list — browsable, and routable.** `server-discovery.hytale.com` is interceptable
(confirmed — the real client trusts our CA, it does not pin). Two things build on that:

- The launcher can **read the browser itself** — a **Servers** tab lists Featured / Random /
  Favorites for a captured game version, using your minted token, *without launching the
  game*. The build-bound params (`protocolVersion` / `clientSeed`) are captured on first
  launch of a version (or via `capture-params`) and cached next to the jar.
- With **Use proxy**, the launcher **rewrites** that same response so gameplay routes through
  the proxy — see **[docs/server-redirect.md](docs/server-redirect.md)**.

The MITM under both is the same: a local CA (in the launcher's folder, BouncyCastle), a
per-host leaf minted on the fly, TLS terminated with that leaf, the plaintext HTTP relayed
(and optionally rewritten). You can still probe a host's pinning directly:

```bash
java -jar meridian-launcher-*.jar probe          # server-discovery + mod-browser
java -jar meridian-launcher-*.jar probe --host server-discovery.hytale.com
```

`probe` installs the CA into the current user's trust store (Windows: `CurrentUser\Root`,
no admin; other OSes: pointed at via `SSL_CERT_FILE` in the launch env), MITMs the target
hosts, launches the game, and — after you open the server browser and quit — reports per
host:

- **INTERCEPTABLE** — the client trusted our cert; injection is possible.
- **PINNED / rejected** — the client refused our cert; injection is not possible for that
  host.

The CA is removed again at the end unless `--keep-ca` is given. Cross-platform: cert
minting and the proxy are pure Java; only the CA-trust step is per-OS (Windows now, the
Linux `SSL_CERT_FILE` path already wired, macOS login-keychain later).

Once a host proves INTERCEPTABLE, response rewriting (adding community servers) slots into
the MITM relay.
