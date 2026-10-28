/*
 *     MCEF (Minecraft Chromium Embedded Framework)
 *     Copyright (C) 2023 CinemaMod Group
 *
 *     This library is free software; you can redistribute it and/or
 *     modify it under the terms of the GNU Lesser General Public
 *     License as published by the Free Software Foundation; either
 *     version 2.1 of the License, or (at your option) any later version.
 *
 *     This library is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *     Lesser General Public License for more details.
 *
 *     You should have received a copy of the GNU Lesser General Public
 *     License along with this library; if not, write to the Free Software
 *     Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301
 *     USA
 */

package com.levviata.integratedntwiki.mcef;

import com.levviata.integratedntwiki.WikiLog;

import com.levviata.integratedntwiki.browser.WikiBrowserSecurity;
import com.levviata.integratedntwiki.browser.WikiUrls;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.loading.FMLPaths;
import org.cef.misc.CefCursorType;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWImage;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;

// test

/**
 * Minecraft 26.2 / NeoForge port of MCEF's entry point (a modified java-cef inside Minecraft).
 * <p>
 * Differences from MCEF 2.1.6: there are no mixins. Instead of hooking {@code ClientPackSource.<clinit>},
 * {@code Minecraft.setScreen} and {@code GameRenderer.render}, the native download is started lazily by
 * {@link #prepare()}, CEF is initialised by {@link #initialize()} once the download is done, and the CEF
 * message loop is pumped by whoever shows a browser, through {@link #pump()}.
 */
public final class MCEF {
    public static final Logger LOGGER = LoggerFactory.getLogger("MCEF");
    private static final String DOWNLOAD_MIRROR = "https://mcef-download.cinemamod.com";

    public enum State { IDLE, DOWNLOADING, READY, FAILED }

    private static volatile State state = State.IDLE;
    private static MCEFApp app;
    private static MCEFClient client;
    private static final AtomicInteger LIVE_BROWSERS = new AtomicInteger();
    private static final HashMap<CefCursorType, Long> CEF_TO_GLFW_CURSORS = new HashMap<>();

    private MCEF() {
    }

    public static Logger getLogger() {
        return LOGGER;
    }

    public static State state() {
        return state;
    }

    /** Browsers created and not yet closed (used to prove nothing accumulates). */
    public static int liveBrowsers() {
        return LIVE_BROWSERS.get();
    }

    static void browserCreated() {
        WikiLog.info("Chromium browser created, live browsers: {}", LIVE_BROWSERS.incrementAndGet());
    }

    static void browserClosed() {
        WikiLog.info("Chromium browser closed, live browsers: {}", LIVE_BROWSERS.decrementAndGet());
    }

    /** Starts (once) the background download of the java-cef natives. Safe from any thread. */
    public static synchronized void prepare() {
        if (state != State.IDLE) return;
        state = State.DOWNLOADING;
        try {
            if (!MCEFPlatform.getPlatform().hasMatchingNatives()) {
                // A 128 MB download that can never load is worse than saying so: see MCEFPlatform#hasMatchingNatives
                LOGGER.error("No Chromium runtime is available for {}: the published build for this platform contains x86-64 binaries. "
                        + "The wiki browser is disabled here. (-Dintegratedntwiki.allowUnverifiedNatives=true tries the download anyway.)",
                        MCEFPlatform.getPlatform().getNormalizedName());
                fail();
                return;
            }
            File libraries = FMLPaths.GAMEDIR.get().resolve("integratedntwiki-cef").resolve("libraries").toFile();
            libraries.mkdirs();
            System.setProperty("mcef.libraries.path", libraries.getCanonicalPath());
            System.setProperty("jcef.path", new File(libraries, MCEFPlatform.getPlatform().getNormalizedName()).getCanonicalPath());
        } catch (IOException | RuntimeException e) {
            LOGGER.error("Could not set up the CEF library path", e);
            fail();
            return;
        }
        new Thread(MCEF::download, "MCEF-Download").start();
    }

