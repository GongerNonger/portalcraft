# Third-party notices

## SkyCraft (MIT)

PortalCraft's host-game bridge follows patterns from [chasmlol/SkyCraft](https://github.com/chasmlol/SkyCraft):
- injecting host geometry through a `BlockCollisions` mixin;
- replaying the host's keyboard as Minecraft input;
- forcing window focus and full frame rate while linked;
- the teleport sequence/acknowledge handshake;
- exporting placed blocks to the host: the CPU copy of the block atlas (`WorldAtlas`, from
  `SkyAtlas`), the `LevelExtractor` dirty-section mixin and the `BlockQuadOutput` mesher
  (`WorldExporter`).

> Copyright (c) 2026 chasmlol
>
> Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
> associated documentation files (the "Software"), to deal in the Software without restriction,
> including without limitation the rights to use, copy, modify, merge, publish, distribute,
> sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
> furnished to do so, subject to the following conditions:
>
> The above copyright notice and this permission notice shall be included in all copies or
> substantial portions of the Software.
>
> THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT
> NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
> NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
> DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT
> OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

## Source engine interfaces

`host/portal/src/sdk.h` declares the few Source engine interface slots and structure offsets that
the plugin calls, written by hand to interoperate with Steam Portal. They follow the published
Source SDK 2013 (single-player branch). No Valve source files are included in this repository.
`host/portal/ref/` (git-ignored) is where a local copy of those headers can live for reference.

Portal, Half-Life and Source are trademarks of Valve Corporation. Minecraft is a trademark of
Mojang/Microsoft. This is a fan project, not affiliated with either. You need to own both games.

## Bundled in releases (tools/package.ps1)

- [Prism Launcher](https://prismlauncher.org/) 11.1.1, the official portable Windows build,
  unmodified, under the GPL-3.0 (its license is in `minecraft/Prism/LICENSE-PrismLauncher.txt`;
  source: https://github.com/PrismLauncher/PrismLauncher/tree/11.1.1). PortalCraft starts it as a
  separate program.
- [Fabric API](https://github.com/FabricMC/fabric) 0.161.0+26.3, unmodified, under the Apache-2.0.
- The Prism instance setup (`tools/minecraft-bundle`) follows SkyCraft's (MIT, above).

Minecraft itself is not bundled: Prism downloads it from Mojang for a player who owns it.
