package com.levviata.integratedntwiki.mcef;

/**
 * The pixels of Chromium's middle-click autoscroll cursor: a round badge with an arrow on each side, and a dot in
 * the middle. Chromium reports it as one of nine "panning" cursor types (the origin, plus the eight directions the
 * pointer can be pushed in); the stock GLFW cursors have no equivalent, so it is drawn here and installed as a real
 * GLFW cursor. Because it is the operating system's cursor, it replaces the normal arrow instead of being drawn
 * next to it. Pure (no Minecraft, GLFW or CEF types) so it can be tested.
 */
public final class AutoscrollCursor {
    public static final int SIZE = 32, HOT = SIZE / 2;

    /** Which arrows are lit: the middle shows all four, a direction lights only that way (diagonals light two). */
    public enum Direction {
        CENTER(true, true, true, true),
        NORTH(true, false, false, false), NORTH_EAST(true, true, false, false), EAST(false, true, false, false),
        SOUTH_EAST(false, true, true, false), SOUTH(false, false, true, false), SOUTH_WEST(false, false, true, true),
        WEST(false, false, false, true), NORTH_WEST(true, false, false, true);

        final boolean up, right, down, left;

        Direction(boolean up, boolean right, boolean down, boolean left) {
            this.up = up;
            this.right = right;
            this.down = down;
            this.left = left;
        }
    }

    private static final int WHITE = 0xFFF4F4F4, BLACK = 0xFF000000, DIM = 0xFF9C9C9C;

    private AutoscrollCursor() {}

    /** {@code SIZE*SIZE} ARGB pixels, row by row, top row first. Transparent outside the badge. */
    public static int[] pixels(Direction d) {
        int[] out = new int[SIZE * SIZE];
        double c = (SIZE - 1) / 2.0;
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                double dist = Math.hypot(x - c, y - c);
                int argb = 0;
                if (dist <= 12.0) argb = WHITE;
                else if (dist <= 14.4) argb = withAlpha(BLACK, Math.min(1.0, 14.9 - dist));
                if (dist <= 2.2) argb = BLACK; // the dot
                out[y * SIZE + x] = argb;
            }
        }
        arrow(out, d.up ? BLACK : DIM, 15.5, 5.0, 12.0, 10.0, 19.0, 10.0);
        arrow(out, d.down ? BLACK : DIM, 15.5, 26.0, 12.0, 21.0, 19.0, 21.0);
        arrow(out, d.left ? BLACK : DIM, 5.0, 15.5, 10.0, 12.0, 10.0, 19.0);
        arrow(out, d.right ? BLACK : DIM, 26.0, 15.5, 21.0, 12.0, 21.0, 19.0);
        return out;
    }

    /** The same pixels as the RGBA bytes GLFW wants (not premultiplied). */
    public static byte[] rgba(Direction d) {
        int[] argb = pixels(d);
        byte[] out = new byte[argb.length * 4];
        for (int i = 0; i < argb.length; i++) {
            out[i * 4] = (byte) (argb[i] >> 16);
            out[i * 4 + 1] = (byte) (argb[i] >> 8);
            out[i * 4 + 2] = (byte) argb[i];
            out[i * 4 + 3] = (byte) (argb[i] >>> 24);
        }
        return out;
    }

    private static int withAlpha(int rgb, double alpha) {
        return ((int) Math.round(Math.max(0, Math.min(1, alpha)) * 255) << 24) | (rgb & 0xFFFFFF);
    }

    /** Fills a triangle (by sign-of-area tests at pixel centres). */
    private static void arrow(int[] px, int color, double ax, double ay, double bx, double by, double cx, double cy) {
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                double s1 = edge(ax, ay, bx, by, x + 0.5, y + 0.5), s2 = edge(bx, by, cx, cy, x + 0.5, y + 0.5), s3 = edge(cx, cy, ax, ay, x + 0.5, y + 0.5);
                boolean neg = s1 < 0 || s2 < 0 || s3 < 0, pos = s1 > 0 || s2 > 0 || s3 > 0;
                if (!(neg && pos)) px[y * SIZE + x] = color;
            }
        }
    }

    private static double edge(double ax, double ay, double bx, double by, double px, double py) {
        return (px - bx) * (ay - by) - (ax - bx) * (py - by);
    }
}
