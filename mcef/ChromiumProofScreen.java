package com.levviata.integratedntwiki.mcef;

import com.levviata.integratedntwiki.browser.UrlInput;
import com.levviata.integratedntwiki.browser.WikiUrls;
import com.levviata.integratedntwiki.client.WikiKeys;
import com.levviata.integratedntwiki.config.WikiConfig;
import com.levviata.integratedntwiki.shell.BrowserSession;
import com.levviata.integratedntwiki.shell.BrowserTab;
import com.levviata.integratedntwiki.shell.Favicons;
import com.levviata.integratedntwiki.shell.Icons;
import com.levviata.integratedntwiki.shell.LoadErrors;
import com.levviata.integratedntwiki.shell.PageKeys;
import com.levviata.integratedntwiki.shell.ShellLayout;
import com.levviata.integratedntwiki.shell.ShellLayout.Hit;
import com.levviata.integratedntwiki.shell.ShellLayout.Part;
import com.levviata.integratedntwiki.shell.ShellLayout.Rect;
import com.levviata.integratedntwiki.shell.ShellScale;
import com.levviata.integratedntwiki.shell.Shortcuts;
import com.levviata.integratedntwiki.shell.TextField;
import com.levviata.integratedntwiki.shell.UrlBarState;
import com.levviata.integratedntwiki.shell.Zoom;
import java.util.HashSet;
import java.util.OptionalInt;
import java.util.Set;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * The integrated browser window: a Minecraft-drawn shell (frame, tabs, window and navigation buttons) around Chromium's view.
 * Chromium still renders the page and owns everything inside it; this screen only owns the shell, and routes the mouse to
 * whichever side is under the pointer. Tabs and the window rectangle live in {@link BrowserSession}, so Esc / the minimize
 * button only close this screen, while the close button ends the session.
 * <p>
 * Size: the shell is laid out in "shell units" of {@link ShellScale#unit} physical pixels each (setting {@code shellScale},
 * 0 = follow the GUI scale), independent of the Chromium page, whose size is the content rectangle in physical pixels.
 * All mouse coordinates are converted to shell units on entry; the shell is drawn under one pose scale.
 * <p>
 * Phase 6: the Chromium keyboard/mouse controls. Browser shortcuts come from one table ({@link Shortcuts}); a key that matches
 * is handled here and never also reaches the page or Minecraft. Zoom is Chromium's own page zoom, kept per tab. The find bar
 * and the zoom menu are shell overlays drawn above the page; neither takes the keyboard away from the page unless one of
 * their text fields is being edited.
 * (Still named ChromiumProofScreen: the legacy WikiScreen keeps serving the H key until the NTM integration phase.)
 */
public final class ChromiumProofScreen extends Screen {
    private static final int BG = 0xFF202020, EDGE = 0xFF5A5A5A, TAB_BAR_BG = 0xFF171717,
            TAB_ACTIVE = 0xFF3A3A3A, TAB_IDLE = 0xFF262626, TAB_HOVER = 0xFF303030, TOOLBAR_BG = 0xFF3A3A3A,
            BUTTON_HOVER = 0xFF555555, BUTTON_PRESSED = 0xFF6E6E6E, CLOSE_HOVER = 0xFFB02020, PAGE_BASE = 0xFF101010,
            TEXT = 0xFFE6E6E6, TEXT_DIM = 0xFF808080, ACCENT = 0xFF4FA0FF, ERROR_COLOR = 0xFFFF5555,
            FIELD_BG = 0xFF161616, SELECTION = 0xAA3A6EA5, PIN_COLOR = 0xFFD8A83A;
    private static final long STATUS_MILLIS = 4000, CARET_BLINK_MILLIS = 530, CHARS_AFTER_HOTKEY_IGNORED_MILLIS = 120;

    /** Ctrl+Shift+F1..F9 load these through the browser to prove the boundary; all but the first must be blocked. */
    private static final String[] BOUNDARY_TESTS = {
            "https://nucleartech.wiki/wiki/Main_Page",
            "https://example.com/",
            "file:///C:/Windows/win.ini",
            "http://localhost:8080/",
            "https://nucleartech.wiki.example.com/",
            "https://example.com/nucleartech.wiki",
            "https://nucleartech.wiki@example.com/",
            "ftp://nucleartech.wiki/",
            "data:text/html,<h1>not allowed</h1>"
    };

    private enum Drag { NONE, MOVE, RIGHT, BOTTOM, CORNER, TAB }

    private final Screen parent;
    private BrowserSession session;
    private boolean shown, disposed, overContent;
    private Drag drag = Drag.NONE;
    /** Index of the tab being dragged to a new position (Drag.TAB). */
    private int dragTab = -1;

    // Context menu (Phase 8): Chromium says when and about what (BrowserContextMenu); the shell draws it.
    /** A menu row. {@code label == null} is a separator line. {@code shortcut} is shown right-aligned, as a hint. */
    private record MenuItem(String label, String shortcut, boolean enabled, Runnable action) {
        boolean separator() { return label == null; }
    }
    /** Two different menus: the page's (Chromium says what was clicked) and a tab's (the shell's own). They never share state. */
    private enum MenuKind { PAGE, TAB }
    private MenuKind menuKind = MenuKind.PAGE;
    /** The tab a TAB menu acts on (the right-clicked tab, not necessarily the active one). */
    private BrowserTab menuTarget;
    /** The right button went down on the shell (tab menu): its release is not forwarded to the page. */
    private boolean swallowRightRelease;
    private static final int MENU_ITEM_H = 12;
    private boolean menuOpen;
    private BrowserTab menuTab;
    private java.util.List<MenuItem> menuItems = java.util.List.of();
    private int menuX, menuY, menuW, menuH, rightClickX, rightClickY;
    private int grabX, grabY, pressShellX, pressShellY;
    /** The shell control the left button went down on; it only fires if the button is released on the same control. */
    private Hit pressed;

    // URL bar (Phase 5). Visibility rules live in the session's UrlBarState; this is the editing side.
    private final TextField urlText = new TextField();
    private boolean urlFocused;
    /** The text field the pointer is dragging a selection in (URL bar, find bar or zoom value), or null. */
    private TextField draggingField;
    /** The tab URL the field was last filled from; while the field is not being edited it follows the tab. */
    private String syncedUrl = "";
    private long caretBlinkStart, ignoreCharsUntil, statusUntil;
    private String status = "";

    // Zoom menu (Phase 6): "[-  100%  +  [Reset]]". Clicking the percentage turns it into a field to type a zoom into.
    private boolean zoomMenuOpen, zoomEditing;
    private final TextField zoomText = new TextField();
    private double wheelZoomAccumulator;

    // Find in page (Phase 6). The bar belongs to one tab; switching tabs closes it.
    private boolean findOpen, findFocused;
    private final TextField findText = new TextField();
    private BrowserTab findTab;
    /** What was last handed to Chromium's search; a different text starts a new search, the same one continues it. */
    private String searchedText = "";

    /** Keys whose press this screen consumed (shortcuts, Esc): their release must not reach the page either. */
    private final Set<Integer> consumedKeys = new HashSet<>();

    /** Shown once as a status line when the screen opens (e.g. "Nothing to look up here"); null for none. */
    private String openMessage;

    private ChromiumProofScreen(Screen parent) {
        super(Component.translatable("integratedntwiki.title"));
        this.parent = parent;
    }

    public static void open(net.minecraft.client.Minecraft minecraft) {
        minecraft.gui.setScreen(new ChromiumProofScreen(minecraft.gui.screen()));
    }

    /** The H key: opens the window over {@code parent} on {@code url} (a wiki address), optionally with a message. */
    public static void open(net.minecraft.client.Minecraft minecraft, Screen parent, String url, String message) {
        ChromiumProofScreen screen = new ChromiumProofScreen(parent);
        screen.openMessage = message;
        BrowserSession.get().showAddress(url);
        minecraft.gui.setScreen(screen);
    }

    @Override
    protected void init() {
        MCEF.prepare();
        session = BrowserSession.get();
        if (openMessage != null) { showStatus(openMessage); openMessage = null; }
        session.useUnit(unit());
        session.place(shellW(), shellH());
        if (!shown) {
            shown = true;
            session.onScreenShown();
        }
    }

    private double guiScale() {
        return minecraft.getWindow().getGuiScale();
    }

    /** Physical screen pixels per shell unit (the shell size setting). */
    private int unit() {
        return ShellScale.unit(WikiConfig.SHELL_SCALE.get(), guiScale());
    }

    private int shellW() {
        return ShellScale.extent(width, guiScale(), unit());
    }

    private int shellH() {
        return ShellScale.extent(height, guiScale(), unit());
    }

    private double toShell(double guiCoordinate) {
        return ShellScale.toShell(guiCoordinate, guiScale(), unit());
    }

    private Hit hitAt(double guiX, double guiY) {
        return session.layout().hit(toShell(guiX), toShell(guiY), session.activeIndex(), session.urlBar().visible(), zoomMenuOpen, findOpen);
    }

