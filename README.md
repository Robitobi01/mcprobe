# mcprobe

Java agent for Minecraft 1.12.2 servers (vanilla and carpet, Java 8). It publishes
live server data as small files, meant for a tmpfs directory such as `/run/mcprobe/<instance>`:

| file          | content                                                        | refresh (default) |
|---------------|----------------------------------------------------------------|-------------------|
| `ticks.json`  | `{pid, time, tick, tickNanos[...]}`: durations in ns of the last 99 finished ticks, oldest to newest (MSPT = mean / 1e6); `tick` is the tick in progress or just finished | `ticks=1000` ms |
| `chunks.json` | `{pid, time, dimensions: [{id, name, loaded, lazy, chunks: [[x,z], ...], lazyChunks: [[x,z], ...]}]}`: all loaded chunks (chunk coordinates) and the lazy ones among them; only rewritten when a set changes, `time` = when it changed | `chunks=1000` ms |
| `key.json`    | the server RSA key pair `{algorithm, publicX509, privatePkcs8}` (base64 DER), mode 0600 | once, only with `key=true` |

```sh
java -javaagent:mcprobe.jar=dir=/run/mcprobe/survival,ticks=1000,chunks=1000,key=false -jar server.jar nogui
```

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

## Development

```sh
nix-shell                       # JDK 8 + python
sh scripts/build.sh             # -> build/mcprobe.jar
python3 tests/integration.py path/to/vanilla_1.12.2.jar   # starts a temporary server, checks all outputs
```

`package.nix` is the Nix package (`callPackage ./package.nix { }`).
