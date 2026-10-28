package com.levviata.integratedntwiki.mcef;

import com.levviata.integratedntwiki.WikiLog;

import com.levviata.integratedntwiki.browser.UrlInput;
import com.levviata.integratedntwiki.browser.WikiUrls;
import com.levviata.integratedntwiki.client.WikiKeys;
import com.levviata.integratedntwiki.config.MarkerStorage;
import com.levviata.integratedntwiki.config.WikiConfig;
import com.levviata.integratedntwiki.shell.BrowserSession;
import com.levviata.integratedntwiki.shell.BrowserTab;
import com.levviata.integratedntwiki.shell.ControlList;
import com.levviata.integratedntwiki.shell.ControlSource;
import com.levviata.integratedntwiki.shell.KeyRebind;
import com.levviata.integratedntwiki.shell.Favicons;
import com.levviata.integratedntwiki.shell.Icons;
import com.levviata.integratedntwiki.shell.LoadErrors;
import com.levviata.integratedntwiki.shell.Marker;
import com.levviata.integratedntwiki.shell.MarkerCodec;
import com.levviata.integratedntwiki.shell.MarkerScripts;
import com.levviata.integratedntwiki.shell.MarkerStore;
import com.levviata.integratedntwiki.shell.PageKeys;
import com.levviata.integratedntwiki.shell.Tooltips;
import com.levviata.integratedntwiki.shell.ScrollBar;
import com.levviata.integratedntwiki.shell.ShellLayout;
import com.levviata.integratedntwiki.shell.ShellLayout.Hit;
import com.levviata.integratedntwiki.shell.ShellLayout.Part;
import com.levviata.integratedntwiki.shell.ShellLayout.Rect;
import com.levviata.integratedntwiki.shell.ShellScale;
import com.levviata.integratedntwiki.shell.Shortcuts;
import com.levviata.integratedntwiki.shell.TextField;
import com.levviata.integratedntwiki.shell.UrlBarState;
import com.levviata.integratedntwiki.shell.desktop.Box;
import com.levviata.integratedntwiki.desktop.DesktopShell;
import com.levviata.integratedntwiki.browser.ExternalBrowser;
import com.levviata.integratedntwiki.shell.desktop.SnapEngine;
import com.levviata.integratedntwiki.shell.desktop.SnapIntent;
import com.levviata.integratedntwiki.shell.desktop.SnapSettings;
import com.levviata.integratedntwiki.shell.desktop.SnapZone;
import com.levviata.integratedntwiki.shell.WindowResize;
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
import org.cef.misc.CefCursorType;
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
            FIELD_BG = 0xFF161616, SELECTION = 0xAA3A6EA5, PIN_COLOR = 0xFFD8A83A, WARN_COLOR = 0xFFFF8A5C;
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

    private enum Drag { NONE, MOVE, RESIZE, TAB }

    private final Screen parent;
    private BrowserSession session;
    private boolean shown, disposed, overContent;
    private Drag drag = Drag.NONE;
    /** Index of the tab being dragged to a new position (Drag.TAB). */
    private int dragTab = -1;
    /**
     * A resize in progress (Drag.RESIZE): which edges follow the pointer ({@link WindowResize} bits), the window as it was when the
     * button went down, and where the pointer was then (shell units). The window is resized by the pointer's movement since then, so
     * the opposite edges never move and pressing a pixel or two inside the frame does not make the window jump.
     */
    private int resizeEdges, resizePressX, resizePressY;
    private Box resizeStart;
    /** The edges whose resize cursor is showing (0: none), so the cursor is only set when it changes. */
    private int cursorEdges;

    // Context menu (Phase 8): Chromium says when and about what (BrowserContextMenu); the shell draws it.
    /** A menu row. {@code label == null} is a separator line. {@code shortcut} is shown right-aligned, as a hint. */
    private record MenuItem(String label, String shortcut, boolean enabled, Runnable action) {
        boolean separator() { return label == null; }
    }
    /** Two different menus: the page's (Chromium says what was clicked) and a tab's (the shell's own). They never share state. */
    private enum MenuKind { PAGE, TAB, MARKER }
    private MenuKind menuKind = MenuKind.PAGE;
    /** The tab a TAB menu acts on (the right-clicked tab, not necessarily the active one). */
    private BrowserTab menuTarget;
    /** The right button went down on the shell (tab menu): its release is not forwarded to the page. */
    private boolean swallowRightRelease;
    private static final int MENU_ITEM_H = 12;
    private boolean menuOpen;
    /** A scroll bar being dragged with the left button (its list, and where on the thumb it was grabbed). */
    private enum BarDrag { NONE, MENU, MARKERS, KEYS }
    private BarDrag barDrag = BarDrag.NONE;
    private int barGrab;
    /** First row shown and rows shown of a menu longer than the shell is tall. */
    private int menuScroll, menuRows;
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
    private int statusColor = ERROR_COLOR;

    // Controls key map ([Keys] button / openControlList key): lines read from the game when it opens, scrolled by whole lines.
    private boolean keysOpen, openKeys;
    private int keysScroll;
    /** The mapping waiting for its new key (a click on its row started it), or null. */
    private String rebindId;
    private java.util.List<ControlList.Line> keysLines = java.util.List.of();

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

    // Marker panel (Phase 8.5). Global, like the markers themselves; it belongs to the tab it was opened on, so switching tabs closes it.
    private boolean markerPanelOpen;
    private BrowserTab markerTab;
    private int markerScroll;
    /** The marker whose name is being edited in the panel (null: none), and the field holding the text. */
    private String markerRenameId;
    private final TextField markerText = new TextField();
    /** A marker the panel points out (a duplicate that was not added). */
    private String markerHighlight;

    /** Keys whose press this screen consumed (shortcuts, Esc): their release must not reach the page either. */
    private final Set<Integer> consumedKeys = new HashSet<>();

    /** Shown once as a status line when the screen opens (e.g. "Nothing to look up here"); null for none. */
    private String openMessage;

    // Phase 10: snapping, tear-off. {@code overlay}: opened over another screen (F9 over a menu); only the shell state records it,
    // the screen is drawn exactly as when opened over nothing (see extractRenderState).
    private boolean overlay;
    /** The pointer has moved far enough since the press for this to be a drag (snapping and tear-off only apply then). */
    private boolean dragMoved;
    /** The zone being previewed and the rectangle (shell units) it would give the window; applied only on release. */
    private SnapZone snapZone = SnapZone.NONE;
    private final SnapIntent snapIntent = new SnapIntent();
    private Box snapBox;
    /** The pointer pulled the window out of the Minecraft window: the desktop window now owns it and this screen only waits for the button to be released. */
    private boolean tearing;
    private static final int DRAG_THRESHOLD = 4;

    private ChromiumProofScreen(Screen parent) {
        super(Component.translatable("integratedntwiki.title"));
        this.parent = parent;
    }

    public static void open(net.minecraft.client.Minecraft minecraft) {
        Screen current = minecraft.gui.screen();
        if (current instanceof ChromiumProofScreen) return; // already showing: never stack a second browser window on the first
        openOverlay(minecraft, current);
    }

    /**
     * F9 over another screen (or from the game): the window opens over {@code parent} and returns to it when closed. It is drawn like
     * any other Minecraft screen (its own background); the screen underneath is not drawn around it.
     */
    public static void openOverlay(net.minecraft.client.Minecraft minecraft, Screen parent) {
        if (DesktopShell.interceptOpen(null)) return; // the shell lives on the desktop: F9 shows / hides that window instead
        ChromiumProofScreen screen = new ChromiumProofScreen(parent);
        screen.overlay = parent != null;
        minecraft.gui.setScreen(screen);
    }

    /**
     * The openControlList key from the game or over another screen: the controls key map opens in the shell (in the desktop window
     * when the shell lives there; while this screen is up the key toggles the panel).
     */
    public static void openControls(net.minecraft.client.Minecraft minecraft, Screen parent) {
        if (minecraft.gui.screen() instanceof ChromiumProofScreen open) {
            open.toggleKeys();
            return;
        }
        if (DesktopShell.interceptControls()) return;
        ChromiumProofScreen screen = new ChromiumProofScreen(parent);
        screen.overlay = parent != null;
        screen.openKeys = true;
        minecraft.gui.setScreen(screen);
    }

    /** The H key: opens the window over {@code parent} on {@code url} (a wiki address), optionally with a message. */
    public static void open(net.minecraft.client.Minecraft minecraft, Screen parent, String url, String message) {
        if (DesktopShell.interceptOpen(url)) return; // the shell is a desktop window: the address opens there
        ChromiumProofScreen screen = new ChromiumProofScreen(parent);
        screen.openMessage = message;
        BrowserSession.get().showAddress(url);
        minecraft.gui.setScreen(screen);
    }

    @Override
    protected void init() {
        MCEF.prepare();
        session = BrowserSession.get();
        com.levviata.integratedntwiki.desktop.DesktopShell.restoreSession(); // the page the last run ended on (shared with the desktop window); only if no tab is open yet
        if (openMessage != null) { showStatus(openMessage); openMessage = null; }
        if (openKeys) { openKeys = false; showKeys(); }
        session.useUnit(unit());
        session.place(shellW(), shellH());
        markers().refresh(); // reads the saved markers (false = config not readable yet; the panel tries again when opened)
        if (!shown) {
            shown = true;
            session.shell().showInMinecraft(overlay);
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
        return session.layout().hit(toShell(guiX), toShell(guiY), session.activeIndex(), session.urlBar().visible(), zoomMenuOpen, findOpen, markerRowCount(), keysOpen);
    }

    private static boolean isZoomPart(Part part) {
        return part == Part.ZOOM_BUTTON || part == Part.ZOOM_MENU || part == Part.ZOOM_OUT || part == Part.ZOOM_VALUE
                || part == Part.ZOOM_IN || part == Part.ZOOM_RESET;
    }

    private static boolean isFindPart(Part part) {
        return part == Part.FIND_BAR || part == Part.FIND_FIELD || part == Part.FIND_PREVIOUS || part == Part.FIND_NEXT || part == Part.FIND_CLOSE;
    }

    private static boolean isKeysPart(Part part) {
        return part == Part.KEYS_PANEL || part == Part.KEYS_CLOSE;
    }

    private static boolean isMarkerPart(Part part) {
        return part == Part.MARKER_PANEL || part == Part.MARKER_ADD || part == Part.MARKER_ROW || part == Part.MARKER_DELETE;
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
        statusColor = ERROR_COLOR;
        statusUntil = System.currentTimeMillis() + STATUS_MILLIS;
    }

    /** Like {@link #showStatus} for good news (a marker was added): drawn in the normal text colour, not the error red. */
    private void showNote(String message) {
        showStatus(message);
        statusColor = TEXT;
    }

    // ---- drawing

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        // Minecraft's own background, always. The screen this one was opened over is NOT drawn underneath: Minecraft already
        // called its removed() when this screen replaced it (a container screen has closed its menu by then), it is not
        // re-laid-out when the window changes size, and drawing it a second time inside this screen's own render pass broke it as
        // soon as the browser rendered. It is initialised again properly by setScreen(parent) when this screen closes.
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        if (tearing) return; // the window is a desktop window now; this screen only waits for the button to be released
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
        if (markerPanelOpen && session.active() != markerTab) closeMarkerPanel(true); // switched to another tab (or it closed)
        if (menuOpen && (menuKind == MenuKind.PAGE ? session.active() != menuTab : menuKind == MenuKind.MARKER ? !markerPanelOpen : session.indexOf(menuTarget) < 0)) closeContextMenu();
        if (drag == Drag.MOVE && dragMoved && !tearing) tickSnapPreview();
        updateUrlBar(tab, (int) Math.floor(toShell(mouseX)), (int) Math.floor(toShell(mouseY)));

        MCEF.pump();
        PageQuery.tick();
        pollContextMenu(session.active());
        String failure = ExternalBrowser.takeFailure(); // a link handed to the OS could not be opened
        if (failure != null) showStatus(Component.translatable("integratedntwiki.external." + failure + "Failed").getString());
        String notice = session.takeNotice(); // the outcome of a marker navigation (restored approximately, page failed ...)
        if (notice != null) showStatus(notice);
        float scale = (float) (unit / guiScale());
        g.pose().pushMatrix();
        g.pose().scale(scale, scale);
        drawShell(g, session.layout(), tab, (int) Math.floor(toShell(mouseX)), (int) Math.floor(toShell(mouseY)), unit);
        drawSnapPreview(g);
        g.pose().popMatrix();
        if (System.currentTimeMillis() < statusUntil) g.text(font, status, 4, height - 12, statusColor, true);
    }

    /** Draws the shell; everything here is in shell units (the caller applied the pose scale), mouse included. */
    private void drawShell(GuiGraphicsExtractor g, ShellLayout layout, BrowserTab active, int mouseX, int mouseY, int unit) {
        Hit hover = drag == Drag.NONE ? layout.hit(mouseX, mouseY, session.activeIndex(), session.urlBar().visible(), zoomMenuOpen, findOpen, markerRowCount(), keysOpen) : Hit.NONE;
        Rect win = layout.window();
        g.fill(win.x(), win.y(), win.right(), win.bottom(), EDGE);
        g.fill(win.x() + 1, win.y() + 1, win.right() - 1, win.bottom() - 1, BG);

        // tab bar
        Rect bar = layout.tabBar();
        g.fill(bar.x(), bar.y(), bar.right(), bar.bottom(), TAB_BAR_BG);
        for (int i = 0; i < session.tabCount(); i++) if (layout.tabVisible(i)) drawTab(g, layout, i, hover);
        if (layout.tabsOverflow()) button(g, layout.tabListButton(), hover.part() == Part.TAB_LIST, Part.TAB_LIST, true, Icons.CHEVRON_DOWN, false);
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
        Rect markerButton = layout.markerButton();
        button(g, markerButton, hover.part() == Part.MARKER_BUTTON, Part.MARKER_BUTTON, true, Icons.MARKER, false);
        // like Chrome's star: marked while this page has markers (and while the panel is open)
        if (markerPanelOpen || (markers().isLoaded() && markers().countFor(active.url()) > 0)) g.fill(markerButton.x() + 2, markerButton.bottom() - 2, markerButton.right() - 2, markerButton.bottom(), PIN_COLOR);
        Rect keysButton = layout.keysButton();
        button(g, keysButton, hover.part() == Part.KEYS_BUTTON, Part.KEYS_BUTTON, true, Icons.KEYS, false);
        if (keysOpen) g.fill(keysButton.x() + 2, keysButton.bottom() - 2, keysButton.right() - 2, keysButton.bottom(), ACCENT);
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
        if (markerPanelOpen) drawMarkerPanel(g, layout, active, hover);
        if (keysOpen) drawKeysPanel(g, layout, hover, mouseX, mouseY);

        if (menuOpen) drawContextMenu(g, mouseX, mouseY);

        // resize grip in the frame corner (every edge and corner resizes; the grip only marks that the window can be)
        int rx = win.right(), by = win.bottom();
        g.fill(rx - 3, by - 3, rx - 1, by - 1, TEXT_DIM);
        g.fill(rx - 6, by - 3, rx - 4, by - 1, TEXT_DIM);
        g.fill(rx - 3, by - 6, rx - 1, by - 4, TEXT_DIM);

        drawTooltip(g, layout, active, hover, mouseX, mouseY);
    }

    // ---- tooltips (the texts and what they belong to are shell/Tooltips; the Swing desktop window shows the same ones)

    private Hit tipHit = Hit.NONE;
    private int tipRow = -1;
    private long tipSince;

    /** A translated tooltip text, or null when the language file has no such key (a raw key must never be printed). */
    private static String tip(String key) {
        if (key == null) return null;
        String text = Component.translatable(key).getString();
        return text.equals(key) ? null : text;
    }

    /** The tooltip for what the pointer rests on, after {@link Tooltips#DELAY_MS}; none while a menu, a drag or a key rebind is going on. */
    private void drawTooltip(GuiGraphicsExtractor g, ShellLayout layout, BrowserTab active, Hit hover, int mx, int my) {
        int row = hover.part() == Part.KEYS_PANEL ? keysRowAt(layout, mx, my) : -1;
        long now = System.currentTimeMillis();
        if (!hover.equals(tipHit) || row != tipRow) { tipHit = hover; tipRow = row; tipSince = now; }
        if (menuOpen || drag != Drag.NONE || rebindId != null || now - tipSince < Tooltips.DELAY_MS) return;
        String text = null;
        switch (hover.part()) {
            case TAB -> { if (hover.index() >= 0 && hover.index() < session.tabCount()) text = session.tab(hover.index()).displayTitle(); }
            case KEYS_PANEL -> {
                ControlList.Line line = row < 0 ? null : keysLines.get(row);
                text = tip(Tooltips.rowKey(line));
                if (text != null && line.warn() && !line.note().isEmpty()) text += "\n" + line.note(); // a clash: say with what
                if (text != null && Tooltips.rebindHint(line)) { String h = tip(Tooltips.REBIND_KEY); if (h != null) text += "\n" + h; }
            }
            default -> {
                boolean flag = switch (hover.part()) {
                    case MAXIMIZE -> session.maximized();
                    case RELOAD -> active.loading();
                    case URL_BUTTON -> session.urlBar().pinned();
                    default -> false;
                };
                text = tip(Tooltips.partKey(hover.part(), flag));
            }
        }
        if (text == null || text.isEmpty()) return;
        Rect win = layout.window();
        java.util.List<String> lines = Tooltips.wrap(text, Math.max(60, Math.min(220, win.w() - 16)), font::width);
        int w = 0;
        for (String l : lines) w = Math.max(w, font.width(l));
        w += 8;
        int h = lines.size() * (font.lineHeight + 1) + 5;
        int x = Math.max(win.x() + 2, Math.min(mx + 8, win.right() - w - 2));
        int y = my + 18 + h > win.bottom() ? Math.max(win.y() + 2, my - h - 4) : my + 18;
        g.fill(x, y, x + w, y + h, EDGE);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, TAB_BAR_BG);
        for (int i = 0; i < lines.size(); i++) g.text(font, lines.get(i), x + 4, y + 3 + i * (font.lineHeight + 1), TEXT, false);
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
        // hovering the bar's own place reveals it; not while a menu or panel is open over the window, and never mid-drag
        boolean inTopStrip = drag == Drag.NONE && !menuOpen && layout.hoverZone().contains(shellX, shellY);
        boolean overBar = layout.title().contains(shellX, shellY) || layout.urlButton().contains(shellX, shellY)
                || layout.zoomButton().contains(shellX, shellY) || layout.markerButton().contains(shellX, shellY);
        bar.update(inTopStrip, overBar, urlFocused, System.currentTimeMillis());
        if (!bar.visible() && urlFocused) blurUrlField(true);
        if (!urlFocused && !tab.url().equals(syncedUrl)) {
            syncedUrl = tab.url();
            urlText.setText(syncedUrl);
        }
    }

    private void focusUrlField(boolean selectAll) {
        BrowserTab tab = session.active();
        closeMarkerPanel(true);
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
        if (WikiUrls.isExternalProtocol(url.get())) { // an e-mail address, mailto: or xmpp: link typed by the user goes to their own program
            if (ExternalBrowser.open(url.get())) {
                session.urlBar().dismissTemporary();
                blurUrlField(true);
            } else {
                showStatus(Component.translatable("integratedntwiki.urlbar.blocked").getString());
            }
            return;
        }
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
            WikiLog.info("Paste into a shell field: the OS clipboard holds no text");
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
        closeMarkerPanel(true);
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
        if (tab != null && !urlFocused && !findFocused && !zoomEditing && markerRenameId == null) tab.input().focusBrowser();
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
        closeMarkerPanel(true);
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
        Rect r = field == findText ? layout.findField() : field == zoomText ? layout.zoomValue() : field == markerText ? markerRenameRect() : layout.urlField();
        return (r == null ? layout.urlField() : r).x() + 4;
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
        updateResizeCursor(tab, guiX, guiY);
    }

    /**
     * The resize cursor over the window's frame: horizontal on the left and right edges, vertical on the top and bottom, a diagonal on
     * the corners. Leaving the frame puts back what belongs there (the page's cursor over the page, the arrow elsewhere). It is only
     * touched when the edge set changes, so Chromium's own cursor handling over the page is left alone.
     */
    private void updateResizeCursor(BrowserTab tab, double guiX, double guiY) {
        int edges = hitAt(guiX, guiY).part().resizeEdges(); // what a press here would grab: the frame, or the wider strip where no control is
        if (edges == cursorEdges) return;
        cursorEdges = edges;
        if (edges != WindowResize.NONE) tab.browser().setCursor(resizeCursor(edges));
        else if (overContent) tab.browser().restoreCursor();
        else tab.browser().getCursorChangeListener().onCursorChange(0);
    }

    private static CefCursorType resizeCursor(int edges) {
        return switch (WindowResize.shape(edges)) {
            case HORIZONTAL -> CefCursorType.EAST_WEST_RESIZE;
            case VERTICAL -> CefCursorType.NORTH_SOUTH_RESIZE;
            case DIAGONAL_NW_SE -> CefCursorType.NORTH_WEST_SOUTH_EAST_RESIZE;
            case DIAGONAL_NE_SW -> CefCursorType.NORTH_EAST_SOUTH_WEST_RESIZE;
            default -> CefCursorType.POINTER;
        };
    }

    /** The left button went down on the frame: remember the window and the pointer, and resize by the pointer's movement from here. */
    private void beginResize(int edges, double shellX, double shellY) {
        drag = Drag.RESIZE;
        resizeEdges = edges;
        resizeStart = session.windowBox();
        resizePressX = (int) shellX;
        resizePressY = (int) shellY;
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
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && pressBar(BarDrag.MENU, x, y)) return true; // its scroll bar scrolls, the menu stays
            if (menuBar().contains(x, y)) return true;
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
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) { // the key map's and the marker list's scroll bars
            BarDrag bar = barAt(x, y);
            if (bar != BarDrag.NONE && pressBar(bar, x, y)) {
                tab.input().cancelAutoscroll();
                return true;
            }
        }
        // The zoom menu closes when the user clicks anywhere else (the zoom button toggles it itself).
        if (zoomMenuOpen && !isZoomPart(hit.part())) closeZoomMenu();
        // Marker panel: a click inside the name being edited moves its caret; any other click ends the edit (the typed name is kept)
        // and a click outside the panel closes it (the marker button toggles it itself).
        if (markerRenameId != null) {
            Rect field = markerRenameRect();
            if (field != null && button == GLFW.GLFW_MOUSE_BUTTON_LEFT && field.contains(x, y)) {
                markerText.setCaretFromX((int) x - fieldOrigin(markerText), false, metrics());
                draggingField = markerText;
                tab.input().focusMinecraft();
                return true;
            }
            commitMarkerRename();
        }
        if (markerPanelOpen && !isMarkerPart(hit.part()) && hit.part() != Part.MARKER_BUTTON) closeMarkerPanel(true);
        // The controls key map closes on a click anywhere else (its button toggles it itself).
        if (keysOpen && !isKeysPart(hit.part()) && hit.part() != Part.KEYS_BUTTON) closeKeys();
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
        // Right-click on a marker row: its menu (open, open in new tab, rename, delete), the shell's own like the tab menu.
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT && hit.part() == Part.MARKER_ROW) {
            openMarkerMenu(hit.index(), x, y);
            swallowRightRelease = true;
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
        if (hit.part() == Part.KEYS_PANEL) { // a key mapping's row: the next key pressed becomes its key
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) clickKeysRow((int) x, (int) y);
            return true;
        }
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
                    dragMoved = false;
                    snapZone = SnapZone.NONE;
                    snapIntent.reset();
                    snapBox = null;
                    session.shell().beginDrag();
                    pressShellX = (int) x;
                    pressShellY = (int) y;
                    grabX = (int) x - session.windowX();
                    grabY = (int) y - session.windowY();
                }
                case EDGE_LEFT, EDGE_TOP, EDGE_RIGHT, EDGE_BOTTOM, CORNER, CORNER_TOP_LEFT, CORNER_TOP_RIGHT, CORNER_BOTTOM_LEFT ->
                        beginResize(hit.part().resizeEdges(), x, y);
                default -> { }
            }
        } else if (button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE && hit.part() == Part.TAB) {
            closeTabAt(hit.index());
        } else if (button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE && hit.part() == Part.MARKER_ROW) {
            openMarkerAt(hit.index(), true);
        }
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (tearing && event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) { // the desktop window takes over from here
            tearing = false;
            pressed = null;
            drag = Drag.NONE;
            onClose();
            DesktopShell.handoffRelease();
            return true;
        }
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT && drag == Drag.MOVE) commitSnap();
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) draggingField = null;
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT && barDrag != BarDrag.NONE) {
            barDrag = BarDrag.NONE;
            return true;
        }
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
        if (barDrag != BarDrag.NONE) {
            setBarOffset(barDrag, barOf(barDrag).offsetAt(y, barGrab));
            return true;
        }
        if (draggingField != null) {
            draggingField.setCaretFromX((int) x - fieldOrigin(draggingField), true, metrics());
            return true;
        }
        switch (drag) {
            case MOVE -> {
                if (tearing) {
                    DesktopShell.handoffMove();
                    return true;
                }
                if (!dragMoved && (Math.abs(x - pressShellX) >= DRAG_THRESHOLD || Math.abs(y - pressShellY) >= DRAG_THRESHOLD)) dragMoved = true;
                if (session.maximized()) {
                    // Pulling a maximized window by its tab strip picks it up at its previous size, under the pointer.
                    if (Math.abs(x - pressShellX) < DRAG_THRESHOLD && Math.abs(y - pressShellY) < DRAG_THRESHOLD) return true;
                    session.restoreUnderPointer((int) x, (int) y, shellW(), shellH());
                    grabX = (int) x - session.windowX();
                    grabY = (int) y - session.windowY();
                } else if (dragMoved && session.snappedRect()) { // a snapped window is picked up at its free size
                    session.restoreFromSnap((int) x, (int) y, shellW(), shellH());
                    grabX = (int) x - session.windowX();
                    grabY = (int) y - session.windowY();
                }
                if (dragMoved && outsideMinecraft(event.x(), event.y()) && tearOff()) return true;
                session.moveTo((int) x - grabX, (int) y - grabY, shellW(), shellH());
                if (dragMoved) updateSnapPreview((int) Math.round(x), (int) Math.round(y));
            }
            case RESIZE -> {
                if (resizeStart != null) session.resizeFrom(resizeStart, resizeEdges, (int) x - resizePressX, (int) y - resizePressY, shellW(), shellH());
            }
            case TAB -> {
                ShellLayout l = session.layout();
                int first = l.firstVisibleTab();
                int target = first + (int) Math.floor((x - l.tab(first).x()) / (double) l.tabWidth());
                target = Math.max(first, Math.min(target, first + l.visibleTabs() - 1));
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
            if (menuRows < menuItems.size()) { // a long menu (the tab list) scrolls
                if (scrollY != 0) setBarOffset(BarDrag.MENU, menuScroll - (int) Math.signum(scrollY) * 3);
                return true;
            }
            closeContextMenu();
            return true;
        }
        Part over = hitAt(guiX, guiY).part();
        if (isKeysPart(over)) { // the controls key map scrolls its lines
            if (scrollY != 0) keysScroll = ControlList.clampScroll(keysScroll - (int) Math.signum(scrollY) * 3, keysLines.size(), session.layout().keysRows());
            return true;
        }
        if (isMarkerPart(over)) { // the marker panel scrolls its list
            if (scrollY != 0) markerScroll -= (int) Math.signum(scrollY);
            clampMarkerScroll();
            return true;
        }
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
            case TAB_LIST -> openTabList();
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
            case MARKER_BUTTON -> {
                toggleMarkerPanel();
                if (markerPanelOpen) returnKeyboardToPage(); // the panel does not cost the page the keyboard
            }
            case KEYS_BUTTON -> {
                toggleKeys();
                returnKeyboardToPage(); // the panel does not cost the page the keyboard
            }
            case KEYS_CLOSE -> closeKeys();
            case MARKER_ADD -> addMarker(false);
            case MARKER_ROW -> openMarkerAt(hit.index(), ctrlDown()); // Ctrl+click: in a new tab
            case MARKER_DELETE -> deleteMarkerAt(hit.index());
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
        if (menuBar().contains(x, y)) return -1; // the bar is not a row
        int i = menuScroll + (int) ((y - menuY - 2) / MENU_ITEM_H);
        return i >= menuScroll && i < Math.min(menuItems.size(), menuScroll + menuRows) ? i : -1;
    }

    private ScrollBar menuBar() {
        return new ScrollBar(menuX + menuW - ScrollBar.HIT_W - 1, menuY + 2, menuRows * MENU_ITEM_H, menuItems.size(), menuRows);
    }

    private ScrollBar barOf(BarDrag which) {
        return switch (which) {
            case MENU -> menuBar();
            case MARKERS -> session.layout().markerBar(markers().size());
            case KEYS -> session.layout().keysBar(keysLines.size());
            case NONE -> new ScrollBar(0, 0, 0, 0, 0);
        };
    }

    private int barOffset(BarDrag which) {
        return switch (which) { case MENU -> menuScroll; case MARKERS -> markerScroll; case KEYS -> keysScroll; case NONE -> 0; };
    }

    private void setBarOffset(BarDrag which, int offset) {
        switch (which) {
            case MENU -> menuScroll = Math.max(0, Math.min(offset, Math.max(0, menuItems.size() - menuRows)));
            case MARKERS -> { markerScroll = offset; clampMarkerScroll(); }
            case KEYS -> keysScroll = ControlList.clampScroll(offset, keysLines.size(), session.layout().keysRows());
            case NONE -> { }
        }
    }

    /** A left press on a scroll bar's strip: jumps there (or grabs the thumb) and starts following the pointer. */
    private boolean pressBar(BarDrag which, double x, double y) {
        ScrollBar bar = barOf(which);
        if (!bar.contains(x, y)) return false;
        barGrab = bar.grab(y, barOffset(which));
        barDrag = which;
        setBarOffset(which, bar.offsetAt(y, barGrab));
        return true;
    }

    /** Which open list's bar is under the point, if any. */
    private BarDrag barAt(double x, double y) {
        if (keysOpen && barOf(BarDrag.KEYS).contains(x, y)) return BarDrag.KEYS;
        if (markerPanelOpen && barOf(BarDrag.MARKERS).contains(x, y)) return BarDrag.MARKERS;
        return BarDrag.NONE;
    }

    private void drawBar(GuiGraphicsExtractor g, ScrollBar bar, int offset, BarDrag which) {
        if (!bar.scrollable()) return;
        int t = bar.thumbTop(offset);
        int x0 = bar.x() + (ScrollBar.HIT_W - ScrollBar.DRAW_W) / 2;
        g.fill(x0, t, x0 + ScrollBar.DRAW_W, t + bar.thumbLength(), barDrag == which ? TEXT : TEXT_DIM);
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
        menuRows = Math.max(1, Math.min(items.size(), Math.min(20, (shellH() - 4) / MENU_ITEM_H)));
        menuScroll = 0;
        barDrag = BarDrag.NONE;
        if (menuRows < items.size()) menuW += ScrollBar.HIT_W; // room for the scroll bar beside the rows
        menuH = menuRows * MENU_ITEM_H + 4;
        menuX = Math.max(0, Math.min(atX, shellW() - menuW));
        menuY = Math.max(0, Math.min(atY, shellH() - menuH));
        menuOpen = true;
        closeZoomMenu();
    }

    // ---- Phase 10: snapping inside the Minecraft window, and pulling the window out of it

    /** The snap settings with the activation distance converted from screen pixels to shell units. */
    private SnapSettings snapSettings() {
        SnapSettings s = WikiConfig.snapSettings();
        int activation = Math.max(SnapSettings.MIN_ACTIVATION, Math.round(s.activation() / (float) Math.max(1, unit())));
        int speed = Math.max(1, Math.round(s.intentSpeed() / (float) Math.max(1, unit()))); // pixels per second -> shell units per second
        return new SnapSettings(s.enabled(), activation, s.exitFactor(), s.preview(), s.fraction(), s.desktopWidth(), s.desktopHeight(), s.tearOffDistance(),
                s.intentDelayMillis(), s.idleDelayMillis(), speed);
    }

    /** Which snap zone the pointer is in (measured against the Minecraft window), and the rectangle it would give: nothing moves yet. */
    private void updateSnapPreview(int shellX, int shellY) {
        SnapSettings s = snapSettings();
        Box area = new Box(0, 0, shellW(), shellH());
        showZone(snapIntent.update(area, shellX, shellY, System.currentTimeMillis(), s), area, s);
    }

    /** Time passing with the pointer held at an edge: the preview appears when the activation delay (see {@link SnapIntent}) is over. */
    private void tickSnapPreview() {
        if (!snapIntent.pending()) return;
        SnapSettings s = snapSettings();
        Box area = new Box(0, 0, shellW(), shellH());
        showZone(snapIntent.tick(area, System.currentTimeMillis(), s), area, s);
    }

    private void showZone(SnapZone zone, Box area, SnapSettings s) {
        snapZone = zone;
        session.shell().preview(snapZone);
        snapBox = snapZone.snapped() ? SnapEngine.target(area, snapZone, s) : null;
    }

    /** The button was released: a previewed zone becomes the window's rectangle (otherwise it stays where the pointer put it). */
    private void commitSnap() {
        if (snapZone.snapped() && snapBox != null) session.snapTo(snapBox, shellW(), shellH());
        session.shell().commit();
        snapZone = SnapZone.NONE;
        snapIntent.reset();
        snapBox = null;
    }

    /** The outline of where the window will snap (shell units; drawn under the shell's pose scale). Only while previewing. */
    private void drawSnapPreview(GuiGraphicsExtractor g) {
        if (snapBox == null || !session.shell().previewing() || !WikiConfig.SNAP_PREVIEW.get()) return;
        int x0 = snapBox.x(), y0 = snapBox.y(), x1 = snapBox.right(), y1 = snapBox.bottom();
        g.fill(x0, y0, x1, y1, SNAP_FILL);
        g.fill(x0, y0, x1, y0 + 2, ACCENT);
        g.fill(x0, y1 - 2, x1, y1, ACCENT);
        g.fill(x0, y0, x0 + 2, y1, ACCENT);
        g.fill(x1 - 2, y0, x1, y1, ACCENT);
    }

    private static final int SNAP_FILL = 0x554FA0FF;

    /** The pointer (GUI units) is beyond the game window by more than the pull-out distance. */
    private boolean outsideMinecraft(double guiX, double guiY) {
        int d = WikiConfig.TEAR_OFF_DISTANCE.get();
        return guiX < -d || guiY < -d || guiX > width + d || guiY > height + d;
    }

    /** Hands the window to the desktop, keeping the pointer's grab point; this screen then only waits for the release. */
    private boolean tearOff() {
        if (!DesktopShell.tearOff(grabX, grabY, session.windowW(), session.windowH(), session.unit())) return false;
        tearing = true;
        snapZone = SnapZone.NONE;
        snapIntent.reset();
        snapBox = null;
        closeContextMenu();
        return true;
    }

    /** "Move to desktop window": the window leaves Minecraft, centred on the monitor the game is on. */
    private void popOut() {
        if (DesktopShell.openCentered()) onClose();
        else showStatus(Component.translatable("integratedntwiki.desktop.unavailable").getString() + " " + DesktopShell.failureReason());
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

    /** The list of every open tab (the button a crowded tab strip ends in): choosing a row selects that tab; the active one has a dot. */
    private void openTabList() {
        if (session.tabCount() == 0) return;
        BrowserContextMenu.take();
        leaveFields();
        java.util.List<MenuItem> items = new java.util.ArrayList<>();
        for (int i = 0; i < session.tabCount(); i++) {
            final int index = i;
            String title = session.tab(i).displayTitle();
            if (title == null || title.isBlank()) title = Component.translatable("integratedntwiki.browser.newTab").getString();
            title = fit(title, 220);
            items.add(new MenuItem((i == session.activeIndex() ? "\u2022 " : "   ") + title, "", true, () -> session.select(index)));
        }
        Rect b = session.layout().tabListButton();
        menuKind = MenuKind.TAB;
        menuTab = null;
        menuTarget = session.active();
        showMenu(items, b.x(), b.bottom() + 1);
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
        items.add(item("popOut", "", true, this::popOut));
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
            items.add(item("popOut", "", true, this::popOut));
            items.add(item("copyAddress", "", tab.url() != null && !tab.url().isBlank(), () -> copyToClipboard(tab.url())));
            items.add(separator());
        }
        if (!r.editable()) { // a marker at the block that was right-clicked (the page recorded it; see MarkerScripts.CONTEXT_HOOK)
            items.add(item("addMarker", WikiUrls.isTopLevelAllowed(tab.url()) && tab.errorText() == null, () -> addMarker(true)));
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

    // ---- markers (Phase 8.5)
    //
    // A marker is a saved place in a wiki page. The markers themselves live in MarkerStore / the mod config, not here: this
    // screen only shows them. Nothing below keeps a marker in a tab; a tab only ever holds a transient "restore the position
    // when the page has loaded" note (BrowserTab.pendingMarker), set by BrowserSession.openMarker.

    private static MarkerStore markers() {
        return MarkerStorage.get();
    }

    private static String marker(String key, Object... args) {
        return Component.translatable("integratedntwiki.marker." + key, args).getString();
    }

    /** The panel's markers: those of the page being viewed first (top to bottom), then the rest in the order they were made. */
    private java.util.List<Marker> markerList() {
        BrowserTab tab = session.active();
        return markers().ordered(tab == null ? null : tab.url());
    }

    /** Rows the open panel has (0 when closed), for hit-testing and drawing. */
    private int markerRowCount() {
        return markerPanelOpen ? session.layout().markerRows(markers().size()) : 0;
    }

    private void clampMarkerScroll() {
        int size = markers().size();
        markerScroll = Math.max(0, Math.min(markerScroll, Math.max(0, size - session.layout().markerRows(size))));
    }

    private void ensureMarkerVisible(String id) {
        java.util.List<Marker> list = markerList();
        int rows = session.layout().markerRows(list.size());
        for (int i = 0; i < list.size(); i++) {
            if (!list.get(i).id().equals(id)) continue;
            if (i < markerScroll) markerScroll = i;
            else if (i >= markerScroll + rows) markerScroll = i - rows + 1;
        }
        clampMarkerScroll();
    }

    private void toggleMarkerPanel() {
        if (markerPanelOpen) closeMarkerPanel(true);
        else openMarkerPanel(true);
    }

    /** @param reread read the saved markers again first (so a hand-edited config shows up); false right after a change made here */
    private void openMarkerPanel(boolean reread) {
        BrowserTab tab = session.active();
        if (tab == null) return;
        closeZoomMenu();
        if (urlFocused) blurUrlField(false);
        findFocused = false;
        zoomEditing = false;
        tab.input().cancelAutoscroll();
        if (reread) markers().refresh();
        markerTab = tab;
        markerScroll = 0;
        markerHighlight = null;
        markerPanelOpen = true;
    }

    /** @param commit a name being typed is kept (true: clicking away, switching tabs, closing) rather than dropped */
    private void closeMarkerPanel(boolean commit) {
        if (markerRenameId != null) {
            if (commit) commitMarkerRename();
            else cancelMarkerRename();
        }
        if (!markerPanelOpen) return;
        markerPanelOpen = false;
        markerTab = null;
        markerHighlight = null;
        if (menuOpen && menuKind == MenuKind.MARKER) closeContextMenu();
    }

    private void beginMarkerRename(Marker m) {
        BrowserTab tab = session.active();
        if (urlFocused) blurUrlField(false);
        findFocused = false;
        zoomEditing = false;
        markerRenameId = m.id();
        markerText.setText(m.name());
        markerText.selectAll();
        caretBlinkStart = System.currentTimeMillis();
        ensureMarkerVisible(m.id());
        if (tab != null) {
            tab.input().cancelAutoscroll();
            tab.input().focusMinecraft();
        }
    }

    private void commitMarkerRename() {
        String id = markerRenameId;
        if (id == null) return;
        markerRenameId = null;
        if (draggingField == markerText) draggingField = null;
        String name = markerText.text();
        if (!name.isBlank() && markers().find(id) != null && !name.strip().equals(markers().find(id).name())) {
            markers().rename(id, name);
            if (markers().lastSaveFailed()) showStatus(marker("saveFailed"));
        }
        returnKeyboardToPage();
    }

    private void cancelMarkerRename() {
        markerRenameId = null;
        if (draggingField == markerText) draggingField = null;
        returnKeyboardToPage();
    }

    /** Where the name being edited is drawn (null when no visible row is being renamed). */
    private Rect markerRenameRect() {
        if (markerRenameId == null || !markerPanelOpen) return null;
        ShellLayout layout = session.layout();
        java.util.List<Marker> list = markerList();
        int rows = layout.markerRows(list.size());
        for (int i = 0; i < rows && markerScroll + i < list.size(); i++) {
            if (!list.get(markerScroll + i).id().equals(markerRenameId)) continue;
            Rect r = layout.markerRow(rows, i);
            return new Rect(r.x() + 2, r.y() + 1, r.w() - 4, r.h() - 2);
        }
        return null;
    }

    /**
     * Adds a marker at the top of the view (Ctrl+D, the panel's + button) or at the block that was right-clicked. The page
     * itself says where it is ({@link MarkerScripts#captureBody}); the answer is checked against what the browser may open.
     */
    private void addMarker(boolean atRightClick) {
        BrowserTab tab = session.active();
        if (tab == null) return;
        if (tab.errorText() != null || !WikiUrls.isTopLevelAllowed(tab.url())) {
            showStatus(marker("noPosition")); // an error page or a page that is not a wiki page has no position to save
            return;
        }
        MCEFBrowser browser = tab.browser();
        PageQuery.ask(browser, browser.getMainFrame(), MarkerScripts.captureBody(atRightClick),
                answer -> finishAddMarker(tab, answer), () -> showStatus(marker("noPosition")), PageQuery.SLOW_TIMEOUT_MILLIS);
    }

    private void finishAddMarker(BrowserTab tab, String answer) {
        if (session.indexOf(tab) < 0) return; // the tab was closed while the page was answering
        java.util.Map<String, String> fields = MarkerCodec.parseFields(answer);
        MarkerStore.AddResult result = markers().add(fields.get("u"), MarkerCodec.position(fields), fields.getOrDefault("ti", ""), System.currentTimeMillis());
        boolean here = session.active() == tab; // the panel is for the tab being looked at
        switch (result.status()) {
            case ADDED -> {
                if (result.saved()) showNote(marker("added", result.marker().name()));
                else showStatus(marker("saveFailed"));
                if (here) { // like Chrome's "bookmark added": the name can be changed right away (Enter keeps it, Esc too)
                    openMarkerPanel(false);
                    beginMarkerRename(result.marker());
                }
            }
            case DUPLICATE -> {
                showNote(marker("duplicate", result.marker().name()));
                if (here) {
                    openMarkerPanel(false);
                    markerHighlight = result.marker().id();
                    ensureMarkerVisible(result.marker().id());
                }
            }
            case FULL -> showStatus(marker("full", MarkerStore.MAX_MARKERS));
            case UNAVAILABLE -> showStatus(marker("unavailable"));
            case NO_ADDRESS -> showStatus(marker("noPosition"));
        }
    }

    private void openMarkerAt(int visibleIndex, boolean inNewTab) {
        java.util.List<Marker> list = markerList();
        int i = markerScroll + visibleIndex;
        if (i >= 0 && i < list.size()) openMarker(list.get(i), inNewTab);
    }

    private void openMarker(Marker m, boolean inNewTab) {
        Rect c = session.layout().content();
        leaveFields(); // closes the panel
        switch (session.openMarker(m, inNewTab, c.w() * session.unit(), c.h() * session.unit())) {
            case BLOCKED -> showStatus(marker("blocked"));
            case TAB_LIMIT -> tabLimitMessage();
            case NO_TAB -> { }
            default -> returnKeyboardToPage();
        }
    }

    private void deleteMarkerAt(int visibleIndex) {
        java.util.List<Marker> list = markerList();
        int i = markerScroll + visibleIndex;
        if (i < 0 || i >= list.size()) return;
        Marker m = list.get(i);
        if (m.id().equals(markerRenameId)) cancelMarkerRename();
        markers().delete(m.id());
        if (m.id().equals(markerHighlight)) markerHighlight = null;
        if (markers().lastSaveFailed()) showStatus(marker("saveFailed"));
        else showNote(marker("deleted", m.name()));
        clampMarkerScroll();
    }

    /** The menu of a marker row (right-click on it): the shell's own, like the tab menu. */
    private void openMarkerMenu(int visibleIndex, double shellX, double shellY) {
        java.util.List<Marker> list = markerList();
        int i = markerScroll + visibleIndex;
        if (i < 0 || i >= list.size()) return;
        Marker m = list.get(i);
        BrowserContextMenu.take(); // a page-menu request still waiting must not pop up over this menu
        java.util.List<MenuItem> items = new java.util.ArrayList<>();
        items.add(item("markerOpen", true, () -> openMarker(m, false)));
        items.add(item("markerOpenTab", !session.tabLimitReached(), () -> openMarker(m, true)));
        items.add(item("markerRename", true, () -> beginMarkerRename(m)));
        items.add(separator());
        items.add(item("markerDelete", true, () -> deleteMarkerAt(visibleIndex)));
        menuKind = MenuKind.MARKER;
        menuTab = null;
        menuTarget = null;
        showMenu(items, (int) shellX, (int) shellY);
    }

    // ---- controls key map

    private void showKeys() {
        closeZoomMenu();
        closeMarkerPanel(true);
        keysLines = ControlSource.lines(minecraft);
        keysScroll = 0;
        keysOpen = true;
    }

    private void closeKeys() {
        keysOpen = false;
        endPadWait();
        rebindId = null;
    }

    /** A controller row that is waiting for a button stops waiting (Phase 7 rebinding). */
    private void endPadWait() {
        if (rebindId != null && rebindId.startsWith(ControlList.Line.PAD_ID)) ControlSource.cancelPadCapture();
    }

    /** The line under a point of the controls panel, or -1 over the footer, the reset buttons or empty space. */
    private int keysRowAt(ShellLayout layout, int x, int y) {
        Rect body = layout.keysBody();
        if (y < body.y() || y >= body.y() + layout.keysRows() * ShellLayout.KEYS_ROW) return -1;
        int i = KeyRebind.lineAt(y, body.y(), ShellLayout.KEYS_ROW, keysScroll, keysLines.size());
        if (i < 0) return -1;
        if (keysLines.get(i).rebindable() && layout.keysRowReset(rowTop(layout, y), ShellLayout.KEYS_ROW).contains(x, y)) return -1;
        return i;
    }

    /** The top of the row a y falls in (rows start at the body's top, {@link ShellLayout#KEYS_ROW} high). */
    private static int rowTop(ShellLayout layout, int y) {
        int top = layout.keysBody().y();
        return top + (y - top) / ShellLayout.KEYS_ROW * ShellLayout.KEYS_ROW;
    }

    private void clickKeysRow(int x, int y) {
        ShellLayout layout = session.layout();
        endPadWait();
        rebindId = null;
        if (layout.keysResetPad().contains(x, y)) { // [Reset Controller]
            ControlSource.resetController();
            keysLines = ControlSource.lines(minecraft);
            return;
        }
        if (layout.keysResetKeys().contains(x, y)) { // [Reset Mod Keybinds]
            ControlSource.resetKeys(minecraft);
            keysLines = ControlSource.lines(minecraft);
            return;
        }
        Rect body = layout.keysBody();
        if (y < body.y() || y >= body.y() + layout.keysRows() * ShellLayout.KEYS_ROW) return;
        int i = KeyRebind.lineAt(y, body.y(), ShellLayout.KEYS_ROW, keysScroll, keysLines.size());
        ControlList.Line line = i < 0 ? null : keysLines.get(i);
        if (line != null && line.rebindable() && layout.keysRowReset(rowTop(layout, y), ShellLayout.KEYS_ROW).contains(x, y)) { // the row's own reset
            ControlSource.resetRow(minecraft, line.id());
            keysLines = ControlSource.lines(minecraft);
            return;
        }
        rebindId = line != null && line.rebindable() ? line.id() : null;
        if (line != null && line.padRow()) { // the next controller press is the new button (the press that clicked here does not count)
            String id = line.id();
            ControlSource.capturePad(id, () -> {
                keysLines = ControlSource.lines(minecraft);
                if (id.equals(rebindId)) rebindId = null;
            });
        }
    }

    /** A key press while a mapping waits for its key (NeoForge's Key Binds rules: modifiers held count, Esc unbinds). */
    private boolean rebindKey(KeyEvent event) {
        KeyRebind.Outcome o = KeyRebind.onKey(event.key(), keyMods(event.modifiers()));
        if (o.kind() == KeyRebind.Kind.WAIT) return true;
        String id = rebindId;
        rebindId = null;
        ControlSource.rebind(minecraft, id, o.key(), o.mods());
        keysLines = ControlSource.lines(minecraft);
        return true;
    }

    private static int keyMods(int glfwMods) {
        return ((glfwMods & GLFW.GLFW_MOD_SHIFT) != 0 ? KeyRebind.MOD_SHIFT : 0)
                | ((glfwMods & GLFW.GLFW_MOD_CONTROL) != 0 ? KeyRebind.MOD_CONTROL : 0)
                | ((glfwMods & GLFW.GLFW_MOD_ALT) != 0 ? KeyRebind.MOD_ALT : 0);
    }

    private void toggleKeys() {
        if (keysOpen) closeKeys();
        else showKeys();
    }

    private void drawKeysPanel(GuiGraphicsExtractor g, ShellLayout layout, Hit hover, int mouseX, int mouseY) {
        Rect p = layout.keysPanel();
        g.fill(p.x(), p.y(), p.right(), p.bottom(), EDGE);
        g.fill(p.x() + 1, p.y() + 1, p.right() - 1, p.bottom() - 1, TAB_ACTIVE);
        g.text(font, fit(ControlSource.labels().title(), p.w() - ShellLayout.KEYS_CELL - 14), p.x() + 6,
                p.y() + (ShellLayout.KEYS_HEADER - font.lineHeight) / 2, TEXT, false);
        overlayButton(g, layout.keysClose(), hover.part() == Part.KEYS_CLOSE, Part.KEYS_CLOSE, Icons.CLOSE);
        int rows = layout.keysRows();
        keysScroll = ControlList.clampScroll(keysScroll, keysLines.size(), rows);
        Rect body = layout.keysBody();
        int nameX = body.x() + 2, keysX = body.x() + body.w() * 30 / 100, noteX = body.x() + body.w() * 55 / 100;
        for (int i = 0; i < rows && keysScroll + i < keysLines.size(); i++) {
            ControlList.Line line = keysLines.get(keysScroll + i);
            int y = body.y() + i * ShellLayout.KEYS_ROW + (ShellLayout.KEYS_ROW - font.lineHeight) / 2;
            switch (line.type()) {
                case HEADING -> g.text(font, fit(line.left(), body.w()), nameX, y, ACCENT, false);
                case EMPTY -> g.text(font, fit(line.left(), body.w()), nameX, y, TEXT_DIM, false);
                case ROW -> {
                    boolean note = !line.note().isEmpty();
                    int color = line.warn() ? WARN_COLOR : TEXT_DIM;
                    int rowTop = body.y() + i * ShellLayout.KEYS_ROW;
                    Rect reset = layout.keysRowReset(rowTop, ShellLayout.KEYS_ROW);
                    int limit = line.rebindable() ? reset.x() - 2 : body.right(); // the reset button takes the row's right end
                    g.text(font, fit(line.left(), keysX - nameX - 4), nameX, y, TEXT, false);
                    boolean waiting = line.rebindable() && line.id().equals(rebindId);
                    String keyText = waiting ? Component.translatable(line.padRow() ? "integratedntwiki.keys.pressPad" : "integratedntwiki.keys.press").getString() : line.right();
                    g.text(font, fit(keyText, (note ? noteX : limit) - keysX - 4), keysX, y, waiting ? ACCENT : color, false);
                    if (note) g.text(font, fit(line.note(), limit - noteX - 4), noteX, y, color, false);
                    if (line.rebindable()) {
                        boolean over = reset.contains(mouseX, mouseY);
                        g.fill(reset.x(), reset.y(), reset.right(), reset.bottom(), over ? BUTTON_HOVER : TAB_IDLE);
                        g.text(font, "R", reset.x() + (reset.w() - font.width("R")) / 2, y, over ? TEXT : TEXT_DIM, false);
                    }
                }
            }
        }
        drawBar(g, layout.keysBar(keysLines.size()), keysScroll, BarDrag.KEYS);
        keysFooterButton(g, layout.keysResetPad(), Component.translatable("integratedntwiki.keys.resetController").getString(), mouseX, mouseY);
        keysFooterButton(g, layout.keysResetKeys(), Component.translatable("integratedntwiki.keys.resetKeys").getString(), mouseX, mouseY);
    }

    private void keysFooterButton(GuiGraphicsExtractor g, Rect r, String label, int mouseX, int mouseY) {
        boolean over = r.contains(mouseX, mouseY);
        g.fill(r.x(), r.y(), r.right(), r.bottom(), EDGE);
        g.fill(r.x() + 1, r.y() + 1, r.right() - 1, r.bottom() - 1, over ? BUTTON_HOVER : TAB_IDLE);
        String text = fit(label, r.w() - 6);
        g.text(font, text, r.x() + (r.w() - font.width(text)) / 2, r.y() + (r.h() - font.lineHeight) / 2, TEXT, false);
    }

    private void drawMarkerPanel(GuiGraphicsExtractor g, ShellLayout layout, BrowserTab tab, Hit hover) {
        java.util.List<Marker> list = markerList();
        clampMarkerScroll();
        int rows = layout.markerRows(list.size());
        Rect p = layout.markerPanel(rows);
        g.fill(p.x(), p.y(), p.right(), p.bottom(), EDGE);
        g.fill(p.x() + 1, p.y() + 1, p.right() - 1, p.bottom() - 1, TAB_ACTIVE);
        String title = fit(Component.translatable("integratedntwiki.markers.title", list.size()).getString(), p.w() - ShellLayout.MARKER_CELL - 14);
        g.text(font, title, p.x() + 6, p.y() + (ShellLayout.MARKER_HEADER - font.lineHeight) / 2, TEXT, false);
        overlayButton(g, layout.markerAdd(rows), hover.part() == Part.MARKER_ADD, Part.MARKER_ADD, Icons.PLUS);
        if (list.isEmpty()) {
            Rect r = layout.markerRow(rows, 0);
            g.text(font, fit(Component.translatable("integratedntwiki.markers.empty").getString(), r.w() - 8), r.x() + 6, r.y() + (r.h() - font.lineHeight) / 2, TEXT_DIM, false);
            return;
        }
        String here = MarkerCodec.canonicalUrl(tab.url());
        for (int i = 0; i < rows && markerScroll + i < list.size(); i++) {
            Marker m = list.get(markerScroll + i);
            Rect r = layout.markerRow(rows, i);
            boolean over = (hover.part() == Part.MARKER_ROW || hover.part() == Part.MARKER_DELETE) && hover.index() == i;
            boolean renaming = m.id().equals(markerRenameId);
            if (m.id().equals(markerHighlight)) g.fill(r.x(), r.y(), r.right(), r.bottom(), SELECTION);
            else if (over && !renaming) g.fill(r.x(), r.y(), r.right(), r.bottom(), BUTTON_HOVER);
            if (m.url().equals(here)) g.fill(r.x(), r.y() + 2, r.x() + 2, r.bottom() - 2, PIN_COLOR); // a marker of the page being viewed
            if (renaming) {
                drawField(g, new Rect(r.x() + 2, r.y() + 1, r.w() - 4, r.h() - 2), markerText, true, "");
                continue;
            }
            g.text(font, fit(m.name(), r.w() - ShellLayout.MARKER_CELL - 10), r.x() + 6, r.y() + (r.h() - font.lineHeight) / 2, TEXT, false);
            Rect del = layout.markerDelete(rows, i);
            if (hover.part() == Part.MARKER_DELETE && hover.index() == i) g.fill(del.x(), del.y(), del.right(), del.bottom(), CLOSE_HOVER);
            Icons.drawCentered(g, Icons.CLOSE, del.x(), del.y(), del.w(), del.h(), over ? TEXT : TEXT_DIM);
        }
        drawBar(g, layout.markerBar(list.size()), markerScroll, BarDrag.MARKERS);
    }

    private void drawContextMenu(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        g.fill(menuX, menuY, menuX + menuW, menuY + menuH, EDGE);
        g.fill(menuX + 1, menuY + 1, menuX + menuW - 1, menuY + menuH - 1, TAB_ACTIVE);
        int hovered = menuItemAt(mouseX, mouseY);
        for (int r = 0; r < menuRows && menuScroll + r < menuItems.size(); r++) {
            int i = menuScroll + r;
            MenuItem it = menuItems.get(i);
            int y = menuY + 2 + r * MENU_ITEM_H;
            if (it.separator()) {
                g.fill(menuX + 4, y + MENU_ITEM_H / 2, menuX + menuW - 4, y + MENU_ITEM_H / 2 + 1, EDGE);
                continue;
            }
            if (i == hovered && it.enabled()) g.fill(menuX + 1, y, menuX + menuW - 1, y + MENU_ITEM_H, BUTTON_HOVER);
            int textY = y + (MENU_ITEM_H - font.lineHeight) / 2 + 1;
            g.text(font, it.label(), menuX + 7, textY, it.enabled() ? TEXT : TEXT_DIM, false);
            if (!it.shortcut().isEmpty()) g.text(font, it.shortcut(), menuX + menuW - 7 - font.width(it.shortcut()), textY, TEXT_DIM, false);
        }
        drawBar(g, menuBar(), menuScroll, BarDrag.MENU);
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
        closeMarkerPanel(true);
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
        if (keysOpen && rebindId != null) { // the next key is the new key: Esc unbinds, it does not close the panel
            consumedKeys.add(key);
            return rebindKey(event);
        }
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
        if (WikiKeys.pressed(WikiKeys.OPEN_CHROMIUM_TEST, event)) { // F9 again: put the window away (the session and its tabs stay)
            consumedKeys.add(key);
            onClose();
            return true;
        }
        if (WikiKeys.pressed(WikiKeys.OPEN_CONTROL_LIST, event)) { // the key map's own key shows / hides it
            consumedKeys.add(key);
            toggleKeys();
            return true;
        }
        if (WikiKeys.pressed(WikiKeys.OPEN_DESKTOP_SHELL, event)) { // the window moves to the desktop (this screen closes, the session stays)
            consumedKeys.add(key);
            DesktopShell.launch();
            return true;
        }
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
        if (markerRenameId != null) { // naming a marker: Enter keeps the name (Esc is handled in escape())
            caretBlinkStart = System.currentTimeMillis();
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) commitMarkerRename();
            else editKey(markerText, event);
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
        } else if (keysOpen) {
            closeKeys();
        } else if (markerRenameId != null) { // Esc while naming: the marker keeps the name it has; the panel stays
            cancelMarkerRename();
        } else if (markerPanelOpen) {
            closeMarkerPanel(true);
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
            case ADD_MARKER -> {
                leaveFields();
                addMarker(false);
            }
            case TOGGLE_MARKERS -> {
                boolean wasOpen = markerPanelOpen;
                leaveFields();
                if (!wasOpen) toggleMarkerPanel();
            }
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
        if (markerRenameId != null) {
            if (markerText.text().length() < MarkerCodec.MAX_NAME) markerText.insert(new String(Character.toChars(event.codepoint())));
            caretBlinkStart = System.currentTimeMillis();
            return true;
        }
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
        endPadWait(); // a controller row must not keep waiting for a button once this screen is gone
        if (session.tabCount() > 0) com.levviata.integratedntwiki.desktop.DesktopShell.rememberSession(); // the next run (or the desktop window) continues on this page
        if (!tearing) session.shell().cancelDrag(); // a tear-off keeps its drag: the desktop window continues it
        if (cursorEdges != WindowResize.NONE) { // the resize cursor must not outlive the screen
            cursorEdges = WindowResize.NONE;
            GLFW.glfwSetCursor(GLFW.glfwGetCurrentContext(), 0L);
        }
        snapZone = SnapZone.NONE;
        snapIntent.reset();
        snapBox = null;
        urlFocused = false;
        draggingField = null;
        BrowserTab shown = session.active();
        if (shown != null) shown.input().cancelAutoscroll(); // nothing would end it while this screen receives no input
        if (findOpen && findTab != null && !findTab.browser().isClosed()) findTab.browser().stopFinding(false);
        closeMarkerPanel(true);
        keysOpen = false;
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
