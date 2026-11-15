#!/usr/bin/env python3
"""Start a real 1.12.2 server with the agent in a temp dir and check every output file.

usage: python3 tests/integration.py <server.jar> [mcprobe.jar]   (inside nix-shell)
"""
import json
import os
import socket
import stat
import subprocess
import sys
import tempfile
import time
from pathlib import Path


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def wait_for(path, timeout=180):
    end = time.time() + timeout
    while time.time() < end:
        if path.exists():
            return path
        time.sleep(0.5)
    raise TimeoutError(path)


def main():
    server_jar = Path(sys.argv[1]).resolve()
    agent = Path(sys.argv[2] if len(sys.argv) > 2 else "build/mcprobe.jar").resolve()
    with tempfile.TemporaryDirectory() as tmp:
        tmp = Path(tmp)
        work, out = tmp / "server", tmp / "out"
        work.mkdir()
        (work / "eula.txt").write_text("eula=true\n")
        (work / "server.properties").write_text(
            f"server-port={free_port()}\nonline-mode=false\nlevel-name=world\nspawn-protection=0\n")
        java = os.environ.get("JAVA", "java")
        proc = subprocess.Popen(
            [java, "-Xmx1G", f"-javaagent:{agent}=dir={out},ticks=500,chunks=2000,key=true",
             "-jar", str(server_jar), "nogui"],
            cwd=work, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        try:
            wait_for(out / "chunks.json")
            time.sleep(3)  # let a few more ticks and a second chunk snapshot happen

            ticks = json.loads((out / "ticks.json").read_text())
            assert ticks["pid"] == proc.pid, ticks["pid"]
            assert len(ticks["tickNanos"]) == 100 and ticks["tick"] > 0, ticks
            assert ticks["tickNanos"][-1] > 0, "newest sample should be last"
            mspt = sum(ticks["tickNanos"]) / len(ticks["tickNanos"]) / 1e6

            chunks = json.loads((out / "chunks.json").read_text())
            dims = {d["name"]: d for d in chunks["dimensions"]}
            assert set(dims) >= {"overworld", "the_nether", "the_end"}, dims.keys()
            overworld = dims["overworld"]
            assert overworld["id"] == 0 and overworld["loaded"] == len(overworld["chunks"]) > 100, overworld["loaded"]
            # spawn area of a fresh world: decoded chunk coordinates must be small and unique
            coords = {tuple(c) for c in overworld["chunks"]}
            assert len(coords) == overworld["loaded"] and all(abs(x) < 200 and abs(z) < 200 for x, z in coords), coords

            key_file = out / "key.json"
            key = json.loads(key_file.read_text())
            assert key["algorithm"] == "RSA" and key["publicX509"] and key["privatePkcs8"]
            assert stat.S_IMODE(key_file.stat().st_mode) == 0o600, oct(key_file.stat().st_mode)

            print(f"ok: mspt={mspt:.2f} tick={ticks['tick']} "
                  + " ".join(f"{n}={d['loaded']}" for n, d in dims.items()))
        finally:
            proc.communicate("stop\n", timeout=60)
        left = sorted(p.name for p in out.iterdir())
        assert left == [], f"files left after stop: {left}"
        print("ok: files removed on stop")


if __name__ == "__main__":
    main()
