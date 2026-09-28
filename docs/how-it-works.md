# How it works

This is an overview for people who want to read or change the code.

## One process, several Minecrafts

Everything runs in the normal Minecraft 26.2 Java process. `umb-hostagent` is a Java agent
passed on the command line. When the game starts, it looks at the mods you installed and, for
each old version that has mods, boots that version's loader:

- 1.7.10: FML/Forge 10.13.4 through LaunchWrapper, in `umb-legacy`
- 1.12.2: Forge 14.23, in `umb-legacy-1122`
- 1.16.5: Forge 36, in `umb-legacy-1165`

Each one gets its own classloader, so old Minecraft classes never collide with 26.2's. Old
Forge expects a client and a server; it gets a headless server world plus a fake client
(`LegacyClientFacade` in 1.7.10) that has just enough of `Minecraft`, `EntityRenderer`,
`TextureManager`, `FontRenderer` and friends for mod client code to run.

Old versions boot lazily, the first time something from that version is needed, so a pack
with only 1.7.10 mods never starts 1.16.5.

## Twins

The mods create their own blocks, items, tile entities and entities inside the old world.
For each of those, the agent registers a 26.2 counterpart (a "twin"):

- blocks and items are registered in 26.2's registries at startup, with generated models,
  textures, names and creative tabs (`umb-hostagent/packgen` builds a resource pack from the
  mod jars)
- tile entities and entities are paired one to one with the live mod object and mirror its
  position, rotation, size, passengers and so on every tick
- containers become 26.2 menus whose slots read and write the mod's real inventory

The twins don't contain game logic. Interactions (right click, damage, riding, inventory
clicks) are forwarded to the mod object, and the mod's reaction is mirrored back.

The bridge between the two sides is a small Java 8 API (`dev.umb.bridge.api`) copied into
every era, so the same host code talks to all three versions.

## World access

When mod code asks the old world for a block, a light level or nearby entities,
`UmbWorld` answers from the real 26.2 world, translating block ids and metadata on the way.
Block changes the mod makes are written into the 26.2 world. There is no second copy of the
world.

## Rendering

Old mods draw with immediate-mode OpenGL (`glBegin`, `Tessellator`, display lists). That API
doesn't exist anymore, so `LegacyRenderCapture` rewrites those calls in mod bytecode to go to
a small GL emulation (`GlEmulationSession`). It tracks matrices, textures and GL state and
records the vertices instead of drawing them. `umb-objbridge` then turns the recorded mesh
into 26.2 render calls.

Static models are captured once and cached. Animated ones (rotors, doors, turrets) re-run
only the transform part each frame.

GUIs work the same way: the mod's own `GuiScreen` draws into the emulator, and the host
screen paints the result, while slots stay native 26.2 slots.

## Input, packets, sound

- Keyboard and mouse state from 26.2 is exposed to mods through the old LWJGL 2 `Keyboard`
  and `Mouse` classes.
- Packets a mod sends through `SimpleNetworkWrapper` never leave the process. They are
  serialised and delivered to the mod's server-side handler, like on a real server.
- Sounds, particles and explosions the mods trigger are mapped to 26.2 sound events and
  particles.

## Why no per-mod code

Every fix goes into the bridge's version of old Minecraft/Forge behaviour. That's more work
for the first mod and much less for the next hundred. The rule is enforced by a test that
scans runtime code for mod names.
