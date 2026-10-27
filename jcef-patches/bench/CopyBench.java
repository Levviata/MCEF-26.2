import java.nio.*;

/** Frame-conversion variants: what the in-game copy does (per pixel IntBuffer.get + setPixel) against reading rows in bulk. */
public class CopyBench {
    static final int W = 2510, H = 1312;
    // stand-in for NativeImage.setPixelABGR: a bounds-checked write to off-heap memory
    static IntBuffer img = ByteBuffer.allocateDirect(W * H * 4).order(ByteOrder.nativeOrder()).asIntBuffer();
    static void setPixelABGR(int x, int y, int v) {
        if (x < 0 || y < 0 || x >= W || y >= H) throw new IllegalArgumentException();
        img.put(x + y * W, v);
    }
    static int swz(int argb) { return (argb & 0xFF00FF00) | ((argb >> 16) & 0xFF) | ((argb & 0xFF) << 16); }

    public static void main(String[] a) {
        ByteBuffer src = ByteBuffer.allocateDirect(W * H * 4);
        for (int i = 0; i < W * H * 4; i++) src.put(i, (byte) (i * 31));
        int rounds = 60;
        double[] res = new double[3];
        for (int pass = 0; pass < 3; pass++) {
            long t0 = System.nanoTime();
            for (int r = 0; r < rounds; r++) {            // A: current code
                IntBuffer p = src.duplicate().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
                for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) setPixelABGR(x, y, swz(p.get(y * W + x)));
            }
            long t1 = System.nanoTime();
            int[] row = new int[W];
            for (int r = 0; r < rounds; r++) {            // B: rows read in bulk, per-pixel setPixel
                IntBuffer p = src.duplicate().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
                for (int y = 0; y < H; y++) { p.position(y * W); p.get(row, 0, W); for (int x = 0; x < W; x++) setPixelABGR(x, y, swz(row[x])); }
            }
            long t2 = System.nanoTime();
            int[] out = new int[W];
            for (int r = 0; r < rounds; r++) {            // C: rows read AND written in bulk (needs a bulk write API; reference only)
                IntBuffer p = src.duplicate().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
                IntBuffer o = img.duplicate();
                for (int y = 0; y < H; y++) { p.position(y * W); p.get(row, 0, W); for (int x = 0; x < W; x++) out[x] = swz(row[x]); o.position(y * W); o.put(out, 0, W); }
            }
            long t3 = System.nanoTime();
            res[0] = (t1 - t0) / 1e6 / rounds; res[1] = (t2 - t1) / 1e6 / rounds; res[2] = (t3 - t2) / 1e6 / rounds;
            System.out.printf("pass %d: A current %.2f ms   B bulk-read %.2f ms   C bulk-read+write %.2f ms   (full %dx%d frame)%n", pass, res[0], res[1], res[2], W, H);
        }
    }
}
