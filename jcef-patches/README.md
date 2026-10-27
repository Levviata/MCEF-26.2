# MCEF's changes to the native java-cef (`libjcef`)

Read this before touching the Chromium runtime. It is written for the next person, or AI assistant, who works on it.

## What this is

The mod does not build Chromium. It downloads two kinds of files at run time:

* **CEF** (the Chromium Embedded Framework binary distribution, CEF 116.0.27 = Chromium 116.0.5845.190), unmodified, inside the archive.
* **`libjcef`** (`jcef.dll` / `libjcef.so` / `libjcef.dylib`) and `jcef_helper`: the JNI wrapper from **CinemaMod/java-cef**, pinned in the
  main repository (`gradle.properties`: `jcef_commit`, currently `eaeb3d4370aa3526ee237ad1981ad59af3de4dd1`).

The upstream archives (`https://mcef-download.cinemamod.com/java-cef-builds/<commit>/<platform>.tar.gz`) hold an unmodified `libjcef`. This
folder holds **patches to the C++ of java-cef** and the tooling to build and publish a `libjcef` that has them. **The Java classes of java-cef
are not patched**: the Java side of every addition is `NativeExt.java` in this repository, so the mod compiles against pristine java-cef sources
and runs against either kind of archive.

## License

java-cef is BSD 3-clause (`java-cef/LICENSE.txt`, "Copyright (c) 2008-2013 Marshall A. Greenblatt. Portions Copyright (c) 2006-2009 Google
Inc."). Modified binaries may be redistributed if the copyright notice, conditions and disclaimer are kept (the CEF/java-cef license text is
inside the archive's source form and in `java-cef/LICENSE.txt`), and the names Google Inc. / Chromium Embedded Framework / contributors are not
used to endorse the product. These patches are BSD-3 like the code they modify. Everything else in this repository is LGPL 2.1 (`LICENSE.txt`).

## The patches (`*.patch`, applied in order with `git apply`)

| Patch | What it changes | Why |
|---|---|---|
| `0001-mcef-native-extensions.patch` | Adds `native/mcef_ext.cpp` (+ one line in `native/CMakeLists.txt`): JNI functions for `com.levviata.integratedntwiki.mcef.NativeExt`: `buildId0()` and `setWindowlessFrameRate0(browser, fps)` | Chromium's off-screen mode paints at most 30 fps unless `CefBrowserHost::SetWindowlessFrameRate` is called; upstream JCEF never calls it and has no way for Java to |
| `0002-offscreen-threaded-message-loop.patch` | `native/context.cpp`: if the Java system property `mcef.multiThreadedMessageLoop` is `true`, an off-screen browser uses CEF's own multi-threaded message loop instead of `external_message_pump` | Lets Chromium keep running while the game's frame loop is slow or blocked (see "Threaded loop") |
| `0003-optional-clang-format-download.patch` | Root `CMakeLists.txt`: a failed download of the clang-format style tool is a warning, not a fatal error | Build only. The download needs Google Storage and Python tooling that breaks on current Python; the tool is not part of the output |
| `0004-keep-jvm-attachment-per-thread.patch` | `native/jni_scoped_helpers.cpp`: a thread that was not started by Java attaches to the JVM once, named `Chromium`, and stays attached until it exits; it used to attach and detach around every callback | With the threaded loop every Chromium callback arrives on a Chromium thread, and each attach made the JVM create a new `java.lang.Thread`. Measured (Linux, 8 s of 60 fps painting): 1312 Java threads created before, 0 after |
| `0005-hide-offscreen-browser.patch` | `native/mcef_ext.cpp`: `NativeExt.setHidden0` calls `CefBrowserHost::WasHidden`; feature `hide-browser` | Chromium kept rendering every hidden OSR tab at the full frame rate, so each open tab cost CPU. Measured (Linux, 8 tabs): 1.39 cores before, 0.27 after, 0 paints/s from background tabs |

Unpatched behaviour is unchanged unless the new entry points are called or the property is set.

## Identity and compatibility at run time

`libjcef` built here reports `"<revision>+<commit7> | <features>"` (for example `mcef3+eaeb3d4 | frame-rate threaded-loop hide-browser`) through
`NativeExt.buildInfo()`; the mod logs it at start-up as `Chromium runtime: ...`. An upstream library has no such function: `buildInfo()` is
`null`, every `NativeExt` call reports "not supported", and the mod behaves exactly as before (Chromium at 30 fps, host-pumped loop). The Java
and native halves can therefore never be mismatched in a way that breaks: a feature is used only if the library lists it.

* Feature `frame-rate`: `NativeExt.setWindowlessFrameRate(browser, 1..60)`. CEF's maximum is 60. Applied per browser from `MCEFBrowser`.
* Feature `threaded-loop`: opt-in, Windows only (see below).

## Building (reproducible)

```
tools/build-natives.sh PLATFORM [OUT_DIR]      # PLATFORM = linux_amd64 | windows_amd64 | macos_amd64 | macos_arm64
tools/smoke-natives.sh OUT_DIR/linux_amd64.tar.gz   # Linux only: loads the library under Xvfb and measures
```

The script clones CinemaMod/java-cef, checks out the pinned commit, applies the patches, configures with
`-DMCEF_NATIVE_BUILD_ID=<revision>+<commit7>`, builds with CMake + Ninja (CMake downloads the CEF binary distribution itself from
`cef-builds.spotifycdn.com`), and packs `native/Release/` as `<platform>/...` exactly like the upstream archive (same layout; verified for Linux
by comparing file lists). It writes `<platform>.tar.gz`, `<platform>.tar.gz.sha256` and `<platform>.build.json` (build id, release tag, commits,
patches, checksum).

Toolchains: Linux needs `libgtk-3-dev libx11-dev libxt-dev ninja-build cmake>=3.19 python3`; Windows needs Visual Studio 2022 (MSVC x64) in the
shell (the workflow uses `ilammy/msvc-dev-cmd`); macOS needs Xcode command-line tools. Windows ARM64 and Linux ARM64 are not built (the upstream
archives for them contain x86-64 binaries; the mod refuses them, `MCEFPlatform#hasMatchingNatives`).

### Publishing

`.github/workflows/natives.yml` (this repository is public, so the runners are free) builds the four platforms. Run it from the Actions tab
with **publish** ticked: it creates the release `natives-<revision>-<commit7>` as a pre-release and uploads the archives, checksums and
`build.json` files. The mod looks for exactly that tag: `MCEFDownloader.mcefTag(commit)`.

**Changing a patch:** edit the patch, bump `jcef-patches/VERSION` (`1` -> `2`) **and** `MCEFDownloader.MCEF_NATIVES_REVISION` (`mcef1` ->
`mcef3`) together. A new tag means installed copies re-download once (the downloader compares the published checksum with the installed one).

## How the mod gets them

`nativesSource` in the config (`auto` default): the downloader tries the release above; the archive is verified against its published SHA-256 and
deleted if it does not match; if the release does not exist or GitHub is unreachable, `auto` falls back to the upstream archive and the log says
so (`mcef` refuses to fall back; `upstream` never tries). `integratedntwiki-cef/libraries/<platform>.natives-source` records what is installed.

## Threaded loop (opt-in, Windows only, experimental)

Upstream JCEF drives Chromium from the game's render thread (`external_message_pump`): Chromium advances only when the game calls
`CefDoMessageLoopWork`, so a stalled or throttled game loop stalls the browser. With `threadedChromiumLoop = true` (Windows, MCEF's build) CEF
runs its own UI thread. Measured with the headless harness on Linux (`jcef-patches/bench/OsrBench.java`; 60 fps requested, host loop at
250 Hz): a 1.5 s host stall every 1.5 s costs the external pump 50 percent of its frames (30 paints/s, 1516 ms worst gap) and costs the
threaded loop nothing (60 paints/s, 24 ms worst gap). The risk is in the callbacks: they now arrive on a Chromium thread. The mod's handlers
that touch the game's state are routed to the render thread (`MCEF.onRenderThread`), frames for the in-game window are handed over through
`MCEFRenderer.offerFrame/flush`, and the tab list is concurrent, but **this was never run on Windows or in the game**. Linux is excluded on
purpose (Xlib needs `XInitThreads` before the first X call, which the game's window library does not do) and macOS has no such mode.

## What was measured, and where

Linux x86-64, Xvfb, CEF 116, unmodified java-cef Java, host pumping at 250 Hz unless noted (`bench/OsrBench.java`):

| Case | Paints per second | Worst gap |
|---|---|---|
| upstream natives, no request | 30.0 | 40 ms |
| upstream natives, host pumps at 10 Hz | 10.1 | 102 ms |
| patched, requested 10 / 15 / 24 / 45 / 60 fps | 10.0 / 15.0 / 24.0 / 44.9 / 60.0 | - |
| external pump, 60 fps, 300 ms stall per second | 46.3 | 316 ms |
| threaded loop, same stalls | 60.0 | 27 ms |
| external pump, 60 fps, 1.5 s stall every 1.5 s | 30.4 | 1516 ms |
| threaded loop, same | 60.0 | 24 ms |
| external pump at 10 Hz (throttled game) | 10.3 | 102 ms |
| threaded loop at a 10 Hz host loop | 60.0 | 28 ms |
| upstream natives with the patched Java side (`NativeExt`) | 30.0, `buildInfo()` is null, requests report false, no crash | - |

Not measured: Windows and macOS builds (cannot be built where this was written), anything inside Minecraft, tearing, GPU upload.

## Frame copy cost and tearing (Phases 4 and 5 of the 2026-10-27 audit)

**Copy cost, reproduced.** The in-game probe measured the Minecraft shell's per-paint copy at p50 7.4 ms (2510x1312). `bench/CopyBench.java` runs the same
loop (read the BGRA ints, swap to ABGR, one bounds-checked off-heap write per pixel as `NativeImage.setPixelABGR` does) and gets **7.2 to 7.8 ms**
per full frame on this machine, so the cost is the per-pixel conversion loop itself, about 2.3 ns per pixel, not allocation or upload. Two variants
were tried: reading rows in bulk before the per-pixel write (**25 ms, slower**) and bulk read plus bulk write (**15 to 17 ms, also slower**; it would
also need a bulk write API of `NativeImage` that was not verified). The existing loop is already the fastest of the variants that use only known
API. What would remove the conversion is uploading CEF's BGRA bytes as they are (a BGRA texture format or a GPU swizzle), which depends on 26.x's
`GpuDevice`/`NativeImage` API and was not guessed at. What does reduce the cost today is fewer frames (`browserFpsMinecraft`, default 30) and a smaller
browser area. Dirty rectangles are already used, but scrolling dirties the whole view.

**Tearing, what the code says.** In the host-pumped loop `onPaint` runs inside `MCEF.pump()` on the render thread, converts into the
`NativeImage` and uploads, and the screen draws later on the same thread: a half-written frame can never be drawn, so there is no buffer race in
the Minecraft shell. (The desktop window did have one: the render thread wrote the `BufferedImage` while the AWT thread drew it; fixed with a lock.)
The tearing reported with VSync off is therefore most likely ordinary display tearing of the whole game picture (presentation without VSync), which
no browser code can change. This was **not** verified: it needs a run with VSync on and off on a real display, which the audit environment did not
have. With the opt-in threaded loop the hand-over is `MCEFRenderer.offerFrame/flush` under a lock, which keeps whole frames only.
