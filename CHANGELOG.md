# Changelog

## 1.0.6

### Fixed

- Opening the click GUI crashed with `IllegalStateException: Can only blur once per frame`: the
  screen already blurs the world when it opens and the GUI blurred it a second time.

### Changed

- The bundled Inter and Sora fonts are registered as Minecraft font resources (`assets/chicken-client/font`),
  so the fallback renderer draws real typography instead of the vanilla bitmap font.
- The fallback draws rounded panels, gradients, borders and circles by stacking fills, so the HUD
  and the click GUI keep their glass panels and orange accent on phones.

## 1.0.5

### Fixed

- `IllegalStateException: Scissor stack underflow` from `Renderer2D.end()` on the vanilla fallback:
  the frame end called `GuiGraphics.disableScissor()` whether or not a clip was open. Clip
  pushes and pops are now counted, `push()`/`pop()` keep the stack in step with the renderer
  state, and the frame end unwinds only the clips it opened. An already empty stack is logged
  once and skipped instead of throwing.

## 1.0.4

### Fixed

- The game no longer dies with a native `SIGSEGV` (in `libc` `strlen`) on the first in-world frame
  when NanoVG cannot run on the driver. NanoVG's desktop GL3 backend is not usable on OpenGL ES
  contexts and on the GL-to-GLES translators Android launchers use (ANGLE, MobileGlues, GL4ES,
  LTW, SwiftShader, VirGL, Zink), so the backend is now picked from the driver at the first frame:
  NanoVG on desktop, and the same drawing calls replayed through `GuiGraphics` on those stacks.
  The HUD, module list and click GUI keep working there, with flat colours instead of rounded
  corners, gradients and glow.
- Text is skipped for a font that failed to load instead of feeding `-1` into NanoVG, which
  dereferenced a null font.
- A failing HUD or click GUI frame is logged and the render frame is always closed, so one broken
  frame can no longer leave NanoVG mid-frame or take the game down.

### Notes

- `config/chicken-client/force-nanovg` tries NanoVG even on a translated stack.
- `config/chicken-client/disable-hud` still turns the interface off completely.
