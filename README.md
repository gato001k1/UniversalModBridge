# UniversalModBridge

This is a WIP mod that enables the use of older mods in newer versions of minecraft.
Notice i did use AI on this project and the use of ai is permitted as long as it is controlled.

It loads unmodified 1.7.10, 1.12.2 and 1.16.5 Forge mods into Minecraft 26.2. It's not a port
of one mod, it's a loader: the old Forge runs headless inside the same game, the mods run on it
like they always did, and everything they do gets translated to 26.2. Blocks, items, entities,
GUIs, sounds, and the mod's own rendering code.

Very early alpha. Expect things to break.

The mods I test with are HBM's Nuclear Tech, MCHeli, Immersive Engineering, Torchmaster,
Iron Chests and Alex's Mobs. Right now MCHeli helicopters fly, steer and shoot with their real
models, a lot of HBM machines work, and the 1.12.2 / 1.16.5 side boots and registers content but
is a lot less finished than 1.7.10. No vehicle HUD yet, some animations are wrong, some GUIs
still look off, and there are culling bugs.

There's no mod-specific code in here, and that's on purpose. If a mod doesn't work, the fix
goes into the part that imitates old Minecraft/Forge, so every mod benefits. There's a test
that fails the build if a mod name shows up in the runtime.

## How it works

A Java agent starts with the game. For each old version you have mods for, it boots that
version's Forge in its own classloader with a fake server world and a fake client, and loads
your jars into it. Every block/item/entity the mods create gets a 26.2 twin that forwards to
the real mod object. For rendering, the old OpenGL calls get recorded instead of drawn and the
result is drawn with the new renderer, which is how the MCHeli models keep their exact look.

Longer explanation in [docs/how-it-works.md](docs/how-it-works.md).

## Installing

You need Java 25 (a JDK), Minecraft 26.2 in a launcher that lets you set JVM arguments, and
your own copies of the mods. Nothing from Minecraft, Forge or the mods is included here.

Run the installer, point it at your instance and your mods folder, and paste the line it
prints into your launcher's JVM arguments. Details for each launcher are in
[docs/INSTALL.md](docs/INSTALL.md).

## Building

```sh
pwsh tools/ci-fetch.ps1
pwsh tools/build-legacy.ps1
pwsh umb-legacy-1122/build.ps1
pwsh umb-legacy-1165/build.ps1
pwsh tools/build-objbridge.ps1
pwsh tools/build-hostagent.ps1
```

`ci-fetch.ps1` downloads Minecraft, Forge and the mappings into `research/` (ignored by git).
Tests are `tools/run-tests.ps1`, `tools/run-hostagent-tests.ps1`, `tools/run-objbridge-tests.ps1`
and the `run-tests.ps1` in the 1.12.2 and 1.16.5 folders.

## Contributing

If you know old Forge internals or modern MC rendering, help is really welcome. Read
[CONTRIBUTING.md](CONTRIBUTING.md) first, the short version is: fixes have to work for every
mod, not just one.

## License

MIT, see [LICENSE](LICENSE). Third-party stuff is listed in [THIRD_PARTY.md](THIRD_PARTY.md).
Not affiliated with Mojang, Microsoft, Forge or any of the mod authors.
