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
            if (platformDir.isDirectory()) prepare();
        } catch (RuntimeException e) {
            WikiLog.warn("Could not check for an installed Chromium runtime: {}", e.toString());
        }
    }

    private static void fail() {
        MCEFDownloadListener.INSTANCE.setFailed(true);
        state = State.FAILED;
    }

    private static void download() {
        try {
            MCEFPlatform platform = MCEFPlatform.getPlatform();
            MCEFDownloader downloader = new MCEFDownloader(DOWNLOAD_MIRROR, getJavaCefCommit(), platform);
            File platformDir = new File(System.getProperty("jcef.path"));

            // Compare the remote checksum with the local one; a mismatch means the runtime must be re-downloaded.
            boolean download;
            try {
                download = !downloader.downloadJavaCefChecksum();
            } catch (IOException e) {
                if (!platformDir.exists()) throw e;
                LOGGER.warn("Could not reach the CEF download host; using the runtime already on disk.");
                download = false;
            }
            download |= !platformDir.exists();

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
        WikiLog.info("Initializing CEF on " + MCEFPlatform.getPlatform().getNormalizedName() + "...");
        if (CefUtil.init()) {
            app = new MCEFApp(CefUtil.getCefApp());
            client = new MCEFClient(CefUtil.getCefClient());
            // The navigation boundary must exist before the first browser does.
            WikiBrowserSecurity.install(client.getHandle());
            client.addContextMenuHandler(new BrowserContextMenu());
            WikiLog.info("Chromium Embedded Framework initialized");

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

    /** Lets CEF run one iteration of its message loop. Call once per frame from the render thread. */
    public static void pump() {
        if (app != null) app.getHandle().N_DoMessageLoopWork();
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