    /**
     * Gets the native runtime ready in the background before anyone asks for the browser, so the wait at the first H / F9 is only
     * Chromium's own start-up. Only when the runtime is already installed: a first-ever download still waits for the player to ask
     * for the browser. The work is the same {@link #prepare()} (checksum check, no extraction when nothing changed), just earlier.
     */
    public static void prewarm() {
        if (state != State.IDLE) return;
        try {
            File platformDir = new File(FMLPaths.GAMEDIR.get().resolve("integratedntwiki-cef").resolve("libraries").toFile(),
                    MCEFPlatform.getPlatform().getNormalizedName());
            if (runtimePresent(platformDir)) { warm = true; prepare(); }
        } catch (RuntimeException e) {
            WikiLog.warn("Could not check for an installed Chromium runtime: {}", e.toString());
        }
    }

    private static volatile boolean warm;

    /**
     * Called every frame by the loader glue. Once the prewarm has made the runtime ready, starts Chromium itself (the render thread's
     * ~4 second job) while the game is still on its title screen, instead of at the first key press: that start froze the game for
     * seconds with nothing on screen, long enough for a second press, which closed the window that had just opened.
     */
    public static void warmTick() {
        if (!warm || state != State.READY) return;
        warm = false;
        try {
            initialize();
        } catch (RuntimeException | LinkageError e) {
            WikiLog.warn("Chromium could not be started ahead of time (it will start when the browser is opened): {}", e.toString());
        }
    }

    private static void fail() {
        MCEFDownloadListener.INSTANCE.setFailed(true);
        state = State.FAILED;
    }

