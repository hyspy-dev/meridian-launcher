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
- **Persist** — store the session (owner-only) at
  `~/.meridian/launcher-session.json`, so login is a one-time step.
- **Launch** — start the client with the session in its environment.

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

The launcher stores several **accounts** (`~/.meridian/accounts.json`, owner-only) so any
of them starts in a couple of clicks — pick one from the dropdown (or `--account NAME`) and
Launch. "Account" is the login, not the in-game profile.

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
  accounts                       list stored accounts
  login                          add an account (interactive sign-in)
  session [--account NAME]       return a usable session (reuse/refresh), print a summary
  launch  [--account NAME] [--client PATH]   start the client for an account
  logout  [--account NAME]       remove an account
```

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

## Server target

The launch arguments carry no server address — the client reaches multiplayer through its
own Direct Connect UI. So with the proxy, the player still Direct-Connects to `localhost`,
exactly as today; the launcher's job is to get the game signed in, not to route it.

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

**Server list / community servers — Stage B, MITM built; pinning test pending.**
Injecting into the server browser means decrypting `server-discovery.hytale.com` (read its
response, add servers). The MITM is built and verified locally: a local CA
(`~/.meridian/ca`, BouncyCastle), a per-host leaf minted on the fly, TLS terminated with
that leaf, the plaintext HTTP relayed to the real server. Whether it works against the
real client depends on whether that host pins — tested with `probe`:

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
