# umb-rendermap

`umb-rendermap` statically analyzes a 1.7.10 Forge mod jar and records how its
blocks, items, tile entities, and entities are rendered. It finds renderer
bindings, OBJ or Java models, textures, groups, transforms, and dynamic
metadata variants without loading or executing the mod.

The output joins with the native snapshot from `fixtures/umb-snapshot-1710`
and is consumed by `umb-objbridge`. Generic Forge registration patterns are
handled first; optional structural resolvers cover renderer tables and other
indirection without naming a particular mod.

## Build and test

```powershell
tools\build-rendermap.ps1
tools\run-rendermap-tests.ps1
tools\run-rendermap.ps1 -Jar path\to\mod.jar -Snapshot path\to\snapshot.json -OutDir path\to\out
```

The command writes a namespace-specific JSON render map and a report. Add
`--no-bonus-indirection` to restrict analysis to the generic resolver set.

## Key classes

- `JarIndex` — reads class files and assets and answers hierarchy queries.
- `MethodSim` and `Val` — symbolic bytecode/data-flow analysis.
- `BindingScanner` — finds Forge and renderer bindings.
- `RendererAnalyzer` — resolves models, textures, transforms, and dynamic use.
- `DynamicVariantResolver` — joins damage-based renderer tables to variants.
- `Snapshot` and `RenderMap` — input snapshot and output schema.
- `ObjParser` — tolerant Wavefront OBJ reader.
