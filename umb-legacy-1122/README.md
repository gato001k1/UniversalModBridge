# umb-legacy-1122

This module runs Forge 1.12.2 in an isolated classloader and exposes the
version-neutral bridge API to `umb-hostagent`. It supplies the old world and
player facades, registry callbacks, tile-entity handles, input and rendering
capture, and the Forge bootstrap needed by 1.12.2 mods.

It is one of three era modules. The host agent selects it when a loaded mod is
identified as 1.12.2; the module does not own the 26.2 world or native
registries.

## Build and test

```powershell
umb-legacy-1122\build.ps1
umb-legacy-1122\run-tests.ps1
umb-legacy-1122\run-probe.ps1
```

The scripts expect the Forge and Minecraft libraries prepared by
`tools/ci-fetch.ps1`. `run-probe.ps1` boots the loader and exercises the
bridge with a small native-world scenario.

## Key classes

- `Legacy1122Loader` — isolated classloader and Forge classpath.
- `Legacy1122Lifecycle` — bootstrap and shutdown.
- `Legacy1122BridgeImpl` — implementation of the shared bridge API.
- `Legacy1122WorldFacadeRaw` and `Legacy1122PlayerFacadeRaw` — old-version facades.
- `Legacy1122RenderCapture` — client rendering capture.
- `TileHandle1122` — host access to a legacy tile entity.
