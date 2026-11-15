# mcprobe

Java agent for Minecraft 1.12.2 servers (vanilla and carpet, Java 8). It publishes
live server data as small files, meant for a tmpfs directory such as `/run/mcprobe/<instance>`:

| file          | content                                                        | refresh (default) |
|---------------|----------------------------------------------------------------|-------------------|
| `ticks.json`  | `{pid, time, tick, tickNanos[100]}`: duration of the last 100 ticks in ns, oldest to newest (MSPT = mean / 1e6) | `ticks=1000` ms |
| `chunks.json` | `{pid, time, dimensions: [{id, name, loaded, chunks: [[x,z], ...]}]}` (chunk coordinates) | `chunks=10000` ms |
| `key.json`    | the server RSA key pair `{algorithm, publicX509, privatePkcs8}` (base64 DER), mode 0600 | once, only with `key=true` |

```sh
java -javaagent:mcprobe.jar=dir=/run/mcprobe/survival,ticks=1000,chunks=10000,key=false -jar server.jar nogui
```

- Files are replaced atomically (write + rename): readers never see partial content and can
  watch the directory with inotify instead of polling. `time` is the capture time in ms since the
  epoch, so stale data (e.g. after `kill -9`) is easy to detect. On a normal stop the files are removed.
- Read-only: no class transformation. Tick times are copied from a low-priority daemon thread;
  only the chunk coordinate copy runs on the server thread (microseconds, once per `chunks` interval).
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
