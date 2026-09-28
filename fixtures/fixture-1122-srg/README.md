# 1.12.2 SRG fixture

This directory contains a small, self-authored Forge 1.12.2 mod used to test the
loader and namespace analysis. It has an FML entry point, mod metadata, registry
references, and representative SRG names without depending on a real mod jar.

The fixture is compiled against hand-written stubs. The stubs are compile-time
inputs only; the output jar contains the fixture classes and `mcmod.info`.

## Build and inspect

```powershell
fixtures\fixture-1122-srg\build.ps1
tools\umb.cmd analyze fixtures\fixture-1122-srg\build\fixture-1122-srg-0.1.0.jar
```

To remap or smoke-test the jar, use the standard CLI commands described in the
top-level README. The build script prints the jar contents so accidental stub
inclusion is visible.

## Key files

- `src/main/java/net/umb/Fixture1122.java` — the fixture mod entry point.
- `stubs/` — the minimal 1.12.2 API surface needed by the fixture.
- `build.ps1` — compilation and jar assembly.

The fixture is used by `umb-core` and the CLI tests; it is not part of the
runtime mod loader.
