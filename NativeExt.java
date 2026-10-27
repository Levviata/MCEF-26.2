package com.levviata.integratedntwiki.mcef;

import org.cef.browser.CefBrowser;

/**
 * Entry points of the MCEF-built {@code libjcef} (see {@code jcef-patches/}), kept in a class of our own so that no upstream JCEF Java class
 * has to change. An upstream libjcef (the CinemaMod download) does not export them: every call here then reports "not supported" instead
 * of failing, and the mod behaves exactly as before. No Minecraft types, so it can be tested without the game.
 */
public final class NativeExt {
    private NativeExt() {}

    /** The system property the native library reads, before CEF starts, to choose CEF's own multi-threaded message loop. */
    public static final String THREADED_LOOP_PROPERTY = "mcef.multiThreadedMessageLoop";

    private static native String buildId0();

    private static native boolean setWindowlessFrameRate0(CefBrowser browser, int fps);

    private static native boolean setHidden0(CefBrowser browser, boolean hidden);

    private static volatile String info;
    private static volatile boolean probed;

    /**
     * "build id | features" of the loaded native library, or null for an upstream build. Call after the native library is loaded
     * (after {@code CefApp.startup}); the answer is remembered.
     */
    public static String buildInfo() {
        if (!probed) {
            try {
                info = buildId0();
            } catch (UnsatisfiedLinkError e) {
                info = null;
            }
            probed = true;
        }
        return info;
    }

    /** Whether the loaded native library lists {@code feature} (for example {@code frame-rate}). */
    public static boolean hasFeature(String feature) {
        return NativeFeatures.has(buildInfo(), feature);
    }

    /**
     * Limits how often Chromium produces frames for one off-screen browser (1 to 60; CEF's own default is 30). This limits frame
     * production only, not the message loop, input or JavaScript. Safe to call from any thread.
     * @return true if the request reached the browser; false if the native library does not support it or the browser is not created yet
     */
    public static boolean setWindowlessFrameRate(CefBrowser browser, int fps) {
        if (!hasFeature("frame-rate")) return false;
        try {
            return setWindowlessFrameRate0(browser, Math.max(1, Math.min(60, fps)));
        } catch (UnsatisfiedLinkError e) {
            return false;
        }
    }

    /**
     * Hides or shows one off-screen browser for Chromium ({@code CefBrowserHost::WasHidden}): a hidden browser stops rendering and
     * painting and its animation frames stop, like a background tab in Chrome. Safe to call from any thread.
     * @return true if the request reached the browser; false if the native library does not support it or the browser is not created yet
     */
    public static boolean setHidden(CefBrowser browser, boolean hidden) {
        if (!hasFeature("hide-browser")) return false;
        try {
            return setHidden0(browser, hidden);
        } catch (UnsatisfiedLinkError e) {
            return false;
        }
    }

    /**
     * Asks for CEF's multi-threaded message loop; must be called before CEF starts. Takes effect only if the native library supports it
     * ({@link #hasFeature(String) "threaded-loop"}) and the platform is Windows (Linux needs XInitThreads before the first Xlib call, which
     * the game's window library does not do; macOS has no such mode).
     * @return whether the request will be honoured
     */
    public static boolean requestThreadedLoop(boolean wanted, boolean platformAllowsIt) {
        boolean honoured = wanted && platformAllowsIt && hasFeature("threaded-loop");
        if (honoured) System.setProperty(THREADED_LOOP_PROPERTY, "true");
        else System.clearProperty(THREADED_LOOP_PROPERTY);
        return honoured;
    }

    /** Whether the message loop runs on CEF's own thread (the host must then not call {@code N_DoMessageLoopWork}). */
    public static boolean threadedLoopActive() {
        return "true".equals(System.getProperty(THREADED_LOOP_PROPERTY));
    }
}
