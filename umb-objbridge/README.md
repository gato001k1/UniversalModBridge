# umb-objbridge

`umb-objbridge` renders legacy OBJ models in Minecraft 26.2. It consumes the
render map produced by `umb-rendermap`, loads meshes and textures from the
legacy asset tree, and supplies native block, item, and entity model hooks.

The module is deliberately separate from the host agent so its model patchers
and mesh code can be tested independently. A normal launch can attach both
agents; the host agent handles gameplay and this module handles captured and
OBJ-based visuals.

## Build and test

```powershell
tools\build-objbridge.ps1
tools\run-objbridge-tests.ps1
tools\probe-objbridge.ps1
```

For a model pack, run `ObjPackGen` with a render map, snapshot, asset directory,
and output directory. The command-line options are shown by the generator and
in `tools\probe-objbridge.ps1`. A windowed development launch is available via
`umb-objbridge\dev\launch-obj.ps1`.

## Key classes

- `ObjBridge` — runtime setup and model hooks.
- `ObjMesh` and `MeshBaker` — OBJ parsing and native mesh creation.
- `TexturePick` — texture selection and safe resource paths.
- `RenderMap` — reader for `umb-rendermap` output.
- `ObjPackGen` — resource-pack generation.
- `ItemModelsPatcher` and `ModelManagerPatcher` — native model seams.
- `LegacyEntityCapture` and block/entity visual classes — captured legacy rendering.
