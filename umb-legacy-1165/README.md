# umb-legacy-1165

This module runs Forge 1.16.5 in an isolated classloader and implements the
version-neutral bridge used by `umb-hostagent`. It adapts the old world,
player, item, entity, tile-entity, lifecycle, and rendering APIs to the host
runtime.

The host agent selects this module for 1.16.5 content. The module owns the
legacy loader and facades; the host agent remains the owner of the live 26.2
world and native registrations.

## Build and test

```powershell
umb-legacy-1165\build.ps1
umb-legacy-1165\run-tests.ps1
umb-legacy-1165\run-probe.ps1
umb-legacy-1165\run-lifecycle.ps1
```

The scripts use the Forge and Minecraft libraries prepared by
`tools/ci-fetch.ps1`. The probe scripts exercise registry and bridge behavior
without opening a game window.

## Key classes

- `Legacy1165Loader` — isolated classloader and classpath setup.
- `Legacy1165Lifecycle` — Forge startup and shutdown.
- `Legacy1165BridgeImpl` — shared bridge implementation.
- `UmbWorld1165` and `UmbPlayer1165` — legacy world and player facades.
- `TileHandle1165` — host access to a legacy tile entity.
- `Legacy1165RenderCapture` — rendering capture for the 1.16.5 side.
