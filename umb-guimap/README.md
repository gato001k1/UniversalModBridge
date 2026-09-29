# umb-guimap

`umb-guimap` analyzes a legacy Forge mod jar and produces the GUI map used by
the host agent. It identifies GUI handlers, container classes, textures,
buttons, labels, slots, and synchronization code without launching Minecraft.

The host agent uses the map to connect a legacy `GuiScreen` and container to a
26.2 screen. `umb-guimap` is an offline input stage, alongside
`umb-rendermap`; it does not render a GUI itself.

## Build and test

```powershell
tools\windows\build-guimap.ps1
tools\windows\run-guimap-tests.ps1
tools\windows\run-guimap.ps1 -Jar path\to\mod.jar -Snapshot path\to\snapshot.json
```

The analyzer writes a namespace-specific GUI map and a report to the selected
output directory. The default paths are documented by the scripts.

## Key classes

- `GuiClassAnalyzer` — reads GUI and container bytecode.
- `GuiHandlerScanner` — finds Forge GUI registrations.
- `ContainerPairer` — joins client screens with server containers.
- `ContainerSyncScanner` — records fields and packets used for synchronization.
- `GuiMap` — output model and JSON schema.
- `MethodSim` and `ExprEval` — small bytecode/data-flow evaluators.
