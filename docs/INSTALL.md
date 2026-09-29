# UMB installation — one command

Download the release zip, install Java 25, and keep your own legacy mod jars in a separate folder. UMB downloads and verifies the official runtime inputs, then generates snapshots, assets, render maps, packs, and manifests locally. No repository checkout is needed.

## Five steps

1. Install Java 25 from [Adoptium](https://adoptium.net/temurin/releases/?version=25).
2. Check it with `java -version`.
3. Download the [latest release](https://github.com/gato001k1/UniversalModBridge/releases/latest).
4. Put the original mod jars you own in a separate `mods` folder. Tested layouts include
   HBM/MC Heli for 1.7.10 and IronChest for 1.16.5; keep Minecraft, Forge, and UMB jars out.
5. Run the installer below, then paste its printed JVM line into your launcher.

## Requirements

Install a JDK, not only a JRE. Minecraft 26.2 requires Java 25. Check with `java -version` and use that same JDK for the launcher.

## Install

1. Make a folder containing the original legacy mod jars you own. Do not put UMB jars in it.
2. Close the target launcher/game instance.
3. From the unpacked release folder, run this command with your paths:

Windows PowerShell:

```powershell
java -jar umb-installer.jar --minecraft "C:\Games\Minecraft\instances\UMB" --mods "C:\Games\UMB\player-mods"
```

Windows Git Bash:

```sh
java -jar umb-installer.jar --minecraft "C:/Games/Minecraft/instances/UMB" --mods "C:/Games/UMB/player-mods"
```

Linux:

```sh
java -jar umb-installer.jar --minecraft "$HOME/.minecraft/instances/UMB" --mods "$HOME/Games/UMB/player-mods"
```

macOS:

```sh
java -jar umb-installer.jar --minecraft "$HOME/Library/Application Support/minecraft/instances/UMB" --mods "$HOME/Games/UMB/player-mods"
```

The installer writes `umb/jvm-arguments.txt`; paste its single line into your launcher. That line includes `-Dumb.home` so the installed bridge resolves every legacy input from this `umb/` directory, not from a repository checkout. It also writes all generated data below `instance/umb/`. Nothing generated from your mods is included in the release. Do not use `--repo` or `--inputs` as an end user; those are developer/QA overrides.

LaunchWrapper, Minecraft, Forge, libraries, and MCP/SRG mappings are downloaded or reused from the player's disk and SHA-1 verified. The installer caches them under `umb/inputs/`.

To verify or repair an existing install without regenerating it:

```sh
java -jar umb-installer.jar --minecraft "$HOME/.minecraft/instances/UMB" --check
```

If the official client or Forge file is already on disk, the optional `--client PATH` and
`--forge PATH` overrides avoid downloading them.

## Add the JVM arguments

- Official Minecraft Launcher: **Installations >** select the profile **> More options > JVM arguments**. Replace or append the line from `umb/jvm-arguments.txt`.
- Prism Launcher / MultiMC: **Instance > Settings > Java > JVM arguments**. Paste the generated line.
- CurseForge app: open the profile, use **... > Profile Options**, enable the Java/JVM argument override (or the profile's **Additional Arguments** field), and paste the line. Keep the profile's existing memory flags if the UI separates them.
- ATLauncher: open the instance **Edit > Java/Minecraft > JVM Arguments** and paste the line.
- Modrinth App: open the instance **Settings → Java** and paste the line into **Java arguments/JVM arguments**.

Use the Java 25 executable selected by the launcher. The generated arguments use the correct path separators for the operating system; do not convert them by hand. On macOS the installer also writes `-XstartOnFirstThread`, as required by the windowing stack.

On macOS, the normal game directory is `~/Library/Application Support/minecraft` (instance launchers may put instances below that directory). On Apple Silicon, install an arm64/aarch64 Java 25 JDK and use arm64 LWJGL natives. If macOS blocks the downloaded installer, run `xattr -d com.apple.quarantine /path/to/umb-installer.jar` once and retry.

## Troubleshooting

- **Unsupported class version / wrong Java:** select JDK 25 in the launcher and confirm with `java -version` in the same shell used for installation.
- **Missing client or Forge:** allow the installer network access to the official Mojang/Forge Maven endpoints, or rerun with `--client` and `--forge` paths.
- **Missing LaunchWrapper:** allow access to `libraries.minecraft.net`, or pass `--launchwrapper` with the player's `launchwrapper-1.12.jar`; the installer verifies its SHA-1 before use.
- **No extracted snapshot/assets:** rerun the one-command installer; it will rebuild `umb/extraction/` and `umb/generated/` from the supplied mod jars. Only use `--inputs` when reproducing a developer/QA cache.
- **Download/checksum failure:** allow HTTPS access to `piston-meta.mojang.com`, `piston-data.mojang.com`, `launcher.mojang.com`, `libraries.minecraft.net`, `maven.minecraftforge.net`, `repo1.maven.org`, and `mcp.zeith.org`. Delete only the affected file under `instance/umb/inputs/` and rerun; never copy Minecraft or Forge binaries into the release zip.
- **MCP mapping unavailable:** the stable MCP export is the one non-Mojang/non-Forge runtime input. The least-effort fallback is to place a verified `joined-1.7.10.srg` at `instance/umb/inputs/joined-1.7.10.srg` and rerun; no repository checkout is required.
- **Path or quoting errors:** quote paths containing spaces. Use `/` in Git Bash, `\` or `/` in PowerShell, and never add `.exe` to a Java-agent path.
- **Mod not found:** give `--mods` a folder containing the original mod jars. Keep those jars outside `umb/`; they remain the player's files.
- **Resource-pack warning:** leave the generated `resourcepacks/umb-*` directories and the `resourcePacks` line in `options.txt` together. If a launcher overwrites options, rerun the installer.
- **Agent startup failure:** use the complete line from `umb/jvm-arguments.txt`, keep the `--add-opens` entries, and ensure the launcher is using Java 25.
- **macOS window does not open:** keep `-XstartOnFirstThread`, use an arm64 Java 25 JDK on Apple Silicon, and ensure the launcher is not forcing an x86_64 Java under Rosetta.
- **Build/install while a game is open:** close the game and launcher profile first. UMB tooling does not terminate processes or send input to a running game.

For a diagnostic bundle, report the installer command, Java version, operating system, and the first error from the installer log. Do not upload Minecraft, Forge, or mod jars.
