import org.cef.CefApp;
import com.levviata.integratedntwiki.mcef.NativeExt;
import org.cef.CefClient;
import org.cef.CefSettings;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefBrowserOsr;
import java.awt.Rectangle;
import java.nio.ByteBuffer;
import java.util.Arrays;

/** Headless OSR benchmark: how often does Chromium paint, and how does that depend on how the host pumps it? */
public class OsrBench {
    static final long[] t = new long[200000];
    static volatile int n;
    static volatile Thread paintThread;
    static volatile int w = 988, h = 612;

    public static void main(String[] a) throws Exception {
        int seconds = Integer.getInteger("bench.seconds", 10);
        int pumpHz = Integer.getInteger("bench.pumpHz", 250);       // main-thread pump rate; 0 = as fast as sleep(1) allows
        int stallMs = Integer.getInteger("bench.stallMs", 0);       // block the pumping thread this long ...
        int stallEvery = Integer.getInteger("bench.stallEveryMs", 1000); // ... every this many ms
        String mode = System.getProperty("bench.mode", "external");
        String[] sw = {"--disable-gpu", "--disable-software-rasterizer"};
        if (!CefApp.startup(sw)) throw new RuntimeException("startup failed");
        boolean threaded = NativeExt.requestThreadedLoop(mode.equals("mt"), true);
        System.out.println("NATIVE info=" + NativeExt.buildInfo() + " threadedLoop=" + threaded);
        CefSettings s = new CefSettings();
        s.windowless_rendering_enabled = true;
        
        s.cache_path = "/tmp/osrbench-cache";
        s.background_color = s.new ColorType(0, 255, 255, 255);
        CefApp app = CefApp.getInstance(sw, s);
        CefClient c = app.createClient();
        String page = "data:text/html,<body style='margin:0'><div id=d style='font:40px monospace'>0</div><script>var i=0;function f(){document.getElementById('d').textContent=(i++)+' '+performance.now().toFixed(0);requestAnimationFrame(f)}f()</script>";
        class B extends CefBrowserOsr { B(CefClient c, String u) { super(c, u, false, null); } void rs() { wasResized(w, h); }
            @Override public Rectangle getViewRect(CefBrowser br) { return new Rectangle(0, 0, w, h); }
            @Override public void onPaint(CefBrowser br, boolean popup, Rectangle[] d, ByteBuffer buf, int ww, int hh) {
                int k = n; if (k < t.length) { t[k] = System.nanoTime(); n = k + 1; }
                paintThread = Thread.currentThread();
            }
        }
        B b = new B(c, page);
        b.createImmediately();
        b.rs();
        int fps = Integer.getInteger("bench.fps", 0);
        boolean ok = true;
        
        long start = System.nanoTime(), end = start + seconds * 1_000_000_000L, nextStall = start + 3_000_000_000L; // 3 s warm-up
        long pumps = 0; boolean applied = false;
        long periodNs = pumpHz > 0 ? 1_000_000_000L / pumpHz : 1_000_000L;
        long next = start;
        while (System.nanoTime() < end + 3_000_000_000L) {
            long now = System.nanoTime();
            if (!applied && now > start + 1_000_000_000L) { applied = true; if (fps > 0) System.out.println("NATIVE fps request " + fps + " -> " + NativeExt.setWindowlessFrameRate(b, fps)); }
            if (now >= next) { if (!NativeExt.threadedLoopActive()) app.N_DoMessageLoopWork(); pumps++; next += periodNs; if (next < now - periodNs) next = now; }
            if (stallMs > 0 && now >= nextStall) { Thread.sleep(stallMs); nextStall = System.nanoTime() + stallEvery * 1_000_000L; }
            long left = next - System.nanoTime();
            if (left > 200_000) Thread.sleep(left / 1_000_000L, (int) (left % 1_000_000L));
        }
        // report over the window after warm-up
        long from = start + 3_000_000_000L, to = end + 3_000_000_000L;
        long[] iv = new long[n]; int m = 0; long prev = 0; int cnt = 0;
        for (int i = 0; i < n; i++) { if (t[i] < from || t[i] > to) continue; cnt++; if (prev != 0) iv[m++] = t[i] - prev; prev = t[i]; }
        long[] x = Arrays.copyOf(iv, m); Arrays.sort(x);
        System.out.printf("RESULT mode=%s pumpHz=%d stall=%d/%d paints=%d (%.1f/s) pumps=%d (%.0f/s) interval ms p50=%.1f p95=%.1f max=%.1f paintThread=%s%n",
                mode, pumpHz, stallMs, stallEvery, cnt, cnt / (double) seconds, pumps, pumps / (seconds + 3.0),
                m == 0 ? 0 : x[m / 2] / 1e6, m == 0 ? 0 : x[(int) (m * 0.95)] / 1e6, m == 0 ? 0 : x[m - 1] / 1e6, paintThread);
        System.out.flush();
        Runtime.getRuntime().halt(0);
    }
}
