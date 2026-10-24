package com.levviata.integratedntwiki.mcef;

/**
 * The per-frame Chromium housekeeping the Minecraft-drawn shell does inside its screen, for when the shell is a desktop window and
 * no screen exists. Exposes package-private pieces ({@code PageQuery}, {@code BrowserContextMenu}) to the desktop host. The page menu request is left for
 * the desktop host to take ({@link #takePageMenu}) and draw in the window.
 */
public final class DesktopPump {
    private DesktopPump() {}

    /** Call once per frame/tick on the render thread, only while no browser screen is open (that screen pumps for itself). */
    public static void frame() {
        PageQuery.install(MCEF.getClient());
        MCEF.pump();
        PageQuery.tick();
    }

    /** What Chromium's context menu is about (a copy of the package-private request), for the desktop window to build its menu from. */
    public record PageMenu(org.cef.browser.CefBrowser browser, org.cef.browser.CefFrame frame, String linkUrl, String selection,
                           String imageUrl, boolean editable, int editFlags) {}

    /** The page menu Chromium asked for since the last call, or null. Also how a stale request is dropped (when the window goes away). */
    public static PageMenu takePageMenu() {
        BrowserContextMenu.Request r = BrowserContextMenu.take();
        return r == null ? null : new PageMenu(r.browser(), r.frame(), r.linkUrl(), r.selection(), r.imageUrl(), r.editable(), r.editFlags());
    }
}