    /** True when the Chromium runtime's native library is under {@code dir} (jcef / libcef: .dll, .so, .dylib or the macOS framework). */
    static boolean runtimePresent(File dir) {
        if (dir == null || !dir.isDirectory()) return false;
        try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.walk(dir.toPath(), 4)) {
            return files.anyMatch(f -> {
                String n = String.valueOf(f.getFileName()).toLowerCase(java.util.Locale.ROOT);
                return (n.contains("cef") && (n.endsWith(".dll") || n.endsWith(".so") || n.endsWith(".dylib"))) || n.equals("chromium embedded framework.framework");
            });
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** Which natives to use: {@code auto} (MCEF's build, else upstream with a warning), {@code mcef} (only MCEF's, fail if missing), {@code upstream}. */
    private static volatile String nativesSource = "auto";

    /** The mod's {@code nativesSource} config value; read when the download starts. */
    public static void setNativesSource(String value) {
        nativesSource = value == null ? "auto" : value.toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Picks the source and checks it is reachable. In {@code auto}, a missing MCEF build (not published yet, or no network to GitHub) falls
     * back to the upstream natives, loudly: the Java side feature-detects the library, so this never mixes incompatible pieces.
     */
    private static MCEFDownloader chooseDownloader(String commit, MCEFPlatform platform) throws IOException {
        String want = System.getProperty("integratedntwiki.natives", nativesSource);
        if (want.equals("upstream")) return new MCEFDownloader(DOWNLOAD_MIRROR, commit, platform, MCEFDownloader.Source.UPSTREAM);
        MCEFDownloader custom = new MCEFDownloader(DOWNLOAD_MIRROR, commit, platform, MCEFDownloader.Source.MCEF);
        if (want.equals("mcef")) return custom;
        try {
            custom.checkPublished(); // changes nothing on disk; the real checksum comparison happens in download()
        } catch (IOException e) {
            LOGGER.warn("The MCEF Chromium build {} is not available ({}); using the upstream natives instead (30 fps cap, no threaded loop). "
                    + "Set nativesSource=upstream to stop trying, or =mcef to require it.", MCEFDownloader.mcefTag(commit), e.getMessage());
            return new MCEFDownloader(DOWNLOAD_MIRROR, commit, platform, MCEFDownloader.Source.UPSTREAM);
        }
        return custom;
    }

    private static void download() {
        try {
            MCEFPlatform platform = MCEFPlatform.getPlatform();
            MCEFDownloader downloader = chooseDownloader(getJavaCefCommit(), platform);
            LOGGER.info("Chromium runtime source: {} ({})", downloader.getSource(), downloader.getJavaCefDownloadUrl());
            File platformDir = new File(System.getProperty("jcef.path"));

            // Compare the remote checksum with the local one; a mismatch means the runtime must be re-downloaded.
            // "Installed" means the runtime's native library is there, not just a folder: a first run that failed half way leaves an empty
            // folder behind, and trusting it started the browser without a runtime (the window closed at once).
            boolean installed = runtimePresent(platformDir);
            boolean download;
            try {
                download = !downloader.downloadJavaCefChecksum();
            } catch (IOException e) {
                if (!installed) throw e;
                LOGGER.warn("Could not reach the CEF download host (" + (e.getCause() != null ? e.getCause() : e) + "); using the runtime already on disk.");
                download = false;
            }
            download |= !installed;

            if (download) {
                downloader.downloadJavaCefBuild();
                downloader.extractJavaCefBuild(true);
            }
            MCEFDownloadListener.INSTANCE.setDone(true);
            state = State.READY;
        } catch (IOException | RuntimeException e) {
            LOGGER.error("Failed to obtain the CEF runtime.", e);
            fail();
        }
    }

    /**
     * Initialises CEF. Must be called on the render thread after {@link #prepare()} reached {@link State#READY}.
     * @return true if CEF is running
     */
    public static boolean initialize() {
        if (isInitialized()) return true;
        if (state != State.READY) return false;
        if (com.levviata.integratedntwiki.shell.FrameProbe.ON) {
            com.levviata.integratedntwiki.shell.FrameProbe.setOutput(LOGGER::info);
            LOGGER.info("[probe] frame probe is ON (-D{}=true): a report is logged every 5 s", com.levviata.integratedntwiki.shell.FrameProbe.PROPERTY);
        }
        WikiLog.info("Initializing CEF on " + MCEFPlatform.getPlatform().getNormalizedName() + "...");
        if (CefUtil.init()) {
            app = new MCEFApp(CefUtil.getCefApp());
            client = new MCEFClient(CefUtil.getCefClient());
            // The navigation boundary must exist before the first browser does.
            WikiBrowserSecurity.install(client.getHandle());
            client.addContextMenuHandler(new BrowserContextMenu());
            WikiLog.info("Chromium Embedded Framework initialized");
            restoreLoginCookies();

            // These are important; the jcef process will linger around if not done. macOS is special.
            MCEFPlatform platform = MCEFPlatform.getPlatform();
            if (platform.isLinux() || platform.isWindows()) {
                Runtime.getRuntime().addShutdownHook(new Thread(MCEF::shutdown, "MCEF-Shutdown"));
            } else if (platform.isMacOS()) {
                CefUtil.getCefApp().macOSTerminationRequestRunnable = () -> {
                    shutdown();
                    Minecraft.getInstance().stop();
                };
            }
            return true;
        }
        LOGGER.error("Could not initialize Chromium Embedded Framework");
        fail();
        shutdown();
        return false;
    }

    /**
     * Logs the player in again in Chromium: the login cookies saved by an earlier login (the cookie file in the user's data folder, if
     * cookie caching is on) are handed to Chromium's cookie store before the first page loads. A cookie that expired is simply refused
     * by the wiki; nothing here can fail the start-up.
     */
    private static void restoreLoginCookies() {
        try {
            java.util.List<com.levviata.integratedntwiki.wiki.WikiCookies.Cached> cached = com.levviata.integratedntwiki.wiki.WikiCookies.cached();
            if (cached.isEmpty()) return;
            org.cef.network.CefCookieManager manager = org.cef.network.CefCookieManager.getGlobalManager();
            if (manager == null) return;
            long now = System.currentTimeMillis();
            java.util.Date created = new java.util.Date(now);
            for (com.levviata.integratedntwiki.wiki.WikiCookies.Cached c : cached) {
                java.util.Date expires = new java.util.Date(now + c.ageSeconds() * 1000L);
                manager.setCookie("https://nucleartech.wiki/", new org.cef.network.CefCookie(c.name(), c.value(), "nucleartech.wiki", "/",
                        true, true, created, created, true, expires));
            }
            WikiLog.info("Restored " + cached.size() + " cached wiki login cookies into Chromium");
        } catch (RuntimeException | LinkageError e) {
            WikiLog.warn("Could not restore the cached wiki login: " + e);
        }
    }

    public static MCEFApp getApp() {
        assertInitialized();
        return app;
    }

    public static MCEFClient getClient() {
        assertInitialized();
        return client;
    }

    /** Creates an off-screen Chromium browser of the given size (in pixels). */
    public static MCEFBrowser createBrowser(String url, boolean transparent, int width, int height) {
        assertInitialized();
        if (!WikiUrls.isTopLevelAllowed(url))
            throw new IllegalArgumentException("Not a permitted wiki URL: " + url);
        MCEFBrowser browser = new MCEFBrowser(client, url, transparent, true);
        browser.setCloseAllowed();
        browser.createImmediately();
        browser.resize(width, height);
        return browser;
    }

    public static boolean isInitialized() {
        return client != null;
    }

    // ---- settings the mod pushes in from its config (the mcef package does not read the config itself)

    private static volatile int fpsMinecraft = 30, fpsDesktop = 60;
    private static volatile boolean threadedLoopRequested;

    /** Frame rates (1 to 60) Chromium may produce for a browser in the game's shell and in the desktop window. Takes effect at the next frame. */
    public static void setFrameRates(int minecraftShell, int desktopWindow) {
        fpsMinecraft = Math.max(1, Math.min(60, minecraftShell));
        fpsDesktop = Math.max(1, Math.min(60, desktopWindow));
    }

    private static volatile boolean smoothScrolling = true;

    /** Spreads each wheel notch over several small steps (see {@link SmoothWheel}); off sends one jump per notch like before. */
    public static void setSmoothScrolling(boolean on) { smoothScrolling = on; }

    static boolean smoothScrolling() { return smoothScrolling; }

    static int frameRateMinecraft() { return fpsMinecraft; }

    static int frameRateDesktop() { return fpsDesktop; }

    /** Asks for CEF's own message loop thread (opt-in, Windows only, needs MCEF's libjcef). Must be set before Chromium starts. */
    public static void setThreadedLoopRequested(boolean wanted) {
        threadedLoopRequested = wanted;
    }

    static boolean threadedLoopRequested() { return threadedLoopRequested; }

    /** Whether the caller is on the thread the game's window library belongs to (always true with the host-pumped loop). */
    public static boolean isRenderThread() {
        return !NativeExt.threadedLoopActive() || Minecraft.getInstance().isSameThread();
    }

    /**
     * Runs {@code task} on the render thread: at once when this is it (always, with the host-pumped loop, where every callback already
     * is on it), otherwise queued to the game's main thread. For work in a Chromium callback that must not run on Chromium's thread
     * (the window library's calls, the shell's tab list).
     */
    public static void onRenderThread(Runnable task) {
        if (!NativeExt.threadedLoopActive()) { task.run(); return; }
        Minecraft mc = Minecraft.getInstance();
        if (mc.isSameThread()) { task.run(); return; }
        // Our own queue, drained by the game's next task run and, much sooner, by the desktop window's pump loop: the game's loop runs only a
        // few times a second while the game is in the background, and the desktop window's title, address and cursor waited for it.
        RENDER_TASKS.add(task);
        mc.execute(MCEF::drainRenderTasks);
    }

    private static final java.util.Queue<Runnable> RENDER_TASKS = new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** Runs the work Chromium's threads queued for the render thread. Call from the render thread only. */
    public static void drainRenderTasks() {
        Runnable t;
        while ((t = RENDER_TASKS.poll()) != null) {
            try {
                t.run();
            } catch (RuntimeException e) {
                LOGGER.error("A task queued for the render thread failed", e);
            }
        }
    }

    /** Lets CEF run one iteration of its message loop. Call once per frame from the render thread. Does nothing in the threaded loop mode. */
    public static void pump() {
        if (app == null || NativeExt.threadedLoopActive()) return;
        long probe = com.levviata.integratedntwiki.shell.FrameProbe.begin();
        app.getHandle().N_DoMessageLoopWork(); // Chromium's frame callbacks run in here, so the probe's pump time includes the paint copies
        com.levviata.integratedntwiki.shell.FrameProbe.pump(probe);
    }

    /** Request a shutdown of CEF. Nothing happens if not initialized. */
    public static synchronized void shutdown() {
        com.levviata.integratedntwiki.desktop.DesktopShell.shutdown();
        if (isInitialized()) {
            CefUtil.shutdown();
            client = null;
            app = null;
            killLingeringHelperOnWindows();
        }
    }

    // Replaces MCEF's CefWindowsShutdownMixin: kill jcef_helper.exe if it outlived CefApp.dispose().
    private static void killLingeringHelperOnWindows() {
        if (!MCEFPlatform.getPlatform().isWindows()) return;
        String processName = "jcef_helper.exe";
        try {
            Process process = new ProcessBuilder("tasklist").start();
            boolean running = false;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains(processName)) {
                        running = true;
                        break;
                    }
                }
            }
            if (running) {
                LOGGER.warn("JCEF is still running, killing to avoid lingering processes.");
                new ProcessBuilder("taskkill", "/F", "/IM", processName).start();
            }
        } catch (Exception e) {
            LOGGER.error("Unable to check if JCEF is still running. There may be lingering processes.", e);
        }
    }

    private static void assertInitialized() {
        if (!isInitialized())
            throw new RuntimeException("Chromium Embedded Framework was never initialized.");
    }

    /**
     * The java-cef commit whose prebuilt natives are downloaded. Taken from the system property
     * {@code mcef.java.cef.commit} or from the {@code jcef-commit.txt} resource generated from
     * {@code jcef_commit} in gradle.properties.
     */
    public static String getJavaCefCommit() throws IOException {
        String commit = System.getProperty("mcef.java.cef.commit");
        if (commit == null) {
            try (InputStream in = MCEF.class.getResourceAsStream("/jcef-commit.txt")) {
                if (in != null) commit = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            }
        }
        if (commit == null || commit.isBlank() || commit.startsWith("$"))
            throw new IOException("jcef_commit is not set in gradle.properties");
        return commit;
    }

    /** Helper method to get a GLFW cursor handle for the given {@link CefCursorType} cursor type. */
    static long getGLFWCursorHandle(CefCursorType cursorType) {
        if (CEF_TO_GLFW_CURSORS.containsKey(cursorType)) return CEF_TO_GLFW_CURSORS.get(cursorType);
        AutoscrollCursor.Direction panning = panningDirection(cursorType);
        if (panning != null) {
            long handle = createCustomCursor(panning);
            CEF_TO_GLFW_CURSORS.put(cursorType, handle);
            return handle;
        }
        // Some CEF cursor types have no GLFW equivalent (glfwId 0); GLFW rejects those, so use the default cursor.
        long glfwCursorHandle = cursorType.glfwId == 0 ? 0L : GLFW.glfwCreateStandardCursor(cursorType.glfwId);
        CEF_TO_GLFW_CURSORS.put(cursorType, glfwCursorHandle);
        return glfwCursorHandle;
    }

    /** The autoscroll cursor direction for one of Chromium's nine "panning" cursor types, or null for any other type. */
    private static AutoscrollCursor.Direction panningDirection(CefCursorType type) {
        return switch (type) {
            case MIDDLE_PANNING -> AutoscrollCursor.Direction.CENTER;
            case NORTH_PANNING -> AutoscrollCursor.Direction.NORTH;
            case NORTH_EAST_PANNING -> AutoscrollCursor.Direction.NORTH_EAST;
            case EAST_PANNING -> AutoscrollCursor.Direction.EAST;
            case SOUTH_EAST_PANNING -> AutoscrollCursor.Direction.SOUTH_EAST;
            case SOUTH_PANNING -> AutoscrollCursor.Direction.SOUTH;
            case SOUTH_WEST_PANNING -> AutoscrollCursor.Direction.SOUTH_WEST;
            case WEST_PANNING -> AutoscrollCursor.Direction.WEST;
            case NORTH_WEST_PANNING -> AutoscrollCursor.Direction.NORTH_WEST;
            default -> null;
        };
    }

    /**
     * A real GLFW cursor from {@link AutoscrollCursor}'s pixels. Being the OS cursor it replaces the arrow instead of
     * being drawn next to it. GLFW copies the pixels, so the buffer only has to live for this call. Returns 0 (the default
     * arrow) if the platform refuses the image, so autoscroll still works, just without its icon.
     */
    private static long createCustomCursor(AutoscrollCursor.Direction direction) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            byte[] rgba = AutoscrollCursor.rgba(direction);
            ByteBuffer pixels = stack.malloc(rgba.length);
            pixels.put(rgba).flip();
            GLFWImage image = GLFWImage.malloc(stack);
            image.set(AutoscrollCursor.SIZE, AutoscrollCursor.SIZE, pixels);
            long handle = GLFW.glfwCreateCursor(image, AutoscrollCursor.HOT, AutoscrollCursor.HOT);
            if (handle == 0L) LOGGER.warn("GLFW could not create the autoscroll cursor ({}); the arrow is shown instead", direction);
            return handle;
        }
    }
}
