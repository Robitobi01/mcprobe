# mcprobe

Live data and on-demand profiling for Minecraft 1.12.2 servers (vanilla and carpet, Java 8):

- **mcprobe.jar**, a Java agent inside the server, publishes small files in a tmpfs directory
  such as `/run/mcprobe/<instance>` and profiles the server thread while someone watches.
- **mcprobe-web**, one small service per host, serves those files, live streams and a
  profiler viewer (flame graph with MCP names) for all instances of the host.

## Agent

Files in the agent's directory:

| file          | content                                                        | refresh (default) |
|---------------|----------------------------------------------------------------|-------------------|
| `ticks.json`  | `{pid, time, tick, tickNanos[...]}`: durations in ns of the last 99 finished ticks, oldest to newest (MSPT = mean / 1e6); `tick` is the tick in progress or just finished | `ticks=1000` ms |
| `chunks.json` | `{pid, time, dimensions: [{id, name, loaded, lazy, chunks: [[x,z], ...], lazyChunks: [[x,z], ...]}]}`: all loaded chunks (chunk coordinates) and the lazy ones among them; only rewritten when a set changes, `time` = when it changed | `chunks=1000` ms |
| `key.json`    | the server RSA key pair `{algorithm, publicX509, privatePkcs8}` (base64 DER), mode 0600 | once, only with `key=true` |

```sh
java -javaagent:mcprobe.jar=dir=/run/mcprobe/survival,ticks=1000,chunks=1000,key=false,profiler=/path/libasyncProfiler.so \
  -jar server.jar nogui
```

With `profiler=` (async-profiler's library), the agent profiles while `profile.request` in its
directory was touched in the last 30 s, and writes `profile.collapsed` (`# start=<ms>` plus
collapsed stacks per thread with JVM signatures, cumulative since the start) every second.
Sampling is CPU time (`itimer`, 2 ms) in async-profiler's native code: no attach, no safepoints,
nothing scheduled on the server thread. Without readers it is stopped again.

- Files are replaced atomically (write + rename): readers never see partial content and can
  watch the directory with inotify instead of polling. `time` in `ticks.json` is the capture time
  in ms since the epoch, so stale data (e.g. after `kill -9`) is easy to detect. On a normal stop the
  files are removed.
- **Lazy chunks**: 1.12 updates an entity only if all chunks within 2 chunks of it are loaded
  (a +-32 block area check). A loaded chunk where that fails is lazy: blocks and tile entities tick,
  entities are frozen. Every loaded chunk not in `lazyChunks` processes entities.
- Read-only and zero work on the server thread: no class transformation, nothing scheduled on
  the server. Everything runs on one low-priority daemon thread. The chunk map is read directly
  from that thread; a read that overlaps a chunk load/unload is detected and retried.
  `0` disables an output.
- The agent never prevents the server from starting; problems are logged as `[mcprobe] ...`.
- The RSA key pair is generated at every server start. Clients negotiate a separate AES secret per
  connection, so decrypting a session also needs its captured login/encryption exchange.

Obfuscated names are for the 1.12.2 server jar (see the constants in `McProbe.java`).

## mcprobe-web

```sh
mcprobe-web -listen 10.0.0.2:23000 -instance survival=/run/mcprobe/survival [-instance ...]
```

| path                                  | content |
|---------------------------------------|---------|
| `/<instance>/`                        | live viewer: MSPT, flame graph of the server thread (click to zoom, search, ms per tick), self time |
| `/<instance>/stream?window=30`        | server-sent events, 1/s: profile of the last `window` seconds + ticks (keeps the profiler running) |
| `/<instance>/watch[?ticks][&chunks]`  | server-sent events whenever `ticks.json` / `chunks.json` change, for long-running subscribers |
| `/<instance>/ticks`, `/<instance>/chunks`, `/<instance>/profile?window=30` | the same as single JSON responses |

Run it as the user owning the agent's directories (it touches `profile.request`). `key.json` is
never served. Names are translated with the MCP 1.12.2 mappings (`joined.srg` from
`mcp-1.12.2-srg.zip`, `methods.csv` from `mcp_stable-39-1.12.zip`), which `package.nix` fetches
into `share/mcprobe/mappings`; `-mappings DIR` overrides that. The service is meant for a private
network or a reverse proxy with authentication.

## Development

```sh
nix-shell                       # JDK 8, Go, python
sh scripts/build.sh             # -> build/mcprobe.jar
(cd web && go run . -listen 127.0.0.1:23000 -instance test=/tmp/out -mappings DIR)
python3 tests/integration.py path/to/vanilla_1.12.2.jar   # starts a temporary server, checks all agent outputs
```

`package.nix` builds both (`callPackage ./package.nix { }`). `src/main/java/one/profiler/` is
async-profiler's Java API (Apache-2.0, like this project).
