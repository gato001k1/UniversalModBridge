# Building UniversalModBridge

## Prerequisites

Install a JDK 25 (not a JRE), then confirm it:

```sh
java -version
```

Temurin 25 is available from [Adoptium](https://adoptium.net/temurin/releases/?version=25).
The build driver is pure Java and works from Linux, macOS, PowerShell, or Command Prompt.

## One-command commands

From the repository root:

```sh
java tools/UmbBuild.java fetch
java tools/UmbBuild.java build
java tools/UmbBuild.java test
java tools/UmbBuild.java release
```

`build`, `test`, and `release` fetch missing official inputs automatically. Downloaded
inputs are cached under `.ci-cache/`, `research/jars/`, `research/visual/`, and
`tools/junit/`. Build outputs are under `build/`; `release` writes the UMB-only bundle to
`dist/` and its installer to `build/installer/umb-installer.jar`.

On Linux/macOS use `./build.sh build` (or `./build.sh test`/`release`). On Windows use
`build.cmd build` (or `build.cmd test`/`release`). These wrappers require Java 25.

## Launching

The supported development launch path is currently Windows-oriented and uses the existing
PowerShell launch helpers under `tools/windows/`. For ordinary users, build a release and
follow [docs/INSTALL.md](docs/INSTALL.md); the installer prints the launcher JVM argument
line. Linux and macOS are supported for fetching and building, while cross-platform game
launch support remains limited by the legacy Forge runtime.

## Common problems

- `Unsupported class version`: the shell or launcher is using a JRE or Java older than 25.
- Missing client/Forge/library input: run `java tools/UmbBuild.java fetch` and allow HTTPS
  access to the official Maven and Mojang endpoints.
- A game or launcher has an agent jar open: close it before rebuilding.
- A release contains no legacy runtime: remove only the incomplete generated runtime and
  rerun `java tools/UmbBuild.java fetch`; it is reproducibly regenerated from fetched inputs.
- Paths containing spaces must be quoted in shell commands.
