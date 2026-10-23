package com.levviata.integratedntwiki.mcef;

/**
 * Works out the "click count" Chromium needs on every mouse press and release.
 * <p>
 * Chromium (CEF {@code SendMouseClickEvent}) does not count clicks itself: a double-click selects a word and a
 * triple-click selects a paragraph only if the page is told {@code clickCount = 2 / 3}. Minecraft only offers a
 * {@code doubleClick} boolean, which cannot express a triple-click, so the count is tracked here the way
 * operating systems do: same button, close in time, close in position.
 * No Minecraft or CEF types, so it can be tested on its own.
 */
final class ClickCounter {
    /** Windows' default double-click time (GetDoubleClickTime); GLFW cannot query the OS setting. */
    static final long INTERVAL_NANOS = 500_000_000L;
    /** Largest pointer movement (browser pixels, per axis) that still counts as the same spot. */
    static final int SLOP = 4;
    /** Chromium selects a word at 2 and a paragraph at 3; a fourth quick click starts over, as native UIs do. */
    static final int MAX_COUNT = 3;

    private int button = -1;
    private int count;
    private int x, y;
    private long time;

    /**
     * @param hostSaysDouble Minecraft's own {@code doubleClick} flag; used as a lower bound of 2
     * @return the click count (1..3) to send with this press and the matching release
     */
    int press(int button, int px, int py, long nowNanos, boolean hostSaysDouble) {
        boolean sameSpot = this.button == button
                && nowNanos - time <= INTERVAL_NANOS
                && Math.abs(px - x) <= SLOP && Math.abs(py - y) <= SLOP;
        boolean wrapped = sameSpot && count >= MAX_COUNT;
        count = sameSpot ? (wrapped ? 1 : count + 1) : 1;
        if (hostSaysDouble && count < 2 && !wrapped) count = 2;
        this.button = button;
        x = px;
        y = py;
        time = nowNanos;
        return count;
    }

    /** Forget the sequence (window lost focus, browser replaced...). */
    void reset() {
        button = -1;
        count = 0;
    }
}
