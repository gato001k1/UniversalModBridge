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

You don't need to build anything. You need:

- Minecraft Java Edition 26.2, in a launcher that lets you set JVM arguments (the official
  launcher, Prism/MultiMC, Modrinth App...).
- Java 25 (a JDK). Check with `java -version`; it has to say 25.
- Your own copies of the old mods, unchanged, in one folder. Nothing from Minecraft, Forge or
  the mods is included here.

Steps:

1. Download the zip from the [latest release](https://github.com/gato001k1/UniversalModBridge/releases/latest)
   (or the `nightly` pre-release for the newest build) and unzip it.
2. Put your old mod jars (1.7.10, 1.12.2 or 1.16.5 Forge mods) in a folder of their own, for
   example `~/Games/UMB/mods` on Linux/macOS or `C:\Games\UMB\mods` on Windows. Don't put the
   UMB jars or Forge in there.
3. Run the installer from the unzipped folder, pointing it at your 26.2 instance and that mods
   folder:

   ```sh
   java -jar umb-installer.jar --minecraft "PATH_TO_YOUR_26.2_INSTANCE" --mods "PATH_TO_YOUR_MOD_FOLDER"
   ```

   The first run downloads and checks the official Minecraft/Forge files it needs, so it takes
   a while.
4. Copy the JVM arguments line it prints (also saved in `<instance>/umb/jvm-arguments.txt`)
   into your launcher's JVM arguments for that instance.
5. Start the game.

`java -jar umb-installer.jar --minecraft "PATH_TO_YOUR_26.2_INSTANCE" --check` checks an
existing install and tells you what to fix. Where each launcher keeps its JVM arguments, and
common errors, are in [docs/INSTALL.md](docs/INSTALL.md).

## Building

See [BUILDING.md](BUILDING.md). Short version, on Linux, macOS or Windows with JDK 25:

```sh
./build.sh fetch     # first time: downloads Minecraft, Forge and mappings (build.cmd on Windows)
./build.sh build
./build.sh release   # dist/umb-<version>.zip with the installer
```

The build downloads what it's missing by itself. The PowerShell scripts in `tools/windows/`
are optional Windows developer helpers; you don't need them to build or install.

## Contributing

If you know old Forge internals or modern MC rendering, help is really welcome. Read
[CONTRIBUTING.md](CONTRIBUTING.md) first, the short version is: fixes have to work for every
mod, not just one.

## License

MIT, see [LICENSE](LICENSE). Third-party stuff is listed in [THIRD_PARTY.md](THIRD_PARTY.md).
Not affiliated with Mojang, Microsoft, Forge or any of the mod authors.
