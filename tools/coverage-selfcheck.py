#!/usr/bin/env python3
"""Read-only three-row live cross-check for coverage-1710.csv."""
import csv
import json
import pathlib
import socket

ROOT = pathlib.Path(__file__).resolve().parents[1]
CSV = ROOT / "research" / "out" / "legacy" / "swarm" / "coverage-1710.csv"
INFO = {
    "A": ROOT / "research" / "out" / "legacy" / "win-twomods" / "logs" / "automation.json",
    "B": ROOT / "research" / "out" / "legacy" / "win-twomods2" / "logs" / "automation.json",
}


def call(command, row):
    info = json.loads(INFO[row["instance"]].read_text(encoding="utf-8"))
    request = {
        "id": 1,
        "token": info["token"],
        "command": command,
        "pos": {"x": int(row["pos_x"]), "y": int(row["pos_y"]), "z": int(row["pos_z"])},
    }
    with socket.create_connection(("127.0.0.1", int(info["port"])), timeout=30) as sock:
        sock.settimeout(30)
        sock.sendall((json.dumps(request) + "\n").encode("utf-8"))
        return json.loads(sock.makefile("rb").readline()).get("result")


with CSV.open(newline="", encoding="utf-8") as handle:
    rows = [r for r in csv.DictReader(handle) if r["mod"] == "hbm" and r["kind"] == "block" and r["registered"] == "true"][:3]
for row in rows:
    print(json.dumps({
        "id": row["id"],
        "pos": [row["pos_x"], row["pos_y"], row["pos_z"]],
        "block_info": call("block_info", row),
        "client_block_entity": call("client_block_entity", row),
        "legacy_tile": call("legacy_tile", row),
    }, ensure_ascii=False))
