package com.levviata.integratedntwiki.mcef;

/**
 * Turns a mouse-wheel notch into a short run of small scroll steps, like the smooth scrolling of a desktop browser.
 *
 * <p>Why: MCEF used to hand Chromium one jump of 96 px per notch. Chromium only paints when something changes, so the page painted once per
 * notch: with a mouse wheel that is 20 to 25 frames per second whatever the frame-rate cap, which looked choppy next to Chrome (measured:
 * 21.5 paints/s for jumps, about 50/s when the same distance is spread over the notch interval).
 *
 * <p>Chromium truncates a wheel delta to whole pixels (19.2 px became 19: 4 % of the distance lost, measured), so this class only ever
 * hands out whole pixels and carries the fraction to the next step. Pure: no Minecraft, CEF or AWT types, so it is unit-tested.
 */
public final class SmoothWheel {
    /** Time constant of the ease-out: about 95 % of a notch is delivered after three of these. */
    static final double TAU_MS = 55.0;
    /** A runaway wheel (or a stuck key repeat) must not queue seconds of scrolling. */
    static final double MAX_PENDING_PX = 3000.0;
    private static final long MIN_DT_NS = 1_000_000L, MAX_DT_NS = 50_000_000L;

    private double pending; // pixels still to deliver
    private double carry;   // fraction (< 1 px) left over from the last whole-pixel step

    /** Queues {@code px} (positive and negative are the two directions). Turning the wheel the other way drops what was still queued. */
    public synchronized void add(double px) {
        if (px == 0) return;
        if (pending != 0 && Math.signum(px) != Math.signum(pending)) {
            pending = 0;
            carry = 0;
        }
        pending = Math.max(-MAX_PENDING_PX, Math.min(MAX_PENDING_PX, pending + px));
    }

    /** @return whole pixels to send now, {@code dtNanos} after the previous step (0 when nothing is due) */
    public synchronized int step(long dtNanos) {
        if (pending == 0 && carry == 0) return 0;
        long dt = Math.max(MIN_DT_NS, Math.min(MAX_DT_NS, dtNanos));
        double move = pending * (1.0 - Math.exp(-(dt / 1e6) / TAU_MS));
        boolean last = Math.abs(pending - move) < 1.0; // the tail would take many steps of under one pixel: deliver it now
        if (last) move = pending;
        pending -= move;
        double total = carry + move;
        int out;
        if (last) {
            out = (int) Math.round(total);
            pending = 0;
            carry = 0;
        } else {
            out = (int) total; // toward zero
            carry = total - out;
        }
        return out;
    }

    public synchronized boolean idle() {
        return pending == 0 && carry == 0;
    }

    public synchronized void clear() {
        pending = 0;
        carry = 0;
    }
}
