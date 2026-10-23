# MCEF (26.2 port and Integrated NT Wiki library)
[MCEF](https://github.com/CinemaMod/mcef) is copyrighted by [CinemaMod](https://github.com/CinemaMod). 

This MCEF port isn't exactly what you expect, as it has been modified for use in my [Integrated Nuclear Tech Wiki mod](https://www.curseforge.com/minecraft/mc-mods/integrated-nuclear-tech-wiki), functionality is NOT the same.

# MCEF (modified) vs MCEF (original)

Only differences are listed. Anything not mentioned is identical apart from the package name.

Compared: original `mcef-1.21.4` (`common/src/main/java/com/cinemamod/mcef/`) ([that](https://github.com/CinemaMod/mcef)) against the modified `mcef/` folder (this).

## 1. Structure

| | Original | Modified |
|---|---|---|
| Packages | `mcef`, `mcef.internal`, `mcef.listeners`, `mcef.mixins`, `mcef.example` | everything in one package |
| Mixins | 4 (`CefDownloadMixin`, `CefInitMixin`, `CefRenderUpdateMixin`, `CefWindowsShutdownMixin`) + `mcef.mixins.json` | none |
| Loader modules | fabric + neoforge | not present |
| Removed classes | `MCEFSettings`, `MIMEUtil`, `ModScheme`, `ExampleScreen`, `MCEFDownloaderMenu`, `MCEFInitListener` | not present |
| Added classes | | `BrowserInput`, `ClickCounter`, `AutoscrollCursor`, `BrowserContextMenu`, `ClipboardBridge`, `PageQuery`, `ChromiumProofScreen` |
| Package-only changes | | `MCEFApp`, `MCEFClient`, `MCEFDragContext`, `MCEFPlatform`, `MCEFCursorChangeListener` |
| `MCEFDownloader` | imports `internal.MCEFDownloadListener` | same code, import dropped (same package) |
| `MCEFDownloadListener` | `@Unique` mixin annotation | annotation removed |

## 2. `MCEF`

| Area | Original | Modified |
|---|---|---|
| Startup trigger | mixins hook `ClientPackSource.<clinit>`, `Minecraft.setScreen`, `GameRenderer.render` | `prepare()` starts the download lazily; `initialize()` starts CEF once the download is done |
| Message loop | pumped by a render mixin | `pump()` calls `N_DoMessageLoopWork`, called once per frame by whoever shows a browser |
| State | none | `State { IDLE, DOWNLOADING, READY, FAILED }`, `state()` |
| Init callbacks | `scheduleForInit(MCEFInitListener)` | removed |
| Settings | `getSettings()` (`MCEFSettings`) | removed |
| Libraries path | set by the download mixin | `<gamedir>/integratedntwiki-cef/libraries`, set in `prepare()` (`mcef.libraries.path`, `jcef.path`) |
| Download | in `CefDownloadMixin` with a menu screen | own `MCEF-Download` thread, fixed mirror `https://mcef-download.cinemamod.com`, checksum compare, re-download on mismatch; if the host is unreachable and a runtime is already on disk it uses that |
| `initialize()` | always attempts init | returns false unless state is `READY`; installs the URL-policy handler and a context-menu handler before any browser exists |
| `mod://` scheme | `ModScheme` registered | removed |
| `createBrowser` | two overloads: `(url, transparent)` and `(url, transparent, w, h)` | only `(url, transparent, w, h)`; throws `IllegalArgumentException` when the URL is not allowed by the URL policy |
| Shutdown | mixin kills `jcef_helper.exe` on Windows | `shutdown()` is `synchronized` and does the kill itself (`tasklist` / `taskkill /F /IM jcef_helper.exe`) |
| Live browsers | not tracked | `liveBrowsers()`, `browserCreated()` / `browserClosed()` log "Chromium browser created/closed, live browsers: N" |
| `getJavaCefCommit()` | system property, then `MANIFEST.MF` (`java-cef-commit`), then `git submodule status` | system property `mcef.java.cef.commit`, then resource `/jcef-commit.txt`; `IOException` if blank or an unexpanded `$...` placeholder |
| Cursors | `glfwCreateStandardCursor(cursorType.glfwId)` for every type | the 9 panning cursor types (`MIDDLE_PANNING` + 8 directions) use custom GLFW cursors built from `AutoscrollCursor` (hotspot 16,16; falls back to the default arrow with a warning); other types with `glfwId == 0` return handle 0 instead of calling GLFW |
| Misc | | stray `// test` comment above the class javadoc |

## 3. `MCEFBrowser`

| Area | Original | Modified |
|---|---|---|
| `browserControls` default | `true` (hard-coded Ctrl+R, Alt+Arrows, Ctrl+wheel zoom) | `false` (`useBrowserControls()` still exists) |
| Renderer | `new MCEFRenderer(transparent)`, `renderer::initialize` submitted to the render thread, `finalize()` cleans up | `new MCEFRenderer()`, no initialize step, `finalize()` removed |
| `onPaint` | raw GL uploads (`GlStateManager`, `RenderSystem`, `GL11`), full upload on size change, sub-uploads per dirty rect, popup compositing | returns early for popups, empty dirty rects or a hidden browser; otherwise `renderer.onPaint(buffer, width, height, dirtyRects)` |
| Popup layer | `popupGraphics`, `popupSize`, `showPopup`, `popupDrawn`, `onPopupShow`, `onPopupSize` | removed; only the main view is drawn (select-box popups are not composited) |
| Window handle | `Minecraft.getInstance().getWindow().getWindow()` | `GLFW.glfwGetCurrentContext()` (also in the cursor code) |
| `sendMouseMove` | `(x, y)` | `(x, y, keyFlags)` |
| `sendMousePress` | `(x, y, button)`, click count always 1 | `(x, y, button, clickCount, keyFlags)` |
| `sendMouseRelease` | `(x, y, button)`, click count always 1 | `(x, y, button, clickCount, keyFlags)` |
| Modifier bits | none on mouse events | `keyFlags` are GLFW modifier bits, OR-ed into the button mask |
| `sendMouseWheel` | `(x, y, amount, modifiers)` | same signature; with `GLFW_MOD_SHIFT` the native code scrolls horizontally |
| Hidden browsers | n/a | `setHidden()` / `isHidden()`; un-hiding calls `invalidate()` for a full repaint |
| Mouse helpers | n/a | `sendMouseExit(x, y)`, `hasButtonsDown()` |
| Closing | `close()` | `isClosed()`; `close()` is idempotent and calls `MCEF.browserClosed()` |
| Cursor tracking | cursor applied only | remembers the last page cursor; `isAutoscrolling()` (true for the 9 panning cursors), `restoreCursor()` |

## 4. `MCEFRenderer`

| Area | Original | Modified |
|---|---|---|
| Backend | OpenGL texture: `glGenTextures`, `glTexImage2D`, `glTexSubImage2D` (BGRA) | `NativeImage` (RGBA) in a `DynamicTexture`, registered in the `TextureManager` as `integratedntwiki:dynamic/browser/<n>` |
| Constructor | `MCEFRenderer(boolean transparent)` | no arguments |
| API | `initialize()`, `getTextureID()`, `isTransparent()`, `onPaint(buffer, w, h)`, `onPaint(buffer, x, y, w, h)`, protected `cleanup()` | `getTexture()` (null before first frame), `getWidth()`, `getHeight()`, `onPaint(buffer, w, h, dirtyRects)`, public `cleanup()` (safe to repeat) |
| Pixel handling | uploaded as BGRA | copied per dirty rect, BGRA converted to ABGR; a size change recreates the texture and repaints fully |

## 5. `CefUtil`

| Area | Original | Modified |
|---|---|---|
| CEF switches | `--autoplay-policy=no-user-gesture-required`, `--disable-web-security`, `--enable-widevine-cdm` | all three removed; `--enable-features=MiddleClickAutoscroll` added |
| Cache | `<gamedir>/mods/mcef-cache`, only if `MCEFSettings.isUsingCache()` | always on, at the `cache` sibling of the libraries directory (`<gamedir>/integratedntwiki-cef/cache`) |
| User agent | `MCEFSettings` user agent, else product `MCEF/2` | product `IntegratedNTWiki/1` |
| macOS helpers | four hard-coded helper paths | loop over `jcef Helper`, `(GPU)`, `(Plugin)`, `(Renderer)` (same behaviour) |

## 6. New classes (no original counterpart)

| Class | What it does |
|---|---|
| `BrowserInput` | Routes keyboard and mouse to one `MCEFBrowser`. Keyboard owner is browser or Minecraft; a click in the view gives the browser the keyboard. Forwards mouse buttons 0-2 only; releases and drags always forwarded. Blurs Chromium when the game window loses focus. Text is sent as code points without modifiers (AltGr safe), except Shift on Space. Enter also sends `\r` as typed text. Horizontal wheel is sent as Shift+wheel. Modifiers are read live from GLFW. Autoscroll cancel: send Esc, re-send a move, and if still autoscrolling after 250 ms send a middle press and release. |
| `ClickCounter` | Click count 1..3 (500 ms, 4 px tolerance, same button; a fourth quick click wraps to 1; Minecraft's double-click flag is a minimum of 2). The count is repeated on the matching release. |
| `AutoscrollCursor` | 32x32 badge pixels for the 9 autoscroll directions (`pixels()` ARGB, `rgba()` bytes for GLFW). |
| `BrowserContextMenu` | Context-menu handler: stores link, selection, image, editable flag and edit flags in a one-slot request (`take()`), then clears the native menu so it is not shown. |
| `ClipboardBridge` | `read()` / `write()` on the OS clipboard through GLFW (1,000,000 character cap); `copy(browser, cut)` asks the focused frame for its selection (never from password fields; cut only deletes in editable fields) and writes it to the OS clipboard. If the page does not answer, Chromium's own `frame.copy()` / `frame.cut()` runs. |
| `PageQuery` | Runs JavaScript in a frame and reads the result over `console.log` using a random one-time number, accepted only from the browser that was asked; 400 ms timeout runs a fallback; matching messages are kept out of the log. |
| `ChromiumProofScreen` | The browser window screen. Calls `MCEF.prepare()`, shows download progress or failure, then `MCEF.initialize()`, `MCEF.pump()` and `PageQuery.tick()` each frame. Entry points `open(minecraft)` and `open(minecraft, parent, url, message)`. Draws its own tab bar, toolbar, URL bar, zoom menu, find bar and context menus around the page; zoom uses Chromium's `setZoomLevel`, find uses `find` / `stopFinding`. |

## 7. Known gaps in the modified version

- Select-box popups are not composited.
- No find-result counter, favicon callback, navigation-entry list or download handler (java-cef limits).