    private static boolean isZoomPart(Part part) {
        return part == Part.ZOOM_BUTTON || part == Part.ZOOM_MENU || part == Part.ZOOM_OUT || part == Part.ZOOM_VALUE
                || part == Part.ZOOM_IN || part == Part.ZOOM_RESET;
    }

    private static boolean isFindPart(Part part) {
        return part == Part.FIND_BAR || part == Part.FIND_FIELD || part == Part.FIND_PREVIOUS || part == Part.FIND_NEXT || part == Part.FIND_CLOSE;
    }

    /** Parts that are drawn over the page and swallow what happens on them (the zoom button itself is in the toolbar). */
    private static boolean isOverlay(Part part) {
        return (isZoomPart(part) && part != Part.ZOOM_BUTTON) || isFindPart(part);
    }

    /** The setting changed (Ctrl+scroll, or the config screen): keep the window's physical size and position. */
    private void syncUnit() {
        int unit = unit();
        if (unit == session.unit()) return;
        session.useUnit(unit);
        session.place(shellW(), shellH());
    }

    private TextField.Metrics metrics() {
        return (String text) -> font.width(text);
    }

    private static boolean ctrlDown() {
        long window = GLFW.glfwGetCurrentContext();
        return GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS;
    }

    private void adjustShellScale(int direction) {
        int next = ShellScale.step(unit(), direction);
        if (next == unit()) return;
        WikiConfig.SHELL_SCALE.set(next);
        WikiConfig.SHELL_SCALE.save();
    }

    private void showStatus(String message) {
        status = message;
        statusUntil = System.currentTimeMillis() + STATUS_MILLIS;
    }

    // ---- drawing

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        if (MCEF.state() == MCEF.State.FAILED) {
            g.centeredText(font, Component.translatable("integratedntwiki.chromium.failed"), width / 2, height / 2, ERROR_COLOR);
            return;
        }
        if (MCEF.state() == MCEF.State.DOWNLOADING) {
            MCEFDownloadListener d = MCEFDownloadListener.INSTANCE;
            g.centeredText(font, Component.translatable("integratedntwiki.chromium.downloading",
                    String.valueOf(d.getTask()), Math.round(d.getProgress() * 100)), width / 2, height / 2, 0xFFFFFFFF);
            return;
        }
        if (!MCEF.initialize()) return;
        session.ensureHandlers();
        PageQuery.install(MCEF.getClient());

        syncUnit();
        int unit = session.unit();
        Rect content = session.layout().content();
        int pw = content.w() * unit, ph = content.h() * unit;
        session.ensureFirstTab(pw, ph);
        session.openPending(pw, ph);
        BrowserTab tab = session.active();
        if (tab == null) return;
        tab.resizeIfNeeded(pw, ph);
        tab.input().setViewport(content.x(), content.y(), content.w(), content.h(), unit);
        tab.input().updateWindowFocus();
        keepAutoscrollSane(tab.input());
        keepFindOnItsTab(tab);
        if (menuOpen && (menuKind == MenuKind.PAGE ? session.active() != menuTab : session.indexOf(menuTarget) < 0)) closeContextMenu();
        updateUrlBar(tab, (int) Math.floor(toShell(mouseX)), (int) Math.floor(toShell(mouseY)));

