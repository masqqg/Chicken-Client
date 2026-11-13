# Chicken Client

Standalone Fabric client for **Minecraft 1.21.11**. It is a complete client on its own:
own module system, own settings, own event bus, own ESP renderer, own NanoVG interface.
No Meteor, no addon API, no external client dependency.

## Features

- **Right Shift GUI** — glass panels, dark orange theme, spring animations, animated backdrop,
  searchable module list, per-module settings (booleans, sliders, enums, colour swatches).
- **HUD** — cloud-logo watermark with live FPS and latency, animated module list.
- **Modules** — the Chicken addon modules, ported onto this client's own API:
  - `cluster-bypass` — amethyst geode finder from block-light data, radar map, bud ESP
  - `base-finder` — container/base detection per chunk
  - `sus-chunk-finder` — suspicious chunk detection
  - `spawner-flag` — persistent spawner flags with natural-structure filtering
  - `storage-esp-bypass` — multi-channel container ESP
  - `clear-render` — fills small sealed air pockets client side
  - `player-finder` — player ESP with tracers
- **Karambit / Red Wings (Visuals)** — Blockbench geometry + animation model rendered in first
  person, with glowing red wing trails.

## Stack

| Part | Used for |
| --- | --- |
| Fabric Loader + Fabric API | mod loading, ticks, HUD and world render hooks |
| **NanoVG** (bundled) | every pixel of the GUI and HUD, anti-aliased vectors and real fonts |
| **GeckoLib** | animation curve evaluation for the Blockbench karambit model |
| **Blockbench** | `.geo.json` model and `.animation.json` clips in `assets/chicken-client` |

NanoVG and its natives are shaded into the jar (JiJ), so no extra download is needed.
GeckoLib is optional: when it is installed the karambit curves come from GeckoLib, otherwise the client uses its built-in easing.

## Build

```bash
./gradlew build
```

The jar lands in `build/libs/chicken-client-1.0.6.jar`.

## Usage

1. Install Fabric Loader 0.19.5+ for Minecraft 1.21.11.
2. Drop the Chicken Client jar into `.minecraft/mods` (GeckoLib is optional).
3. Launch, press **Right Shift**, toggle modules.

Config is stored in `config/chicken-client/config.json`.

### Renderer

The interface is drawn with NanoVG on desktop drivers. On OpenGL ES contexts and GL-to-GLES
translators (ANGLE, MobileGlues, GL4ES, LTW, Zink — the stacks Android launchers use) NanoVG
crashes inside its own native code, so there the same drawing calls are replayed through the
vanilla `GuiGraphics` renderer instead. Files in `config/chicken-client` control it:

| File | Effect |
| --- | --- |
| `disable-hud` | Draw no HUD and no GUI at all |
| `force-nanovg` | Use NanoVG even on a translated stack |

## Layout

```
src/main/java/dev/chicken
├── ChickenClient.java      entrypoint, hooks, keybind
├── events/                 event bus and event types
├── settings/               setting types and groups
├── systems/                categories, modules, config
├── modules/                every module including Karambit
├── render/                 NanoVG renderer, world ESP renderer, colours
├── karambit/               Blockbench geometry/animation runtime
├── gui/                    theme, click GUI, icons, logo, animation
├── hud/                    watermark and module list
└── mixin/                  packet, chunk and block update hooks
```

## Notes

- Client side only (`"environment": "client"`), nothing is sent to servers by the client itself.
- Fonts: Inter and Sora (SIL Open Font License, see `assets/chicken-client/fonts/OFL.txt`).
