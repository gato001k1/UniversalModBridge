# Third-party software

UniversalModBridge builds on the work of these projects. Nothing from Minecraft, Forge or any
mod is included in this repository; those are downloaded from official sources at install
time or supplied by the user.

## Used as dependencies

| Project | License |
|---|---|
| SpongePowered Mixin | MIT |
| mapping-io (FabricMC) | Apache-2.0 |
| tiny-remapper (FabricMC) | LGPL-3.0 (used as an unmodified dependency) |
| ASM | BSD-3-Clause |
| Gson | Apache-2.0 |
| JUnit 5 (tests only) | EPL-2.0 |

## Code or ideas adapted

| Project | License | Used for |
|---|---|---|
| Sinytra Connector, Adapter | MIT | jar remapping and mixin transformation approach |
| Forgified Fabric API | Apache-2.0 | reference for registry, event and networking translation |
| Forge (`fml/common/patcher`, `common/asm`) | OSI grant in Forge's LICENSE.txt | legacy class transformer techniques |

## Mapping data

Downloaded at build or install time, not stored in this repository:

- Mojang official mappings (Mojang's license: use for development, no redistribution)
- MCP/SRG 1.7.10 and 1.12.2 (MCPConfig, modified zlib)
- Yarn, Legacy-Yarn, Ornithe Calamus (CC0-1.0)

## Runtime downloads

Minecraft, LaunchWrapper, Forge 1.7.10 / 1.12.2 / 1.16.5 and their libraries are downloaded
from Mojang and Forge servers and checked against their published SHA-1 hashes.
