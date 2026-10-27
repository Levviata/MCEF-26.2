package com.levviata.integratedntwiki.mcef;

import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;

/**
 * Routes Minecraft input to one {@link MCEFBrowser} and makes keyboard ownership explicit.
 * <pre>
 *   Owner.BROWSER   (page, form field or address bar focused) -> Chromium gets keys and text; Minecraft does not.
 *   Owner.MINECRAFT (a Minecraft shell widget focused)        -> keys/text are NOT consumed here, the screen handles them.
 * </pre>
 * Mouse events are owned by whatever is under the cursor: inside the view they go to Chromium, elsewhere the
 * screen's own widgets get them. A key or button event is never delivered to both sides.
 * All coordinates are Minecraft GUI units; the view is placed with {@link #setViewport}.
 */
public final class BrowserInput {
    public enum Owner { BROWSER, MINECRAFT }

    /** GLFW buttons 0..2 (left, right, middle) are forwarded; extra buttons (3, 4) are handled by the shell, not Chromium. */
    private static final int MAX_FORWARDED_BUTTON = GLFW.GLFW_MOUSE_BUTTON_MIDDLE;

    private final MCEFBrowser browser;
    private Owner owner = Owner.BROWSER;
    private boolean windowFocused = true;
    private int viewX, viewY, viewWidth, viewHeight;
    private float scale = 1F;
    private int modifiers; // GLFW modifier bits from the latest key event
    private double lastX, lastY; // last pointer position sent to the page (GUI units), for cancelling autoscroll
    /** When a cancel of autoscroll that Esc did not end is escalated to a click (0 = no cancel pending). */
    private long cancelEscalateAt;
    private static final long CANCEL_ESCALATE_MILLIS = 250;
    private final ClickCounter clicks = new ClickCounter();
    /** Click count sent with each button's press, repeated on its release (indexed by GLFW button 0..2). */
    private final int[] pressCount = {1, 1, 1};

    public BrowserInput(MCEFBrowser browser) {
        this.browser = browser;
        applyFocus();
    }

    /** Where the browser is drawn, in GUI units, and the GUI scale (browser pixels per GUI unit). */
    public void setViewport(int x, int y, int width, int height, float guiScale) {
        viewX = x;
        viewY = y;
        viewWidth = width;
        viewHeight = height;
        scale = guiScale;
    }

    public boolean inView(double x, double y) {
        return x >= viewX && x < viewX + viewWidth && y >= viewY && y < viewY + viewHeight;
    }

    public Owner owner() {
        return owner;
    }

    public void focusBrowser() {
        owner = Owner.BROWSER;
        applyFocus();
    }

    public void focusMinecraft() {
        owner = Owner.MINECRAFT;
        applyFocus();
    }

    /** Call every frame: tells Chromium when the whole game window loses or regains OS focus (alt-tab). */
    public void updateWindowFocus() {
        boolean focused = GLFW.glfwGetWindowAttrib(GLFW.glfwGetCurrentContext(), GLFW.GLFW_FOCUSED) == GLFW.GLFW_TRUE;
        if (focused != windowFocused) {
            windowFocused = focused;
            if (!focused) cancelAutoscroll(); // alt-tab away: nothing would end it
            applyFocus();
        }
    }

    private void applyFocus() {
        browser.setFocus(owner == Owner.BROWSER && windowFocused);
    }

    // ---- modifier keys held right now, as CEF event flags

    private static boolean down(long window, int key) {
        return GLFW.glfwGetKey(window, key) == GLFW.GLFW_PRESS;
    }

    /**
     * Ctrl / Shift / Alt as GLFW modifier bits (what this java-cef's native code decodes for mouse, wheel and key events:
     * {@code GetCefModifiersGlfw}; it adds the button masks itself), read from the keyboard at the moment of the event
     * (not remembered from the last key event, which goes stale when a key is released while another screen part had focus).
     */
    static int heldFlags() {
        long w = GLFW.glfwGetCurrentContext();
        int f = 0;
        if (down(w, GLFW.GLFW_KEY_LEFT_SHIFT) || down(w, GLFW.GLFW_KEY_RIGHT_SHIFT)) f |= GLFW.GLFW_MOD_SHIFT;
        if (down(w, GLFW.GLFW_KEY_LEFT_CONTROL) || down(w, GLFW.GLFW_KEY_RIGHT_CONTROL)) f |= GLFW.GLFW_MOD_CONTROL;
        if (down(w, GLFW.GLFW_KEY_LEFT_ALT) || down(w, GLFW.GLFW_KEY_RIGHT_ALT)) f |= GLFW.GLFW_MOD_ALT;
        return f;
    }

    private int pixelX(double gui) {
        return (int) Math.round((gui - viewX) * scale);
    }

    private int pixelY(double gui) {
        return (int) Math.round((gui - viewY) * scale);
    }

    // ---- mouse

    public void mouseMoved(double x, double y) {
        lastX = x;
        lastY = y;
        browser.sendMouseMove(pixelX(x), pixelY(y), heldFlags());
    }

    /**
     * @param doubleClick Minecraft's own double-click flag; Chromium needs a real click count, see {@link ClickCounter}
     * @return true if Chromium took the click (it was inside the view, on a forwarded button)
     */
    public boolean mousePressed(double x, double y, int button, boolean doubleClick) {
        if (!inView(x, y) || button > MAX_FORWARDED_BUTTON) return false;
        com.levviata.integratedntwiki.shell.FrameProbe.input();
        focusBrowser();
        int px = pixelX(x), py = pixelY(y);
        int count = clicks.press(button, px, py, System.nanoTime(), doubleClick);
        pressCount[button] = count;
        lastX = x;
        lastY = y;
        browser.sendMousePress(px, py, button, count, heldFlags());
        return true;
    }

