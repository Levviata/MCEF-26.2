package com.levviata.integratedntwiki.mcef;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.cef.CefSettings;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefDisplayHandlerAdapter;

/**
 * Asks the page a question and gets the answer back. This java-cef has no message router set up, so the answer travels
 * the one channel an injected script always has: {@code console.log}, which arrives in {@code onConsoleMessage}. Every
 * question carries a random one-time number; only a message with that number, from that browser, is accepted, so page
 * scripts cannot make the shell act by logging something. A question the page never answers (script blocked, page
 * navigating) times out and runs its fallback. Callbacks run inside {@code MCEF.pump()} on the render thread.
 */
public final class PageQuery {
    private static final String PREFIX = "__integratedntwiki_query:";
    private static final long TIMEOUT_MILLIS = 400;
    private static final SecureRandom RANDOM = new SecureRandom();

    private record Pending(CefBrowser browser, Consumer<String> reply, Runnable timeout, long deadline) {}

    /** How long marker captures and restores wait: the page may be busy laying out a long article. */
    public static final long SLOW_TIMEOUT_MILLIS = 2000;

    private static final Map<Long, Pending> PENDING = new ConcurrentHashMap<>();
    private static MCEFClient installedOn;

    private PageQuery() {}

    /** Registers the console listener once per Chromium client. */
    static void install(MCEFClient client) {
        if (client == null || installedOn == client) return;
        installedOn = client;
        client.addDisplayHandler(new CefDisplayHandlerAdapter() {
            @Override
            public boolean onConsoleMessage(CefBrowser browser, CefSettings.LogSeverity level, String message, String source, int line) {
                if (message == null || !message.startsWith(PREFIX)) return false;
                int colon = message.indexOf(':', PREFIX.length());
                if (colon < 0) return true;
                long nonce;
                try {
                    nonce = Long.parseLong(message.substring(PREFIX.length(), colon));
                } catch (NumberFormatException e) {
                    return true;
                }
                Pending p = PENDING.get(nonce);
                if (p == null || p.browser() != browser || !PENDING.remove(nonce, p)) return true;
                String value;
                try {
                    value = URLDecoder.decode(message.substring(colon + 1), StandardCharsets.UTF_8);
                } catch (IllegalArgumentException e) {
                    value = "";
                }
                p.reply().accept(value);
                return true; // ours: keep it out of the log
            }
        });
    }

    /**
     * Runs {@code body} (JavaScript statements that {@code return} a value) in the frame and hands the value, as a string,
     * to {@code reply}.
     *
     * @param frame   where to run it; null means the frame that has focus
     * @param timeout run when the page does not answer in time (may be null)
     */
    static void ask(CefBrowser browser, CefFrame frame, String body, Consumer<String> reply, Runnable timeout) {
        ask(browser, frame, body, reply, timeout, TIMEOUT_MILLIS);
    }

    /** As above, with a longer wait ({@link #SLOW_TIMEOUT_MILLIS}) for questions that scan the whole page. */
    public static void ask(CefBrowser browser, CefFrame frame, String body, Consumer<String> reply, Runnable timeout, long timeoutMillis) {
        CefFrame target = frame != null ? frame : browser.getFocusedFrame();
        if (target == null) {
            if (timeout != null) timeout.run();
            return;
        }
        long nonce = RANDOM.nextLong() & Long.MAX_VALUE;
        PENDING.put(nonce, new Pending(browser, reply, timeout, System.currentTimeMillis() + timeoutMillis));
        String js = "(function(){var r='';try{r=String((function(){" + body + "})());}catch(e){r='';}"
                + "console.log('" + PREFIX + nonce + ":'+encodeURIComponent(r));})();";
        target.executeJavaScript(js, target.getURL(), 0);
    }

    /** Call every frame: runs the fallback of questions that were not answered in time. */
    static void tick() {
        if (PENDING.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (Map.Entry<Long, Pending> e : PENDING.entrySet()) {
            Pending p = e.getValue();
            if (now >= p.deadline() && PENDING.remove(e.getKey(), p) && p.timeout() != null) p.timeout().run();
        }
    }
}