        MCEF.pump();
        PageQuery.tick();
        pollContextMenu(session.active());
        float scale = (float) (unit / guiScale());
        g.pose().pushMatrix();
        g.pose().scale(scale, scale);
        drawShell(g, session.layout(), tab, (int) Math.floor(toShell(mouseX)), (int) Math.floor(toShell(mouseY)), unit);
        g.pose().popMatrix();
        if (System.currentTimeMillis() < statusUntil) g.text(font, status, 4, height - 12, ERROR_COLOR, true);
    }

    /** Draws the shell; everything here is in shell units (the caller applied the pose scale), mouse included. */
    private void drawShell(GuiGraphicsExtractor g, ShellLayout layout, BrowserTab active, int mouseX, int mouseY, int unit) {
        Hit hover = drag == Drag.NONE ? layout.hit(mouseX, mouseY, session.activeIndex(), session.urlBar().visible(), zoomMenuOpen, findOpen) : Hit.NONE;
        Rect win = layout.window();
        g.fill(win.x(), win.y(), win.right(), win.bottom(), EDGE);
        g.fill(win.x() + 1, win.y() + 1, win.right() - 1, win.bottom() - 1, BG);

        // tab bar
        Rect bar = layout.tabBar();
        g.fill(bar.x(), bar.y(), bar.right(), bar.bottom(), TAB_BAR_BG);
        for (int i = 0; i < session.tabCount(); i++) drawTab(g, layout, i, hover);
        button(g, layout.newTabButton(), hover.part() == Part.NEW_TAB, Part.NEW_TAB, true, Icons.PLUS, false);
        button(g, layout.minimizeButton(), hover.part() == Part.MINIMIZE, Part.MINIMIZE, true, Icons.MINIMIZE, false);
        button(g, layout.maximizeButton(), hover.part() == Part.MAXIMIZE, Part.MAXIMIZE, true, session.maximized() ? Icons.RESTORE : Icons.MAXIMIZE, false);
        button(g, layout.closeButton(), hover.part() == Part.CLOSE, Part.CLOSE, true, Icons.CLOSE, true);

        // toolbar
        Rect tool = layout.toolbar();
        g.fill(tool.x(), tool.y(), tool.right(), tool.bottom(), TOOLBAR_BG);
        button(g, layout.back(), hover.part() == Part.BACK, Part.BACK, active.canGoBack(), Icons.BACK, false);
        button(g, layout.forward(), hover.part() == Part.FORWARD, Part.FORWARD, active.canGoForward(), Icons.FORWARD, false);
        button(g, layout.reload(), hover.part() == Part.RELOAD, Part.RELOAD, true, active.loading() ? Icons.CLOSE : Icons.RELOAD, false);
        Rect title = layout.title();
        if (session.urlBar().visible()) drawUrlField(g, layout);
        else g.text(font, fit(active.displayTitle(), title.w()), title.x(), title.y() + (title.h() - font.lineHeight) / 2, TEXT, false);
        Rect urlButton = layout.urlButton();
        button(g, urlButton, hover.part() == Part.URL_BUTTON, Part.URL_BUTTON, true, Icons.GLOBE, false);
        if (session.urlBar().pinned()) g.fill(urlButton.x() + 2, urlButton.bottom() - 2, urlButton.right() - 2, urlButton.bottom(), ACCENT);
        Rect zoomButton = layout.zoomButton();
        button(g, zoomButton, hover.part() == Part.ZOOM_BUTTON, Part.ZOOM_BUTTON, true, Icons.ZOOM, false);
        // like Chrome's magnifier in the address bar: marked while this page is not at 100%, and while the menu is open
        if (zoomMenuOpen || Math.abs(active.zoomLevel()) > 1e-6) g.fill(zoomButton.x() + 2, zoomButton.bottom() - 2, zoomButton.right() - 2, zoomButton.bottom(), ACCENT);
        if (active.loading()) { // indeterminate bar along the bottom of the toolbar
            int seg = Math.max(40, tool.w() / 4);
            int pos = (int) ((System.currentTimeMillis() % 1400) / 1400.0 * (tool.w() + seg)) - seg;
            int x0 = Math.max(tool.x(), tool.x() + pos), x1 = Math.min(tool.right(), tool.x() + pos + seg);
            if (x1 > x0) g.fill(x0, tool.bottom() - 2, x1, tool.bottom(), ACCENT);
        }

        // the Chromium view
        Rect c = layout.content();
        g.fill(c.x(), c.y(), c.right(), c.bottom(), PAGE_BASE);
        Identifier texture = active.browser().getRenderer().getTexture();
        if (texture != null) {
            MCEFRenderer r = active.browser().getRenderer();
            // The page is physical pixels: draw it 1:1 by undoing the shell scale. Drawing only the part of the texture
            // that fits the content rectangle clips a frame that is still the old size while the window is resized.
            int drawW = Math.min(r.getWidth(), c.w() * unit), drawH = Math.min(r.getHeight(), c.h() * unit);
            g.pose().pushMatrix();
            g.pose().scale(1F / unit, 1F / unit);
            g.blit(RenderPipelines.GUI_TEXTURED, texture, c.x() * unit, c.y() * unit,
                    0F, 0F, drawW, drawH, r.getWidth(), r.getHeight());
            g.pose().popMatrix();
        }

        if (active.errorText() != null) drawLoadError(g, c, active);
        if (findOpen) drawFindBar(g, layout, hover);
        if (zoomMenuOpen) drawZoomMenu(g, layout, active, hover);

        if (menuOpen) drawContextMenu(g, mouseX, mouseY);

        // resize grip in the frame corner
        int rx = win.right(), by = win.bottom();
        g.fill(rx - 3, by - 3, rx - 1, by - 1, TEXT_DIM);
        g.fill(rx - 6, by - 3, rx - 4, by - 1, TEXT_DIM);
        g.fill(rx - 3, by - 6, rx - 1, by - 4, TEXT_DIM);
    }

    private void drawUrlField(GuiGraphicsExtractor g, ShellLayout layout) {
        drawField(g, layout.urlField(), urlText, urlFocused, Component.translatable("integratedntwiki.urlbar.hint").getString());
    }

    /** A single-line text field in the shell: frame, selection, text (or the hint when empty) and a blinking caret. */
    private void drawField(GuiGraphicsExtractor g, Rect f, TextField field, boolean focused, String hint) {
        int inner = f.w() - 8;
        g.fill(f.x(), f.y(), f.right(), f.bottom(), focused ? ACCENT : EDGE);
        g.fill(f.x() + 1, f.y() + 1, f.right() - 1, f.bottom() - 1, FIELD_BG);
        String visible = field.view(metrics(), inner);
        int textX = f.x() + 4, textY = f.y() + (f.h() - font.lineHeight) / 2, first = field.first();
        if (field.text().isEmpty()) {
            if (!hint.isEmpty()) g.text(font, fit(hint, inner), textX, textY, TEXT_DIM, false);
        } else {
            if (focused && field.hasSelection()) {
                int from = Math.max(first, field.selectionStart()), to = Math.min(first + visible.length(), field.selectionEnd());
                if (to > from) {
                    int x0 = textX + font.width(field.text().substring(first, from)), x1 = textX + font.width(field.text().substring(first, to));
                    g.fill(x0, textY - 1, x1, textY + font.lineHeight, SELECTION);
                }
            }
            g.text(font, visible, textX, textY, TEXT, false);
        }
        if (focused && (System.currentTimeMillis() - caretBlinkStart) / CARET_BLINK_MILLIS % 2 == 0) {
            int caretX = textX + font.width(field.text().substring(first, field.caret()));
            g.fill(caretX, textY - 1, caretX + 1, textY + font.lineHeight, TEXT);
        }
    }

    private void drawFindBar(GuiGraphicsExtractor g, ShellLayout layout, Hit hover) {
        Rect f = layout.findBar();
        g.fill(f.x(), f.y(), f.right(), f.bottom(), EDGE);
        g.fill(f.x() + 1, f.y() + 1, f.right() - 1, f.bottom() - 1, TAB_ACTIVE);
        drawField(g, layout.findField(), findText, findFocused, Component.translatable("integratedntwiki.find.hint").getString());
        overlayButton(g, layout.findPrevious(), hover.part() == Part.FIND_PREVIOUS, Part.FIND_PREVIOUS, Icons.CHEVRON_UP);
        overlayButton(g, layout.findNext(), hover.part() == Part.FIND_NEXT, Part.FIND_NEXT, Icons.CHEVRON_DOWN);
        overlayButton(g, layout.findClose(), hover.part() == Part.FIND_CLOSE, Part.FIND_CLOSE, Icons.CLOSE);
    }

    private void drawZoomMenu(GuiGraphicsExtractor g, ShellLayout layout, BrowserTab tab, Hit hover) {
        Rect m = layout.zoomMenu();
        g.fill(m.x(), m.y(), m.right(), m.bottom(), EDGE);
        g.fill(m.x() + 1, m.y() + 1, m.right() - 1, m.bottom() - 1, TAB_ACTIVE);
        overlayButton(g, layout.zoomOut(), hover.part() == Part.ZOOM_OUT, Part.ZOOM_OUT, Icons.MINUS);
        overlayButton(g, layout.zoomIn(), hover.part() == Part.ZOOM_IN, Part.ZOOM_IN, Icons.PLUS);
        Rect v = layout.zoomValue();
        if (zoomEditing) {
            drawField(g, v, zoomText, true, "");
        } else {
            boolean over = hover.part() == Part.ZOOM_VALUE;
            if (over) g.fill(v.x(), v.y(), v.right(), v.bottom(), pressed != null && pressed.part() == Part.ZOOM_VALUE ? BUTTON_PRESSED : BUTTON_HOVER);
            String label = Zoom.label(tab.actualZoomLevel()); // what Chromium really has, not what was last asked for
            g.text(font, label, v.x() + (v.w() - font.width(label)) / 2, v.y() + (v.h() - font.lineHeight) / 2, TEXT, false);
        }
        Rect r = layout.zoomReset();
        boolean overReset = hover.part() == Part.ZOOM_RESET;
        g.fill(r.x(), r.y(), r.right(), r.bottom(), overReset ? (pressed != null && pressed.part() == Part.ZOOM_RESET ? BUTTON_PRESSED : BUTTON_HOVER) : TAB_IDLE);
        String reset = fit(Component.translatable("integratedntwiki.zoom.reset").getString(), r.w() - 4);
        g.text(font, reset, r.x() + (r.w() - font.width(reset)) / 2, r.y() + (r.h() - font.lineHeight) / 2, TEXT, false);
    }

    private void overlayButton(GuiGraphicsExtractor g, Rect r, boolean hovered, Part part, String[] glyph) {
        if (hovered) g.fill(r.x(), r.y(), r.right(), r.bottom(), pressed != null && pressed.part() == part ? BUTTON_PRESSED : BUTTON_HOVER);
        Icons.drawCentered(g, glyph, r.x(), r.y(), r.w(), r.h(), TEXT);
    }

    /** Hover reveal / auto-hide, and keeping the field in step with the tab's address while it is not being edited. */
    private void updateUrlBar(BrowserTab tab, int shellX, int shellY) {
        ShellLayout layout = session.layout();
        UrlBarState bar = session.urlBar();
        boolean inTopStrip = drag == Drag.NONE && layout.hoverZone().contains(shellX, shellY);
        boolean overBar = layout.title().contains(shellX, shellY) || layout.urlButton().contains(shellX, shellY)
                || layout.zoomButton().contains(shellX, shellY);
        bar.update(inTopStrip, overBar, urlFocused, System.currentTimeMillis());
        if (!bar.visible() && urlFocused) blurUrlField(true);
        if (!urlFocused && !tab.url().equals(syncedUrl)) {
            syncedUrl = tab.url();
            urlText.setText(syncedUrl);
        }
    }

    private void focusUrlField(boolean selectAll) {
        BrowserTab tab = session.active();
        findFocused = false; // one text field has the caret at a time
        zoomEditing = false;
        urlFocused = true;
        caretBlinkStart = System.currentTimeMillis();
        if (tab != null) {
            tab.input().cancelAutoscroll(); // before the page loses the keyboard
            tab.input().focusMinecraft(); // the browser must not also receive what is typed here
        }
        if (selectAll) urlText.selectAll();
    }

    /** Leaves the field, discarding unsent edits. {@code returnToPage} gives the keyboard back to the page. */
    private void blurUrlField(boolean returnToPage) {
        BrowserTab tab = session.active();
        urlFocused = false;
        draggingField = null;
        if (tab != null) {
            syncedUrl = tab.url();
            urlText.setText(syncedUrl);
            if (returnToPage) tab.input().focusBrowser();
        }
    }

    /** The globe button and the keybind: pin the bar open (focused) or close it. */
    private void toggleUrlBar() {
        if (session.urlBar().toggle()) {
            focusUrlField(true);
            ignoreCharsUntil = System.currentTimeMillis() + CHARS_AFTER_HOTKEY_IGNORED_MILLIS;
        } else if (urlFocused) {
            blurUrlField(true);
        }
    }

    /** Ctrl+L / Alt+D: show the bar if hidden and put the caret in it with the address selected. */
    private void revealUrlBar() {
        session.urlBar().reveal();
        focusUrlField(true);
        ignoreCharsUntil = System.currentTimeMillis() + CHARS_AFTER_HOTKEY_IGNORED_MILLIS;
    }

    private void clickUrlField(double shellX, boolean doubleClick) {
        boolean wasFocused = urlFocused;
        focusUrlField(false);
        if (!wasFocused || doubleClick) { // like a browser: the first click selects the whole address
            urlText.selectAll();
            draggingField = null;
        } else {
            urlText.setCaretFromX((int) shellX - (session.layout().urlField().x() + 4), false, metrics());
            draggingField = urlText;
        }
    }

    private void submitUrl(BrowserTab tab) {
        var url = UrlInput.toUrl(urlText.text());
        if (url.isEmpty()) return;
        if (!WikiUrls.isTopLevelAllowed(url.get())) { // stays open so it can be corrected
            showStatus(Component.translatable("integratedntwiki.urlbar.blocked").getString());
            return;
        }
        tab.browser().loadURL(url.get());
        session.urlBar().dismissTemporary(); // a temporary reveal ends; a pinned bar stays
        blurUrlField(true);
    }

    /** Esc in the field: first reverts unsent edits, then returns to the page. */
    private void escapeUrlField(BrowserTab tab) {
        if (!urlText.text().equals(tab.url())) {
            urlText.setText(tab.url());
            urlText.selectAll();
            return;
        }
        session.urlBar().dismissTemporary();
        blurUrlField(true);
    }

    private boolean urlKey(BrowserTab tab, KeyEvent event) {
        caretBlinkStart = System.currentTimeMillis();
        switch (event.key()) {
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> submitUrl(tab);
            case GLFW.GLFW_KEY_ESCAPE -> escapeUrlField(tab);
            default -> editKey(urlText, event);
        }
        return true; // while the field is focused nothing reaches the page
    }

    /** The text-editing keys every shell field shares (the Excel's Editing rows): caret, selection, delete, clipboard, undo/redo. */
    private void editKey(TextField field, KeyEvent event) {
        int key = event.key();
        boolean ctrl = (event.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0, shift = (event.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0;
        caretBlinkStart = System.currentTimeMillis();
        switch (key) {
            case GLFW.GLFW_KEY_LEFT -> field.left(shift, ctrl);
            case GLFW.GLFW_KEY_RIGHT -> field.right(shift, ctrl);
            case GLFW.GLFW_KEY_HOME -> field.home(shift);
            case GLFW.GLFW_KEY_END -> field.end(shift);
            case GLFW.GLFW_KEY_BACKSPACE -> field.backspace(ctrl);
            case GLFW.GLFW_KEY_DELETE -> {
                if (shift && !ctrl && field.hasSelection()) { // Shift+Delete: cut
                    ClipboardBridge.write(field.selectedText());
                    field.deleteSelection();
                } else {
                    field.delete(ctrl);
                }
            }
            case GLFW.GLFW_KEY_A -> {
                if (ctrl) field.selectAll();
            }
            case GLFW.GLFW_KEY_C -> {
                if (ctrl && field.hasSelection()) ClipboardBridge.write(field.selectedText());
            }
            case GLFW.GLFW_KEY_X -> {
                if (ctrl && field.hasSelection()) {
                    ClipboardBridge.write(field.selectedText());
                    field.deleteSelection();
                }
            }
            case GLFW.GLFW_KEY_V -> {
                if (ctrl) paste(field); // Ctrl+V and Ctrl+Shift+V: these fields only hold plain text anyway
            }
            case GLFW.GLFW_KEY_Z -> {
                if (ctrl) {
                    if (shift) field.redo();
                    else field.undo();
                }
            }
            case GLFW.GLFW_KEY_Y -> {
                if (ctrl) field.redo();
            }
            case GLFW.GLFW_KEY_INSERT -> {
                if (shift) paste(field);
                else if (ctrl && field.hasSelection()) ClipboardBridge.write(field.selectedText());
            }
            default -> { }
        }
    }

    /** Pastes the OS clipboard's text into a shell field (the address is trimmed). Says so when there is no text to paste. */
    private void paste(TextField field) {
        String clip = ClipboardBridge.read();
        if (clip == null || clip.isEmpty()) {
            MCEF.LOGGER.info("Paste into a shell field: the OS clipboard holds no text");
            showStatus(Component.translatable("integratedntwiki.clipboard.noText").getString());
            return;
        }
        field.insert(field == urlText ? clip.strip() : clip);
    }

    // ---- zoom (Chromium's own page zoom; kept per tab)

    private void zoomStep(int direction) {
        BrowserTab tab = session.active();
        if (tab != null) tab.setZoomLevel(Zoom.step(tab.zoomLevel(), direction));
    }

    private void zoomReset() {
        BrowserTab tab = session.active();
        if (tab != null) tab.setZoomLevel(0.0);
    }

    private void toggleZoomMenu() {
        if (zoomMenuOpen) {
            closeZoomMenu();
            return;
        }
        BrowserTab tab = session.active();
        if (tab != null) tab.input().cancelAutoscroll();
        zoomMenuOpen = true;
        zoomEditing = false;
    }

    private void closeZoomMenu() {
        zoomMenuOpen = false;
        draggingField = draggingField == zoomText ? null : draggingField;
        if (zoomEditing) {
            zoomEditing = false;
            returnKeyboardToPage();
        }
    }

    /** Clicking the percentage: it becomes a field with the current value selected. */
    private void beginZoomEdit() {
        BrowserTab tab = session.active();
        if (tab == null || zoomEditing) return;
        if (urlFocused) blurUrlField(false);
        findFocused = false;
        tab.input().cancelAutoscroll();
        zoomEditing = true;
        zoomText.setText(String.valueOf(Zoom.percent(tab.actualZoomLevel())));
        zoomText.selectAll();
        caretBlinkStart = System.currentTimeMillis();
        tab.input().focusMinecraft();
    }

    private void applyZoomText() {
        BrowserTab tab = session.active();
        OptionalInt percent = Zoom.parsePercent(zoomText.text());
        if (tab == null || percent.isEmpty()) {
            showStatus(Component.translatable("integratedntwiki.zoom.invalid").getString());
            return;
        }
        tab.setZoomLevel(Zoom.levelForPercent(percent.getAsInt()));
        zoomEditing = false;
        returnKeyboardToPage();
    }

    /** Gives the page the keyboard back unless one of the shell's text fields is still being edited. */
    private void returnKeyboardToPage() {
        BrowserTab tab = session.active();
        if (tab != null && !urlFocused && !findFocused && !zoomEditing) tab.input().focusBrowser();
    }

    private void wheelZoom(double scrollY) {
        if (wheelZoomAccumulator * scrollY < 0) wheelZoomAccumulator = 0; // direction changed
        wheelZoomAccumulator += scrollY;
        while (wheelZoomAccumulator >= 1) {
            zoomStep(1);
            wheelZoomAccumulator -= 1;
        }
        while (wheelZoomAccumulator <= -1) {
            zoomStep(-1);
            wheelZoomAccumulator += 1;
        }
    }

    // ---- find in page (Ctrl+F, Ctrl+G, Ctrl+Shift+G); Chromium does the searching and highlighting

    private void openFind(boolean searchNow) {
        BrowserTab tab = session.active();
        if (tab == null) return;
        closeZoomMenu();
        if (urlFocused) blurUrlField(false);
        findOpen = true;
        findTab = tab;
        focusFind();
        findText.selectAll(); // like Chrome: the previous search text is selected, typing replaces it
        if (searchNow && !findText.text().isEmpty()) runFind(true, false);
    }

    private void focusFind() {
        BrowserTab tab = session.active();
        urlFocused = false;
        zoomEditing = false;
        findFocused = true;
        caretBlinkStart = System.currentTimeMillis();
        if (tab != null) {
            tab.input().cancelAutoscroll();
            tab.input().focusMinecraft();
        }
    }

    private void closeFind() {
        if (!findOpen) return;
        findOpen = false;
        findFocused = false;
        draggingField = draggingField == findText ? null : draggingField;
        if (findTab != null && !findTab.browser().isClosed()) findTab.browser().stopFinding(false); // the current match stays selected, as in Chrome
        findTab = null;
        searchedText = "";
        returnKeyboardToPage();
    }

    /** The find bar belongs to the tab it was opened on: when that tab is no longer the active one, it goes away. */
    private void keepFindOnItsTab(BrowserTab active) {
        if (!findOpen || findTab == active) return;
        findOpen = false;
        findFocused = false;
        searchedText = "";
        if (findTab != null && !findTab.browser().isClosed()) findTab.browser().stopFinding(true);
        findTab = null;
    }

    private void runFind(boolean forward, boolean next) {
        BrowserTab tab = session.active();
        if (tab == null) return;
        String text = findText.text();
        if (text.isEmpty()) {
            tab.browser().stopFinding(true);
            searchedText = "";
            return;
        }
        // "find next" only continues a search that is for this very text; otherwise it is a new search
        tab.browser().find(text, forward, false, next && text.equals(searchedText));
        searchedText = text;
    }

    private void liveFind() {
        if (!findText.text().equals(searchedText)) runFind(true, false);
    }

    /** Ctrl+G / Ctrl+Shift+G: open the bar if it is closed (with the last text), then go to the next / previous match. */
    private void findStep(boolean forward) {
        if (!findOpen) openFind(false);
        runFind(forward, true);
    }

    private void clickFindField(double shellX, boolean doubleClick) {
        boolean was = findFocused;
        focusFind();
        if (!was || doubleClick) {
            findText.selectAll();
            draggingField = null;
        } else {
            findText.setCaretFromX((int) shellX - (session.layout().findField().x() + 4), false, metrics());
            draggingField = findText;
        }
    }

    private int fieldOrigin(TextField field) {
        ShellLayout layout = session.layout();
        Rect r = field == findText ? layout.findField() : field == zoomText ? layout.zoomValue() : layout.urlField();
        return r.x() + 4;
    }

    // ---- autoscroll upkeep

    /**
     * Autoscroll must never stay stuck: it ends when the game window loses focus, or when the pointer has left the
     * window with no button held (the page would otherwise keep scrolling at the last speed with nothing to stop it).
     */
    private void keepAutoscrollSane(BrowserInput input) {
        input.tick();
        if (!input.autoscrolling()) return;
        long window = GLFW.glfwGetCurrentContext();
        boolean hovered = GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_HOVERED) == GLFW.GLFW_TRUE;
        boolean held = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS
                || GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_MIDDLE) == GLFW.GLFW_PRESS
                || GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;
        if (!hovered && !held) input.cancelAutoscroll();
    }

    private void drawTab(GuiGraphicsExtractor g, ShellLayout layout, int i, Hit hover) {
        BrowserTab tab = session.tab(i);
        Rect t = layout.tab(i);
        boolean isActive = i == session.activeIndex();
        boolean hovered = hover.index() == i && (hover.part() == Part.TAB || hover.part() == Part.TAB_CLOSE);
        g.fill(t.x(), t.y(), t.right(), t.bottom(), isActive ? TAB_ACTIVE : hovered ? TAB_HOVER : TAB_IDLE);
        if (tab.loading()) g.fill(t.x(), t.bottom() - 2, t.right(), t.bottom(), ACCENT);
        if (tab.pinned()) g.fill(t.x(), t.y(), t.right(), t.y() + 2, PIN_COLOR); // pinned: a gold line along the top

        int textX = t.x() + 5;
        Identifier icon = Favicons.texture(tab.icon());
        if (icon != null && t.w() >= 28) { // a very narrow tab (many tabs) has no room for its icon
            g.blit(RenderPipelines.GUI_TEXTURED, icon, t.x() + 4, t.y() + 1, 0F, 0F, 16, 16, 16, 16);
            textX = t.x() + 4 + 16 + 4;
        }
        boolean closeShown = !tab.pinned() && layout.tabCloseVisible(i, session.activeIndex());
        int textRight = closeShown ? layout.tabClose(i).x() - 2 : t.right() - 4;
        if (textRight - textX > 8) g.text(font, fit(tab.displayTitle(), textRight - textX), textX, t.y() + (t.h() - font.lineHeight) / 2, TEXT, false);
        if (closeShown) {
            Rect cr = layout.tabClose(i);
            boolean over = hover.part() == Part.TAB_CLOSE && hover.index() == i;
            if (over) g.fill(cr.x(), cr.y(), cr.right(), cr.bottom(), pressed != null && pressed.equals(hover) ? BUTTON_PRESSED : BUTTON_HOVER);
            Icons.drawCentered(g, Icons.CLOSE, cr.x(), cr.y(), cr.w(), cr.h(), over ? TEXT : TEXT_DIM);
        }
    }

    private void button(GuiGraphicsExtractor g, Rect r, boolean hovered, Part part, boolean enabled, String[] glyph, boolean danger) {
        if (enabled && hovered) {
            boolean down = pressed != null && pressed.part() == part;
            g.fill(r.x(), r.y(), r.right(), r.bottom(), down ? BUTTON_PRESSED : danger ? CLOSE_HOVER : BUTTON_HOVER);
        }
        Icons.drawCentered(g, glyph, r.x(), r.y(), r.w(), r.h(), enabled ? TEXT : 0xFF555555);
    }

    /** Shortens text with "..." so it fits in {@code maxWidth} shell units. */
    private String fit(String text, int maxWidth) {
        if (maxWidth <= 0) return "";
        if (font.width(text) <= maxWidth) return text;
        String ellipsis = "...";
        int end = text.length();
        while (end > 0 && font.width(text.substring(0, end)) + font.width(ellipsis) > maxWidth) end--;
        return text.substring(0, end).stripTrailing() + ellipsis;
    }

    // ---- mouse: the shell and Chromium share the screen, whatever is under the pointer gets the event

    @Override
    public void mouseMoved(double guiX, double guiY) {
        BrowserTab tab = session.active();
        if (tab == null || drag != Drag.NONE || menuOpen) return;
        boolean inContent = hitAt(guiX, guiY).part() == Part.CONTENT;
        double x = toShell(guiX), y = toShell(guiY);
        BrowserInput input = tab.input();
        // During autoscroll the page keeps following the pointer wherever it goes: how far it is from where autoscroll
        // started is the scroll speed, and telling the page the pointer "left" would stall or cancel it.
        boolean following = overContent && input.autoscrolling();
        if (inContent || following) {
            if (!overContent) { // re-entering: Chromium only re-sends its cursor when it changes
                overContent = true;
                tab.browser().restoreCursor();
            }
            input.mouseMoved(x, y);
        } else if (overContent) {
            if (input.buttonsDown()) { // a drag that left the view keeps following the pointer
                input.mouseMoved(x, y);
            } else {
                overContent = false;
                input.mouseLeftView(x, y);
                tab.browser().getCursorChangeListener().onCursorChange(0); // arrow over the shell
            }
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        BrowserTab tab = session.active();
        if (tab == null) return true;
        double x = toShell(event.x()), y = toShell(event.y());
        int button = event.button();
        if (menuOpen && button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            closeContextMenu(); // a right-click while the menu is open closes it and goes on to the page: it opens the new menu
        } else if (menuOpen) { // the menu takes the click: an item runs on left click, anything else only closes it
            int item = menuItemAt(x, y);
            MenuItem chosen = item >= 0 ? menuItems.get(item) : null;
            if (chosen != null && chosen.separator()) return true; // a separator line is not a choice: the menu stays
            closeContextMenu();
            if (chosen != null && chosen.enabled() && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) chosen.action().run();
            return true;
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            rightClickX = (int) x;
            rightClickY = (int) y;
        }
        // Mouse buttons 4 and 5 (X1 / X2): Chromium's Back and Forward, wherever the pointer is.
        if (button == GLFW.GLFW_MOUSE_BUTTON_4 || button == GLFW.GLFW_MOUSE_BUTTON_5) {
            tab.input().cancelAutoscroll();
            closeZoomMenu();
            if (button == GLFW.GLFW_MOUSE_BUTTON_4) goBack(tab);
            else goForward(tab);
            return true;
        }
        Hit hit = hitAt(event.x(), event.y());
        // The zoom menu closes when the user clicks anywhere else (the zoom button toggles it itself).
        if (zoomMenuOpen && !isZoomPart(hit.part())) closeZoomMenu();
        // A click outside the page ends an autoscroll and does nothing else (inside the page Chromium ends it itself).
        if (hit.part() != Part.CONTENT && tab.input().autoscrolling()) {
            tab.input().cancelAutoscroll();
            return true;
        }
        // Right-click on a tab: the TAB menu, drawn and run by the shell. The click never reaches Chromium, so the page
        // menu cannot open from it, and a right-click in the page never gets here, so the tab menu cannot open from that.
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT && (hit.part() == Part.TAB || hit.part() == Part.TAB_CLOSE)) {
            openTabMenu(hit.index(), x, y);
            swallowRightRelease = true; // Chromium opens its menu on the right button's RELEASE: that release must not reach it
            return true;
        }
        if (hit.part() == Part.CONTENT) {
            if (urlFocused) blurUrlField(false);
            findFocused = false;
            tab.input().mousePressed(x, y, button, doubleClick);
            return true;
        }
        if (urlFocused && hit.part() != Part.URL_FIELD) blurUrlField(false);
        if (findFocused && hit.part() != Part.FIND_FIELD) findFocused = false;
        tab.input().focusMinecraft(); // clicking anything that is not the page takes the keyboard from it
        if (hit.part() == Part.NONE) return true;
        if (hit.part() == Part.URL_FIELD) {
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) clickUrlField(x, doubleClick);
            return true;
        }
        if (hit.part() == Part.FIND_FIELD) {
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) clickFindField(x, doubleClick);
            return true;
        }
        if (hit.part() == Part.ZOOM_VALUE && zoomEditing) { // a click inside the value being edited moves its caret
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                zoomText.setCaretFromX((int) x - fieldOrigin(zoomText), false, metrics());
                draggingField = zoomText;
            }
            return true;
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            // Double-click on the empty part of the tab strip toggles maximized / windowed, like Chrome.
            if (hit.part() == Part.DRAG && doubleClick && session.layout().tabBar().contains(x, y)) {
                session.toggleMaximize(shellW(), shellH());
                return true;
            }
            pressed = hit;
            switch (hit.part()) {
                case TAB -> { // like Chrome: the tab is selected on press, and can be dragged to a new position
                    session.select(hit.index());
                    pressed = null;
                    drag = Drag.TAB;
                    dragTab = hit.index();
                }
                case DRAG -> {
                    drag = Drag.MOVE;
                    pressShellX = (int) x;
                    pressShellY = (int) y;
                    grabX = (int) x - session.windowX();
                    grabY = (int) y - session.windowY();
                }
                case EDGE_RIGHT -> drag = Drag.RIGHT;
                case EDGE_BOTTOM -> drag = Drag.BOTTOM;
                case CORNER -> drag = Drag.CORNER;
                default -> { }
            }
        } else if (button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE && hit.part() == Part.TAB) {
            closeTabAt(hit.index());
        }
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) draggingField = null;
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT && (pressed != null || drag != Drag.NONE)) {
            Hit down = pressed;
            pressed = null;
            drag = Drag.NONE;
            // a button fires only when released over the control it was pressed on
            if (down != null && down.equals(hitAt(event.x(), event.y()))) activate(down);
            return true;
        }
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT && swallowRightRelease) {
            swallowRightRelease = false;
            return true;
        }
        BrowserTab tab = session.active();
        if (tab != null) tab.input().mouseReleased(toShell(event.x()), toShell(event.y()), event.button());
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        double x = toShell(event.x()), y = toShell(event.y());
        if (draggingField != null) {
            draggingField.setCaretFromX((int) x - fieldOrigin(draggingField), true, metrics());
            return true;
        }
        switch (drag) {
            case MOVE -> {
                if (session.maximized()) {
                    // Pulling a maximized window by its tab strip picks it up at its previous size, under the pointer.
                    if (Math.abs(x - pressShellX) < 4 && Math.abs(y - pressShellY) < 4) return true;
                    session.restoreUnderPointer((int) x, (int) y, shellW(), shellH());
                    grabX = (int) x - session.windowX();
                    grabY = (int) y - session.windowY();
                }
                session.moveTo((int) x - grabX, (int) y - grabY, shellW(), shellH());
            }
            case RIGHT -> session.resizeTo((int) x - session.windowX() + 1, session.windowH(), shellW(), shellH());
            case BOTTOM -> session.resizeTo(session.windowW(), (int) y - session.windowY() + 1, shellW(), shellH());
            case CORNER -> session.resizeTo((int) x - session.windowX() + 1, (int) y - session.windowY() + 1, shellW(), shellH());
            case TAB -> {
                ShellLayout l = session.layout();
                int target = (int) Math.floor((x - l.tab(0).x()) / (double) l.tabWidth());
                target = Math.max(0, Math.min(target, session.tabCount() - 1));
                if (dragTab >= 0 && dragTab < session.tabCount() && target != dragTab) {
                    BrowserTab dragged = session.tab(dragTab);
                    session.moveTab(dragTab, target); // pinned and unpinned tabs keep to their own group
                    dragTab = session.indexOf(dragged);
                }
            }
            case NONE -> {
                BrowserTab tab = session.active();
                if (pressed == null && tab != null) tab.input().mouseDragged(x, y);
            }
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double guiX, double guiY, double scrollX, double scrollY) {
        if (menuOpen) {
            closeContextMenu();
            return true;
        }
        Part over = hitAt(guiX, guiY).part();
        if (isOverlay(over)) return true; // the zoom menu and find bar swallow the wheel
        // Ctrl+scroll over the shell (not the page, where it zooms the page) resizes the shell itself
        if (over != Part.CONTENT && over != Part.NONE && scrollY != 0 && ctrlDown()) {
            adjustShellScale(scrollY > 0 ? 1 : -1);
            return true;
        }
        BrowserTab tab = session.active();
        if (tab == null) return true;
        if (over == Part.CONTENT && scrollY != 0 && ctrlDown()) { // Ctrl+wheel: Chromium page zoom, one step per notch
            wheelZoom(scrollY);
            return true;
        }
        tab.input().mouseScrolled(toShell(guiX), toShell(guiY), scrollX, scrollY);
        return true;
    }

    // ---- shell actions

    private void activate(Hit hit) {
        BrowserTab tab = session.active();
        switch (hit.part()) {
            case TAB -> session.select(hit.index());
            case TAB_CLOSE -> { // a pinned tab shows no close button: the click only selects it
                if (hit.index() < session.tabCount() && session.tab(hit.index()).pinned()) session.select(hit.index());
                else closeTabAt(hit.index());
            }
            case NEW_TAB -> openNewTab(false);
            case URL_BUTTON -> toggleUrlBar();
            // the zoom menu must not cost the page the keyboard: it goes straight back unless the value is being typed
            case ZOOM_BUTTON -> {
                toggleZoomMenu();
                returnKeyboardToPage();
            }
            case ZOOM_OUT -> {
                zoomStep(-1);
                returnKeyboardToPage();
            }
            case ZOOM_IN -> {
                zoomStep(1);
                returnKeyboardToPage();
            }
            case ZOOM_RESET -> {
                zoomReset();
                returnKeyboardToPage();
            }
            case ZOOM_VALUE -> beginZoomEdit();
            case FIND_PREVIOUS -> runFind(false, true);
            case FIND_NEXT -> runFind(true, true);
            case FIND_CLOSE -> closeFind();
            case MINIMIZE -> onClose();
            case MAXIMIZE -> session.toggleMaximize(shellW(), shellH()); // same as double-clicking the empty tab strip
            case CLOSE -> closeWindow();
            case BACK -> goBack(tab);
            case FORWARD -> goForward(tab);
            case RELOAD -> {
                if (tab != null) {
                    if (tab.loading()) tab.browser().stopLoad();
                    else tab.browser().reload();
                }
            }
            default -> { }
        }
    }

    private void goBack(BrowserTab tab) {
        if (tab != null && tab.canGoBack()) tab.browser().goBack();
    }

    private void goForward(BrowserTab tab) {
        if (tab != null && tab.canGoForward()) tab.browser().goForward();
    }

    // ---- context menu

    private void closeContextMenu() {
        menuOpen = false;
        menuItems = java.util.List.of();
        menuTab = null;
        menuTarget = null;
    }

    private int menuItemAt(double x, double y) {
        if (x < menuX || x >= menuX + menuW || y < menuY + 2 || y >= menuY + menuH - 2) return -1;
        int i = (int) ((y - menuY - 2) / MENU_ITEM_H);
        return i >= 0 && i < menuItems.size() ? i : -1;
    }

    private static void copyToClipboard(String text) {
        ClipboardBridge.write(text);
    }

    private void openInNewTab(String url) {
        Rect c = session.layout().content();
        if (session.openTab(url, false, c.w() * session.unit(), c.h() * session.unit()) == null) tabLimitMessage();
    }

    private static MenuItem item(String key, boolean enabled, Runnable action) {
        return item(key, "", enabled, action);
    }

    private static MenuItem item(String key, String shortcut, boolean enabled, Runnable action) {
        return new MenuItem(Component.translatable("integratedntwiki.menu." + key).getString(), shortcut, enabled, action);
    }

    private static MenuItem separator() {
        return new MenuItem(null, "", false, null);
    }

    /** Sizes the menu to its rows and shows it at a shell position, kept inside the screen. */
    private void showMenu(java.util.List<MenuItem> items, int atX, int atY) {
        int w = 0;
        for (MenuItem it : items) {
            if (it.separator()) continue;
            w = Math.max(w, font.width(it.label()) + (it.shortcut().isEmpty() ? 0 : 18 + font.width(it.shortcut())));
        }
        menuItems = items;
        menuW = w + 14;
        menuH = items.size() * MENU_ITEM_H + 4;
        menuX = Math.max(0, Math.min(atX, shellW() - menuW));
        menuY = Math.max(0, Math.min(atY, shellH() - menuH));
        menuOpen = true;
        closeZoomMenu();
    }

    /** Minecraft's own full screen (what F11 does), including the option so it is remembered. */
    private void toggleFullScreen() {
        minecraft.getWindow().toggleFullScreen();
        minecraft.options.fullscreen().set(minecraft.getWindow().isFullscreen());
    }

    private void reloadAllTabs() {
        for (int i = 0; i < session.tabCount(); i++) session.tab(i).browser().reload();
    }

    /** Ctrl+Shift+T / "Reopen last closed tab": one implementation for both. */
    private void reopenClosedTab() {
        Rect c = session.layout().content();
        leaveFields();
        if (session.reopenClosedTab(c.w() * session.unit(), c.h() * session.unit()) == null) {
            if (session.tabLimitReached()) tabLimitMessage();
            else showStatus(Component.translatable("integratedntwiki.tabs.noneClosed").getString());
        }
    }

    /** The menu of a tab (right-click on it): the shell's own, never Chromium's. Acts on the right-clicked tab. */
    private void openTabMenu(int index, double shellX, double shellY) {
        if (index < 0 || index >= session.tabCount()) return;
        BrowserTab target = session.tab(index);
        BrowserContextMenu.take(); // a page-menu request still waiting must not pop up over this menu
        leaveFields();
        Rect c = session.layout().content();
        int pw = c.w() * session.unit(), ph = c.h() * session.unit();
        boolean limit = session.tabLimitReached();
        String url = target.url();
        boolean hasUrl = url != null && !url.isBlank();
        java.util.List<MenuItem> items = new java.util.ArrayList<>();
        items.add(item("tabNew", "Ctrl+T", !limit, () -> openNewTab(false)));
        items.add(item("reload", "Ctrl+R", true, () -> target.browser().reload()));
        items.add(item("reloadAll", "", true, this::reloadAllTabs));
        items.add(item("copyPageUrl", "", hasUrl, () -> copyToClipboard(target.url())));
        items.add(separator());
        items.add(item("duplicateTab", "", !limit && hasUrl && WikiUrls.isTopLevelAllowed(url), () -> {
            leaveFields();
            if (session.duplicateTab(target, pw, ph) == null) tabLimitMessage();
        }));
        items.add(item(target.pinned() ? "unpinTab" : "pinTab", "", true, () -> session.setPinned(target, !target.pinned())));
        items.add(separator());
        items.add(item("closeTab", "Ctrl+W", true, () -> {
            leaveFields();
            closeTabAt(session.indexOf(target));
        }));
        items.add(item("closeOtherTabs", "", session.countOthers(target) > 0, () -> {
            leaveFields();
            session.closeOtherTabs(target);
        }));
        items.add(item("closeTabsRight", "", session.countToRight(target) > 0, () -> {
            leaveFields();
            session.closeTabsToRight(target);
        }));
        items.add(item("closeDuplicateTabs", "", session.countDuplicates(target) > 0, () -> {
            leaveFields();
            session.closeDuplicateTabs(target);
        }));
        items.add(separator());
        items.add(item("reopenClosedTab", "Ctrl+Shift+T", session.hasClosedTabs(), this::reopenClosedTab));
        menuKind = MenuKind.TAB;
        menuTab = null;
        menuTarget = target;
        showMenu(items, (int) shellX, (int) shellY);
    }

    /** Builds the menu Chromium asked for (about a link, an image, a selection, an editable field or the page). */
    private void pollContextMenu(BrowserTab tab) {
        BrowserContextMenu.Request r = BrowserContextMenu.take();
        if (r == null || tab == null || r.browser() != tab.browser() || drag != Drag.NONE) return;
        if (menuOpen && menuKind == MenuKind.TAB) return; // a tab menu is open: a page-menu request must not replace it
        org.cef.browser.CefFrame frame = r.frame() != null ? r.frame() : tab.browser().getFocusedFrame();
        java.util.List<MenuItem> items = new java.util.ArrayList<>();
        boolean hasSelection = !r.selection().isBlank();
        int flags = r.editFlags();
        if (r.linkUrl() != null) {
            String link = r.linkUrl();
            items.add(item("openLinkTab", WikiUrls.isTopLevelAllowed(link), () -> openInNewTab(link)));
            items.add(item("copyLink", true, () -> copyToClipboard(link)));
        }
        if (r.imageUrl() != null) {
            String image = r.imageUrl();
            items.add(item("openImageTab", WikiUrls.isTopLevelAllowed(image), () -> openInNewTab(image)));
            items.add(item("copyImageUrl", true, () -> copyToClipboard(image)));
        }
        if (r.editable()) {
            items.add(item("cut", "Ctrl+X", (flags & org.cef.callback.CefContextMenuParams.EditStateFlags.CM_EDITFLAG_CAN_CUT) != 0, () -> {
                ClipboardBridge.copy(tab.browser(), true); // read through the page, so it lands on the OS clipboard
                returnKeyboardToPage();
            }));
        }
        if (r.editable() || hasSelection) {
            boolean canCopy = r.editable() ? (flags & org.cef.callback.CefContextMenuParams.EditStateFlags.CM_EDITFLAG_CAN_COPY) != 0 : true;
            String selected = r.selection();
            items.add(item("copy", "Ctrl+C", canCopy, () -> {
                // Chromium told us the selection when the menu opened: put exactly that on the OS clipboard; if it did not, ask the page
                if (!selected.isBlank()) copyToClipboard(selected);
                else ClipboardBridge.copy(tab.browser(), false);
                returnKeyboardToPage();
            }));
        }
        if (r.editable()) {
            items.add(item("paste", "Ctrl+V", (flags & org.cef.callback.CefContextMenuParams.EditStateFlags.CM_EDITFLAG_CAN_PASTE) != 0, () -> { frame.paste(); returnKeyboardToPage(); }));
        }
        boolean plainPage = r.linkUrl() == null && r.imageUrl() == null && !r.editable() && !hasSelection;
        if (plainPage) { // the page menu: Back, Forward, Reload, full screen, Copy address
            items.add(item("back", "Alt+Left", tab.canGoBack(), () -> goBack(tab)));
            items.add(item("forward", "Shift+Backspace", tab.canGoForward(), () -> goForward(tab)));
            items.add(item("reload", "F5", true, () -> tab.browser().reload()));
            items.add(separator());
            items.add(item(minecraft.getWindow().isFullscreen() ? "fullscreenExit" : "fullscreenEnter", "F11", true, this::toggleFullScreen));
            items.add(item("copyAddress", "", tab.url() != null && !tab.url().isBlank(), () -> copyToClipboard(tab.url())));
            items.add(separator());
        }
        if (r.editable() || r.linkUrl() == null && r.imageUrl() == null) {
            items.add(item("selectAll", "Ctrl+A", true, () -> {
                frame.executeJavaScript("document.execCommand('selectAll')", frame.getURL(), 0);
                returnKeyboardToPage();
            }));
        }
        if (items.isEmpty()) return;
        menuKind = MenuKind.PAGE;
        menuTarget = null;
        menuTab = tab;
        showMenu(items, rightClickX, rightClickY);
    }

    private void drawContextMenu(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        g.fill(menuX, menuY, menuX + menuW, menuY + menuH, EDGE);
        g.fill(menuX + 1, menuY + 1, menuX + menuW - 1, menuY + menuH - 1, TAB_ACTIVE);
        int hovered = menuItemAt(mouseX, mouseY);
        for (int i = 0; i < menuItems.size(); i++) {
            MenuItem it = menuItems.get(i);
            int y = menuY + 2 + i * MENU_ITEM_H;
            if (it.separator()) {
                g.fill(menuX + 4, y + MENU_ITEM_H / 2, menuX + menuW - 4, y + MENU_ITEM_H / 2 + 1, EDGE);
                continue;
            }
            if (i == hovered && it.enabled()) g.fill(menuX + 1, y, menuX + menuW - 1, y + MENU_ITEM_H, BUTTON_HOVER);
            int textY = y + (MENU_ITEM_H - font.lineHeight) / 2 + 1;
            g.text(font, it.label(), menuX + 7, textY, it.enabled() ? TEXT : TEXT_DIM, false);
            if (!it.shortcut().isEmpty()) g.text(font, it.shortcut(), menuX + menuW - 7 - font.width(it.shortcut()), textY, TEXT_DIM, false);
        }
    }

    /** The wiki could not be loaded: the error replaces the page area (Chromium's own error page is a navigation we do not control). */
    private void drawLoadError(GuiGraphicsExtractor g, Rect c, BrowserTab tab) {
        g.fill(c.x(), c.y(), c.right(), c.bottom(), PAGE_BASE);
        int cx = c.x() + c.w() / 2, y = c.y() + Math.max(10, c.h() / 4), maxW = Math.max(40, c.w() - 24);
        g.centeredText(font, Component.translatable("integratedntwiki.error.title"), cx, y, ERROR_COLOR);
        y += 16;
        // why it failed, in plain words, then what to do about it (wrapped to the window)
        String why = Component.translatable("integratedntwiki.error.why." + LoadErrors.reason(tab.errorText())).getString();
        StringBuilder row = new StringBuilder();
        for (String word : why.split(" ")) {
            if (row.length() > 0 && font.width(row + " " + word) > maxW) {
                g.centeredText(font, Component.literal(row.toString()), cx, y, TEXT);
                y += 11;
                row.setLength(0);
            }
            if (row.length() > 0) row.append(' ');
            row.append(word);
        }
        if (row.length() > 0) {
            g.centeredText(font, Component.literal(row.toString()), cx, y, TEXT);
            y += 11;
        }
        y += 6;
        if (tab.errorUrl() != null) g.centeredText(font, Component.literal(fit(tab.errorUrl(), maxW)), cx, y, TEXT_DIM);
        y += 12;
        g.centeredText(font, Component.literal(fit(tab.errorText(), maxW)), cx, y, TEXT_DIM); // the technical code, for reports
        y += 16;
        g.centeredText(font, Component.translatable("integratedntwiki.error.retry"), cx, y, TEXT_DIM);
    }

    private void closeTabAt(int index) {
        if (session.closeTab(index)) closeWindow();
    }

    /** Ctrl+T / the + button. Like Chrome, the keyboard shortcut also puts the caret in the address bar. */
    private void openNewTab(boolean focusAddress) {
        Rect c = session.layout().content();
        leaveFields();
        BrowserTab opened = session.openTab(WikiUrls.HOME_URL, true, c.w() * session.unit(), c.h() * session.unit());
        if (opened == null) tabLimitMessage();
        else if (focusAddress) revealUrlBar();
    }

    private void tabLimitMessage() {
        showStatus(Component.translatable("integratedntwiki.tabs.limit", String.valueOf(BrowserSession.maxTabs())).getString());
    }

    /** Before the active tab changes by keyboard: the shell's own fields and menus do not follow into the next tab. */
    private void leaveFields() {
        closeZoomMenu();
        if (urlFocused) blurUrlField(false);
        findFocused = false;
    }

    /** Close button / last tab closed: the session ends and every Chromium browser is released. */
    private void closeWindow() {
        disposed = true;
        session.dispose();
        onClose();
    }

    // ---- keyboard

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        BrowserTab tab = session.active();
        if (menuOpen) { // any key closes the context menu; Esc is only that
            closeContextMenu();
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                consumedKeys.add(key);
                return true;
            }
        }
        // Test hooks (Ctrl+Shift+F1..F9) and Esc belong to this screen and are never sent to the page.
        int mods = GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SHIFT;
        int test = key - GLFW.GLFW_KEY_F1;
        if (tab != null && (event.modifiers() & mods) == mods && test >= 0 && test < BOUNDARY_TESTS.length) {
            tab.browser().loadURL(BOUNDARY_TESTS[test]);
            return true;
        }
        // Shell shortcuts belong to Minecraft even while the page has the keyboard (never sent to the page).
        if (WikiKeys.pressed(WikiKeys.TOGGLE_URL_BAR, event)) {
            consumedKeys.add(key);
            toggleUrlBar();
            return true;
        }
        if (tab == null) return key == GLFW.GLFW_KEY_ESCAPE && escapeNoTab();
        // Browser shortcuts (the Chromium controls table): handled here, once, whoever has the keyboard.
        Shortcuts.Match shortcut = Shortcuts.match(key, event.modifiers(), BrowserSession.maxTabs());
        if (shortcut != null) {
            consumedKeys.add(key);
            runShortcut(shortcut, tab);
            return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            consumedKeys.add(key);
            escape(tab);
            return true;
        }
        if (zoomEditing) {
            caretBlinkStart = System.currentTimeMillis();
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) applyZoomText();
            else editKey(zoomText, event);
            return true;
        }
        if (findFocused) {
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                runFind((event.modifiers() & GLFW.GLFW_MOD_SHIFT) == 0, true); // Enter: next match, Shift+Enter: previous
                caretBlinkStart = System.currentTimeMillis();
            } else {
                editKey(findText, event);
                liveFind(); // Chrome searches as you type
            }
            return true;
        }
        if (urlFocused) return urlKey(tab, event);
        if (tab.input().owner() == BrowserInput.Owner.BROWSER) {
            // Copy / cut go through the clipboard bridge (the page's selection -> the OS clipboard), not to Chromium as keys.
            PageKeys.Clip clip = PageKeys.clip(key, event.modifiers());
            if (clip != null) {
                consumedKeys.add(key);
                ClipboardBridge.copy(tab.browser(), clip == PageKeys.Clip.CUT);
                return true;
            }
            // Shift+Backspace is "Forward" unless a text field has the keyboard (there it is Backspace): the key still goes
            // to the page, and the page says whether a field is focused.
            if (PageKeys.forwardChord(key, event.modifiers())) {
                boolean handled = tab.input().keyPressed(event);
                PageQuery.ask(tab.browser(), null, FIELD_PROBE, answer -> {
                    if ("nav".equals(answer) && session.active() == tab) goForward(tab);
                }, null);
                return handled;
            }
        }
        return tab.input().keyPressed(event);
    }

    /** JavaScript for the page: "edit" when a text field or editable area has the keyboard, else "nav". */
    private static final String FIELD_PROBE = "var a=document.activeElement;if(!a)return 'nav';var t=a.tagName;"
            + "if(t==='TEXTAREA'||t==='SELECT')return 'edit';"
            + "if(t==='INPUT'){var y=(a.type||'text').toLowerCase();"
            + "return ['button','checkbox','radio','submit','reset','image','file','range','color'].indexOf(y)>=0?'nav':'edit';}"
            + "return a.isContentEditable?'edit':'nav';";

    private boolean escapeNoTab() {
        onClose();
        return true;
    }

    /**
     * Esc, in the order Chromium's own "cancel the current operation" would apply it. One thing handles it, nothing else
     * sees it: autoscroll, the zoom field/menu, the find bar, the address bar, a drag in the page, a page still loading
     * (Chromium's Stop) and finally, when there is nothing left to cancel, minimizing the window.
     */
    private void escape(BrowserTab tab) {
        if (tab.input().autoscrolling()) {
            tab.input().cancelAutoscroll();
        } else if (zoomEditing) {
            zoomEditing = false;
            returnKeyboardToPage();
        } else if (zoomMenuOpen) {
            closeZoomMenu();
        } else if (findOpen) {
            closeFind();
        } else if (urlFocused) {
            escapeUrlField(tab);
        } else if (tab.browser().getDragContext().isDragging()) {
            tab.browser().cancelDrag();
        } else if (tab.loading()) {
            tab.browser().stopLoad();
        } else {
            onClose(); // minimize: the tabs stay alive in the session
        }
    }

    private void runShortcut(Shortcuts.Match shortcut, BrowserTab tab) {
        switch (shortcut.action()) {
            case NEW_TAB -> openNewTab(true);
            case REOPEN_TAB -> reopenClosedTab();
            case CLOSE_TAB -> {
                leaveFields();
                closeTabAt(session.activeIndex());
            }
            case CLOSE_WINDOW -> closeWindow();
            case NEXT_TAB -> {
                leaveFields();
                session.nextTab();
            }
            case PREVIOUS_TAB -> {
                leaveFields();
                session.previousTab();
            }
            case SELECT_TAB -> {
                leaveFields();
                session.selectNumber(shortcut.tab());
            }
            case LAST_TAB -> {
                leaveFields();
                session.lastTab();
            }
            case BACK -> {
                closeZoomMenu();
                goBack(tab);
            }
            case FORWARD -> {
                closeZoomMenu();
                goForward(tab);
            }
            case HOME -> {
                closeZoomMenu();
                tab.browser().loadURL(WikiUrls.HOME_URL);
            }
            case FOCUS_ADDRESS_BAR -> revealUrlBar(); // Chromium's "focus address bar"
            case CYCLE_PANES -> cyclePanes(tab, 1);
            case CYCLE_PANES_REVERSE -> cyclePanes(tab, -1);
            case RELOAD -> {
                closeZoomMenu();
                tab.browser().reload();
            }
            case HARD_RELOAD -> {
                closeZoomMenu();
                tab.browser().reloadIgnoreCache();
            }
            case FIND -> openFind(true);
            case FIND_NEXT -> findStep(true);
            case FIND_PREVIOUS -> findStep(false);
            case ZOOM_IN -> zoomStep(1);
            case ZOOM_OUT -> zoomStep(-1);
            case ZOOM_RESET -> zoomReset();
        }
    }

    /**
     * F6 / Shift+F6: moves the keyboard between the browser's panes: the page, the address bar (shown if hidden) and the
     * find bar while it is open.
     */
    private void cyclePanes(BrowserTab tab, int direction) {
        int panes = findOpen ? 3 : 2; // 0 page, 1 address bar, 2 find bar
        int current = urlFocused ? 1 : findFocused ? 2 : 0;
        int next = ((current + direction) % panes + panes) % panes;
        switch (next) {
            case 0 -> {
                if (urlFocused) {
                    session.urlBar().dismissTemporary();
                    blurUrlField(true);
                }
                findFocused = false;
                closeZoomMenu();
                tab.input().focusBrowser();
            }
            case 1 -> revealUrlBar();
            default -> {
                if (urlFocused) blurUrlField(false);
                focusFind();
                findText.selectAll();
            }
        }
    }

    @Override
    public boolean keyReleased(KeyEvent event) {
        if (consumedKeys.remove(event.key())) return true; // its press was handled here; the page never saw it
        BrowserTab tab = session.active();
        return tab != null && tab.input().keyReleased(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (zoomEditing) {
            int cp = event.codepoint();
            if ((cp >= '0' && cp <= '9') || cp == '%') { // a zoom is a number
                zoomText.insert(new String(Character.toChars(cp)));
                caretBlinkStart = System.currentTimeMillis();
            }
            return true;
        }
        if (findFocused) {
            findText.insert(new String(Character.toChars(event.codepoint())));
            caretBlinkStart = System.currentTimeMillis();
            liveFind();
            return true;
        }
        if (urlFocused) {
            // the hotkey that opened the field may also deliver its character (Alt+U on some systems)
            if (System.currentTimeMillis() >= ignoreCharsUntil) {
                urlText.insert(new String(Character.toChars(event.codepoint())));
                caretBlinkStart = System.currentTimeMillis();
            }
            return true;
        }
        BrowserTab tab = session.active();
        return tab != null && tab.input().charTyped(event);
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    /** Runs whenever the screen is replaced or closed. The browsers stay in the session unless it was disposed. */
    @Override
    public void removed() {
        urlFocused = false;
        draggingField = null;
        BrowserTab shown = session.active();
        if (shown != null) shown.input().cancelAutoscroll(); // nothing would end it while this screen receives no input
        if (findOpen && findTab != null && !findTab.browser().isClosed()) findTab.browser().stopFinding(false);
        findOpen = findFocused = zoomMenuOpen = zoomEditing = false;
        if (!disposed) {
            session.urlBar().dismissTemporary(); // a peeking bar does not survive minimizing; a pinned one does
            session.onScreenHidden();
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
