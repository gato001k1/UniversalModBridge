# 1.7.10 SRG fixture

This directory contains a small, self-authored Forge 1.7.10 mod used to test
legacy namespace analysis. It includes an FML entry point, mod metadata,
registry references, and representative SRG names without requiring a real mod
jar.

The fixture is compiled against hand-written stubs. The stubs are compile-time
inputs only; the output jar contains the fixture classes and `mcmod.info`.

## Build and inspect

```powershell
fixtures\fixture-1710-srg\build.ps1
tools\umb.cmd analyze fixtures\fixture-1710-srg\build\fixture-1710-srg-0.1.0.jar
```

Remapping and smoke testing use the standard CLI commands in the top-level
README. The build script prints the jar contents so accidental stub inclusion
is easy to spot.

## Key files

- `src/main/java/net/umb/Fixture1710.java` — the fixture mod entry point.
- `stubs/` — the minimal 1.7.10 API surface needed by the fixture.
- `build.ps1` — compilation and jar assembly.

The fixture is used by `umb-core` and the CLI tests; it is not loaded by the
runtime bridge as a production mod.
