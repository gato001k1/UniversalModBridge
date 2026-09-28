# 1.7.10 snapshot fixture

This is a small Forge 1.7.10 mod that runs beside a legacy mod and records the
registries and client data visible during a native 1.7.10 run. The snapshot
includes blocks, items, entities, tile entities, creative tabs, and texture
information that cannot be recovered reliably from bytecode alone.

The resulting snapshot is consumed by the host agent and by the offline
rendering and GUI tools. The fixture jar contains only its own classes and
`mcmod.info`; Minecraft and Forge are supplied by the local build environment.

## Build and run

```powershell
fixtures\umb-snapshot-1710\build.ps1
fixtures\umb-snapshot-1710\run-native-1710.ps1
```

The run command expects the 1.7.10 Forge libraries and a target mod in the
paths configured by the script. It writes the snapshot under the configured
output directory. Use the generated snapshot with `umb-rendermap` or
`umb-hostagent` as described in the top-level documentation.

## Key files

- `src/net/umb/snapshot/UmbSnapshotMod.java` — Forge entry point.
- `src/net/umb/snapshot/Snapshot.java` — registry snapshot model and writer.
- `src/net/umb/snapshot/IconDump.java` — texture-atlas extraction.
- `src/net/umb/snapshot/Refl.java` — access to runtime-only fields.
- `build.ps1` — compilation and packaging.
