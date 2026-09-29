# Build tools

The portable entry point is `java tools/UmbBuild.java` (or the repository-root
`build.sh`/`build.cmd`). It fetches and verifies official inputs without PowerShell.

The `windows/` directory contains developer, probe, and game-launch helpers that require
PowerShell and the Windows-oriented local test environment. Their paths are intentionally
separate from the cross-platform build driver. These helpers are optional for building a
release.

Do not place Minecraft, Forge, or player mod binaries in the repository or release bundle.
