# Contributing

Thanks for wanting to help.

The main thing: no per-mod code. If a mod breaks, figure out which old Minecraft or Forge
behaviour it relies on that we don't imitate yet, and fix that. A test fails the build if a mod
id or class name ends up in runtime code. Tests and fixtures can mention mods, that's fine.

Before opening a PR, build and run the tests for whatever you touched, and keep the PR to one
thing. If your fix depends on how old Minecraft or Forge behaves, mention where you got that
from.

Please don't commit Minecraft, Forge or mod jars, game folders, logs, or screenshots of
copyrighted stuff.

For bug reports, include the mods and versions, your OS and Java version, what you did, and the
first error from `logs/latest.log` and the UMB logs next to it. Don't upload mod jars.

Stuff that needs help the most right now: vehicle HUDs, GUI rendering, 1.12.2 and 1.16.5
rendering, performance on weaker PCs, and testing on Linux and macOS.