    /** Releases are always forwarded, even outside the view, so a button can never stay stuck down in the page. */
    public boolean mouseReleased(double x, double y, int button) {
        if (button > MAX_FORWARDED_BUTTON) return false;
        browser.sendMouseRelease(pixelX(x), pixelY(y), button, pressCount[button], heldFlags());
        return true;
    }

    /** The pointer left the view with no button held: clear hover state in the page. */
    public void mouseLeftView(double x, double y) {
        browser.sendMouseExit(pixelX(x), pixelY(y));
    }

    public boolean buttonsDown() {
        return browser.hasButtonsDown();
    }

    /** Dragging continues outside the view (text selection, scrollbar thumbs keep following the pointer). */
    public void mouseDragged(double x, double y) {
        lastX = x;
        lastY = y;
        browser.sendMouseMove(pixelX(x), pixelY(y), heldFlags());
    }

    /**
     * Wheel: vertical, plus horizontal for a horizontal wheel / tilt. This java-cef takes one wheel delta per event, so
     * a horizontal turn is sent as Shift+wheel, which is what Chromium itself turns into horizontal scrolling. A real
     * Shift+wheel arrives with Shift already held.
     */
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (!inView(x, y)) return false;
        com.levviata.integratedntwiki.shell.FrameProbe.input();
        int flags = heldFlags();
        if (scrollY != 0) browser.sendMouseWheel(pixelX(x), pixelY(y), scrollY, flags);
        // Chromium's wheel delta is the direction the content moves, so a turn to the right (+x) is a negative delta.
        if (scrollX != 0) browser.sendMouseWheel(pixelX(x), pixelY(y), -scrollX, flags | GLFW.GLFW_MOD_SHIFT);
        return true;
    }

    // ---- autoscroll (middle-click scrolling, drawn and driven by Chromium)

    /** Whether Chromium's autoscroll is running in this page (it shows its panning cursor exactly as long as it does). */
    public boolean autoscrolling() {
        return browser.isAutoscrolling();
    }

    /**
     * Ends an autoscroll that is running, without clicking anything in the page. Esc is what ends it in Chromium (any key
     * does), and it is harmless to the page. If the cursor still says "autoscrolling" shortly after (see {@link #tick}), the
     * cancel is repeated as the middle click that ends it in Chromium too.
     */
    public void cancelAutoscroll() {
        if (!browser.isAutoscrolling()) return;
        int esc = GLFW.GLFW_KEY_ESCAPE;
        long scan = GLFW.glfwGetKeyScancode(esc);
        browser.sendKeyPress(esc, scan, 0);
        browser.sendKeyRelease(esc, scan, 0);
        browser.sendMouseMove(pixelX(lastX), pixelY(lastY), 0); // makes Chromium re-evaluate (and re-send) the cursor
        cancelEscalateAt = System.currentTimeMillis() + CANCEL_ESCALATE_MILLIS;
    }

    /** Call every frame: finishes a cancel that Esc did not complete, so autoscroll can never stay stuck on. */
    public void tick() {
        if (cancelEscalateAt == 0 || System.currentTimeMillis() < cancelEscalateAt) return;
        cancelEscalateAt = 0;
        if (!browser.isAutoscrolling()) return;
        int px = pixelX(lastX), py = pixelY(lastY);
        // a middle press while autoscrolling ends it and is swallowed by the page; the release closes the pair
        browser.sendMousePress(px, py, GLFW.GLFW_MOUSE_BUTTON_MIDDLE, 1, 0);
        browser.sendMouseRelease(px, py, GLFW.GLFW_MOUSE_BUTTON_MIDDLE, 1, 0);
    }

    // ---- keyboard and text (only while the browser owns the keyboard)

    public boolean keyPressed(KeyEvent event) {
        modifiers = event.modifiers();
        if (owner != Owner.BROWSER) return false;
        com.levviata.integratedntwiki.shell.FrameProbe.input();
        browser.sendKeyPress(event.key(), event.scancode(), event.modifiers());
        // GLFW sends no character for Enter, but Chromium submits forms and follows focused links on the text event.
        if ((event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) && (event.modifiers() & GLFW.GLFW_MOD_ALT) == 0)
            browser.sendKeyTyped('\r', event.modifiers() & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SHIFT));
        return true;
    }

    public boolean keyReleased(KeyEvent event) {
        modifiers = event.modifiers();
        if (owner != Owner.BROWSER) return false;
        browser.sendKeyRelease(event.key(), event.scancode(), event.modifiers());
        return true;
    }

    /**
     * Text input arrives as Unicode code points, separate from key presses, so dead keys, AltGr and non-ASCII
     * layouts type real characters. Modifiers are deliberately not attached: on Windows AltGr is reported as
     * Ctrl+Alt, and Chromium would then treat the character as a shortcut instead of inserting it.
     */
    public boolean charTyped(CharacterEvent event) {
        if (owner != Owner.BROWSER) return false;
        // Only Shift is attached, and only to Space: Shift+Space is "page up" in Chromium and it reads that from the text
        // event, not the key event. Other modifiers stay off so AltGr (reported as Ctrl+Alt) still types its character.
        int flags = heldFlags();
        boolean shiftSpace = event.codepoint() == ' ' && (flags & GLFW.GLFW_MOD_SHIFT) != 0
                && (flags & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_ALT)) == 0;
        for (char c : Character.toChars(event.codepoint())) browser.sendKeyTyped(c, shiftSpace ? GLFW.GLFW_MOD_SHIFT : 0);
        return true;
    }
}
