# umb-legacy

This is the 1.7.10 side of UniversalModBridge. It boots FML/Forge in an
isolated classloader, loads unmodified 1.7.10 mods, and implements the shared
bridge API used by `umb-hostagent`.

The module supplies the old world and player facades, item and NBT conversion,
tile-entity handles, Forge lifecycle, input and network translation, and
legacy OpenGL capture. The host agent remains the owner of the live 26.2 world;
calls made by legacy code are translated across the bridge rather than stored
in a second world.

## Build and test

```powershell
tools\build-legacy.ps1
tools\run-tests.ps1
```

The build expects the Forge and Minecraft libraries prepared by
`tools/ci-fetch.ps1`. The tests include facade tests and the headless M1 probe.

## Key classes

- `LegacyLoader` — LaunchWrapper classloader and legacy classpath.
- `LegacyDriver` and `LegacyLifecycle` — Forge startup and shutdown.
- `LegacyBridgeImpl` — implementation of the shared bridge API.
- `UmbWorld` and `UmbPlayer` — world and player facades.
- `LegacyClientFacade` — the small client surface needed by mod code.
- `LegacyRenderCapture` and `GlEmulationSession` — immediate-mode rendering capture.
- `TileHandle` and `UmbItemConv` — tile and item state crossing the bridge.
