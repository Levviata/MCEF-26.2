package com.levviata.integratedntwiki.mcef;

/**
 * The per-frame Chromium housekeeping the Minecraft-drawn shell does inside its screen, for when the shell is a desktop window and
 * no screen exists. Exposes package-private pieces ({@code PageQuery}, {@code BrowserContextMenu}) to the desktop host.
 */
public final class DesktopPump {
    private DesktopPump() {}

    /** Call once per frame/tick on the render thread, only while no browser screen is open (that screen pumps for itself). */
    public static void frame() {
        PageQuery.install(MCEF.getClient());
        MCEF.pump();
        PageQuery.tick();
        BrowserContextMenu.take(); // the page menu is a Minecraft-drawn feature: drop requests so none pops up later in the game
    }
}
