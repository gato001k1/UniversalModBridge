# Building UniversalModBridge

## Prerequisites

- JDK 25, including `java`, `javac`, and `jar` on `PATH`.
- PowerShell 7 (`pwsh`) for fetching the checked-in CI inputs on Windows, Linux, or macOS.
- Enough disk space for the official Minecraft, Forge, mapping, and test inputs downloaded by
  `tools/ci-fetch.ps1`. These inputs stay under `research/`, which is ignored by Git.

Install a JDK 25 from [Adoptium Temurin](https://adoptium.net/temurin/releases/?version=25),
Oracle Java, or another JDK vendor that provides Java 25 for your operating system. Verify it
with:

```sh
java -version
javac -version
```

Both commands must report version 25. The build driver uses the JDK that owns the `java` command.

## One-command build, test, and release

From the repository root:

```sh
./build.sh build
./build.sh test
./build.sh release
```

On Windows Command Prompt or PowerShell, use:

```bat
build.cmd build
build.cmd test
build.cmd release
```

The wrappers check for Java 25 and invoke the portable driver directly. The equivalent command is:

```sh
java tools/UmbBuild.java build
```

`test` builds first and then runs the available test gates. `release` builds first, stages the
distributable UMB files, and creates the release archive.

The build outputs are:

- `build/legacy/` — 1.7.10 UMB API, boot, bridge API, and legacy-side jars.
- `build/hostagent/umb-hostagent.jar` — the host Java agent.
- `build/objbridge/umb-objbridge.jar` — the rendering Java agent.
- `build/rendermap/umb-rendermap.jar` and `build/guimap/umb-guimap.jar` — build tools packaged
  as UMB jars.
- `umb-legacy-1122/build/` and `umb-legacy-1165/build/` — the era-specific UMB jars.
- `dist/umb-0.1.0-alpha.zip` — the release archive from `release`.

The build never packages Minecraft, Forge, mappings, or player mod jars as distributable UMB
artifacts. Those remain local inputs.

## Running and launching

Building is supported on Windows, Linux, and macOS. The repository’s supported end-user launch
path is the installer: use the release archive’s `umb-installer.jar` and follow
[docs/INSTALL.md](docs/INSTALL.md). That document includes launcher JVM-argument steps and
platform-specific instance paths for Windows, Linux, and macOS.

The repository does not provide a cross-platform game launcher command. Launch the configured
Minecraft instance from a launcher that accepts the generated JVM arguments. On macOS, keep the
installer-generated `-XstartOnFirstThread` argument; on Apple Silicon, use an arm64 JDK and
matching native libraries.

## Common problems

- **Java version rejected:** select JDK 25, then check both `java -version` and `javac -version`.
- **Missing build inputs:** run `pwsh -NoProfile -File tools/ci-fetch.ps1` from the repository root.
  Network access to the official endpoints is required. The 1.7.10 legacy runtime is a local,
  ignored input and is not downloaded by this repository.
- **PowerShell unavailable:** install PowerShell 7 and ensure `pwsh` is on `PATH`; the portable
  Java driver still handles compilation once the inputs exist.
- **Paths with spaces:** quote paths passed to shell commands. Do not copy third-party jars into
  the release staging directory.
- **Build files locked:** close Minecraft and any launcher process before rebuilding. The build
  driver does not terminate running applications.
- **macOS window does not open:** retain `-XstartOnFirstThread` and use a JDK/native-library
  architecture matching the machine.
- **Release scan failure:** inspect the reported archive entry. Only UMB-owned classes and jars
  belong in the release; Minecraft, Forge, mappings, and mod jars must remain outside it.
