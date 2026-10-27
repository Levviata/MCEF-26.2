/*
 *     MCEF (Minecraft Chromium Embedded Framework)
 *     Copyright (C) 2023 CinemaMod Group
 *
 *     This library is free software; you can redistribute it and/or
 *     modify it under the terms of the GNU Lesser General Public
 *     License as published by the Free Software Foundation; either
 *     version 2.1 of the License, or (at your option) any later version.
 *
 *     This library is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *     Lesser General Public License for more details.
 *
 *     You should have received a copy of the GNU Lesser General Public
 *     License along with this library; if not, write to the Free Software
 *     Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301
 *     USA
 */

package com.levviata.integratedntwiki.mcef;

import org.cef.browser.CefBrowser;
import org.cef.browser.CefBrowserOsr;
import org.cef.callback.CefDragData;
import org.cef.event.CefKeyEvent;
import org.cef.event.CefMouseEvent;
import org.cef.event.CefMouseWheelEvent;
import org.cef.misc.CefCursorType;
import org.cef.handler.CefScreenInfo;
import org.lwjgl.glfw.GLFW;

import java.awt.Point;
import com.levviata.integratedntwiki.shell.desktop.FrameSink;
import java.awt.Rectangle;
import java.nio.ByteBuffer;


import static org.lwjgl.glfw.GLFW.*;

/**
 * An instance of an "Off-screen rendered" Chromium web browser.
 * Complete with a renderer, keyboard and mouse inputs, optional
 * browser control shortcuts, cursor handling, drag & drop support.
 */
public class MCEFBrowser extends CefBrowserOsr {
    /**
     * The renderer for the browser.
     */
    private final MCEFRenderer renderer;
    /**
     * Stores information about drag & drop.
     */
    private final MCEFDragContext dragContext = new MCEFDragContext();
    /**
     * A listener that defines that happens when a cursor changes in the browser.
     * E.g. when you've hovered over a button, an input box, are selecting text, etc...
     * A default listener is created in the constructor that sets the cursor type to
     * the appropriate cursor based on the event.
     */
    private MCEFCursorChangeListener cursorChangeListener;
    /**
     * Whether this class itself mimics browser shortcuts (Ctrl+R, Alt+Left, Ctrl+wheel zoom...), as the original MCEF does.
     * Off here: the browser window owns every shortcut in one place ({@code Shortcuts} and the screen), so a key can never
     * be handled twice. Turn it on only for a bare browser with no shell around it.
     */
    private boolean browserControls = false;
    /**
     * Used to track when a full repaint should occur.
     */
    private int lastWidth = 0, lastHeight = 0;
    /**
     * A bitset representing what mouse buttons are currently pressed.
     * CEF is a bit odd and implements mouse buttons as a part of modifier flags.
     */
    private int btnMask = 0;
    /**
     * Whether MCEF should automatically calculate the device scale factor.
     * The device scale factor is used to determine how content should be scaled based on the
     * DPI of the monitor that the window is on. This is important for high-DPI displays where
     * the device scale factor is greater than 1.
     */
    private boolean autoDSF;
    private double deviceScaleFactor = 1;

    public MCEFBrowser(MCEFClient client, String url, boolean transparent) {
        this(client, url, transparent, false);
    }

    public MCEFBrowser(MCEFClient client, String url, boolean transparent, boolean autoDSF) {
        super(client.getHandle(), url, transparent, null);
        renderer = new MCEFRenderer();
        cursorChangeListener = (cefCursorID) -> setCursor(CefCursorType.fromId(cefCursorID));
        this.autoDSF = autoDSF;

        MCEF.browserCreated();
    }

    public MCEFRenderer getRenderer() {
        return renderer;
    }

    public void setAutoDSF(boolean autoDSF) {
      this.autoDSF = autoDSF;
    }

    public boolean isAutoDSF() {
      return autoDSF;
    }

    public void setDeviceScaleFactor(double deviceScaleFactor) {
      this.deviceScaleFactor = deviceScaleFactor;
    }

    public double getDeviceScaleFactor() {
      if (autoDSF) {
        long window = GLFW.glfwGetCurrentContext();

        int[] fbWidth = new int[1];
        int[] fbHeight = new int[1];
        GLFW.glfwGetFramebufferSize(window, fbWidth, fbHeight);

        int[] winWidth = new int[1];
        int[] winHeight = new int[1];
        GLFW.glfwGetWindowSize(window, winWidth, winHeight);

        // The device scale factor is the ratio of the allocated framebuffer size to the window size
        // https://stackoverflow.com/questions/44719635/what-is-the-difference-between-glfwgetwindowsize-and-glfwgetframebuffersize
        return Math.max(1, Math.min(fbWidth[0] / winWidth[0], fbHeight[0] / winHeight[0]));
      } else {
          return deviceScaleFactor > 0 ? deviceScaleFactor : 1;
      }
    }

    public int scaleX(int x) {
        return (int) (x / getDeviceScaleFactor());
    }

    public int scaleY(int y) {
        return (int) (y / getDeviceScaleFactor());
    }

    public boolean getScreenInfo(CefBrowser browser, CefScreenInfo screenInfo) {
        super.getScreenInfo(browser, screenInfo);
        screenInfo.device_scale_factor = getDeviceScaleFactor();
        return true;
    }

    public MCEFCursorChangeListener getCursorChangeListener() {
        return cursorChangeListener;
    }

    public void setCursorChangeListener(MCEFCursorChangeListener cursorChangeListener) {
        this.cursorChangeListener = cursorChangeListener;
    }

    public boolean usingBrowserControls() {
        return browserControls;
    }

    /**
     * Enabling browser controls tells MCEF to mimic the behavior of an actual browser.
     * CTRL+R for reload, CTRL+Left for back, CTRL+Right for forward, etc...
     *
     * @param browserControls whether browser controls should be enabled
     * @return the browser instance
     */
    public MCEFBrowser useBrowserControls(boolean browserControls) {
        this.browserControls = browserControls;
        return this;
    }

    public MCEFDragContext getDragContext() {
        return dragContext;
    }

    // Graphics
    // Select-box popups (CEF's popup layer) are not composited yet: the original MCEF code for it
    // used raw GL sub-uploads and is deferred to the input phase. Only the main view is drawn.
    @Override
    public void onPaint(CefBrowser browser, boolean popup, Rectangle[] dirtyRects, ByteBuffer buffer, int width, int height) {
        if (popup || dirtyRects.length == 0 || hidden) return;
        long probe = com.levviata.integratedntwiki.shell.FrameProbe.begin(); // 0 (and free) unless -Dintegratedntwiki.probe=true
        FrameSink target = sink;
        if (target != null) { // shown in a desktop window: the Minecraft texture is not needed (and not kept up to date)
            target.onFrame(buffer, width, height, dirtyRects);
            com.levviata.integratedntwiki.shell.FrameProbe.paint("desktop", probe, width, height);
            return;
        }
        renderer.onPaint(buffer, width, height, dirtyRects);
        com.levviata.integratedntwiki.shell.FrameProbe.paint("mc", probe, width, height);
    }

    private volatile FrameSink sink;

    /** Sends frames to {@code sink} instead of the Minecraft texture; null goes back to the texture (and repaints it fully). */
    public void setFrameSink(FrameSink sink) {
        FrameSink before = this.sink;
        this.sink = sink;
        if (before != sink && !isClosed()) invalidate(); // the new target has no picture yet
    }

    public FrameSink getFrameSink() {
        return sink;
    }

    private boolean hidden;

    /**
     * A background tab: its frames are not copied into the texture (the java-cef native cannot pause an off-screen
     * browser, so this is what keeps hidden tabs cheap). Showing it again forces a full repaint.
     */
    public void setHidden(boolean hidden) {
        if (this.hidden == hidden) return;
        this.hidden = hidden;
        if (!hidden) invalidate();
    }

    public boolean isHidden() {
        return hidden;
    }

    /** Whether any mouse button is currently held down inside the page. */
    public boolean hasButtonsDown() {
        return btnMask != 0;
    }

    /** Tells Chromium the pointer left the view (clears hover state; it re-evaluates the cursor on re-entry). */
    public void sendMouseExit(int mouseX, int mouseY) {
        sendMouseEvent(new CefMouseEvent(CefMouseEvent.MOUSE_EXIT, scaleX(mouseX), scaleY(mouseY), 0, 0, 0));
    }

    public void resize(int width, int height) {
        width = scaleX(width);
        height = scaleY(height);

        browser_rect_.setBounds(0, 0, width, height);
        wasResized(width, height);
    }

    // Inputs
    public void sendKeyPress(int keyCode, long scanCode, int modifiers) {
        if (browserControls) {
            if (modifiers == GLFW_MOD_CONTROL) {
                if (keyCode == GLFW_KEY_R) {
                    reload();
                    return;
                } else if (keyCode == GLFW_KEY_EQUAL) {
                    if (getZoomLevel() < 9) setZoomLevel(getZoomLevel() + 1);
                    return;
                } else if (keyCode == GLFW_KEY_MINUS) {
                    if (getZoomLevel() > -9) setZoomLevel(getZoomLevel() - 1);
                    return;
                } else if (keyCode == GLFW_KEY_0) {
                    setZoomLevel(0);
                    return;
                }
            } else if (modifiers == GLFW_MOD_ALT) {
                if (keyCode == GLFW_KEY_LEFT && canGoBack()) {
                    goBack();
                    return;
                } else if (keyCode == GLFW_KEY_RIGHT && canGoForward()) {
                    goForward();
                    return;
                }
            }
        }

        CefKeyEvent e = new CefKeyEvent(CefKeyEvent.KEY_PRESS, keyCode, (char) keyCode, modifiers);
        e.scancode = scanCode;
        sendKeyEvent(e);
    }

    public void sendKeyRelease(int keyCode, long scanCode, int modifiers) {
        if (browserControls) {
            if (modifiers == GLFW_MOD_CONTROL) {
                if (keyCode == GLFW_KEY_R) return;
                else if (keyCode == GLFW_KEY_EQUAL) return;
                else if (keyCode == GLFW_KEY_MINUS) return;
                else if (keyCode == GLFW_KEY_0) return;
            } else if (modifiers == GLFW_MOD_ALT) {
                if (keyCode == GLFW_KEY_LEFT && canGoBack()) return;
                else if (keyCode == GLFW_KEY_RIGHT && canGoForward()) return;
            }
        }

        CefKeyEvent e = new CefKeyEvent(CefKeyEvent.KEY_RELEASE, keyCode, (char) keyCode, modifiers);
        e.scancode = scanCode;
        sendKeyEvent(e);
    }

    public void sendKeyTyped(char c, int modifiers) {
        if (browserControls) {
            if (modifiers == GLFW_MOD_CONTROL) {
                if ((int) c == GLFW_KEY_R) return;
                else if ((int) c == GLFW_KEY_EQUAL) return;
                else if ((int) c == GLFW_KEY_MINUS) return;
                else if ((int) c == GLFW_KEY_0) return;
            } else if (modifiers == GLFW_MOD_ALT) {
                if ((int) c == GLFW_KEY_LEFT && canGoBack()) return;
                else if ((int) c == GLFW_KEY_RIGHT && canGoForward()) return;
            }
        }

        CefKeyEvent e = new CefKeyEvent(CefKeyEvent.KEY_TYPE, c, c, modifiers);
        sendKeyEvent(e);
    }

    /**
     * @param keyFlags GLFW modifier bits ({@code GLFW_MOD_CONTROL} ...) for the keys held right now. Without them
     *                 Chromium cannot tell Ctrl+click (background tab) or Shift+click from a plain click.
     */
    public void sendMouseMove(int mouseX, int mouseY, int keyFlags) {
        mouseX = scaleX(mouseX);
        mouseY = scaleY(mouseY);

        CefMouseEvent e = new CefMouseEvent(CefMouseEvent.MOUSE_MOVED, mouseX, mouseY, 0, 0, dragContext.getVirtualModifiers(btnMask) | keyFlags);
        sendMouseEvent(e);

        if (dragContext.isDragging())
            this.dragTargetDragOver(new Point(mouseX, mouseY), 0, dragContext.getMask());
    }

    // TODO: it may be necessary to add modifiers here
    /**
     * @param button     Minecraft/GLFW numbering: 0 left, 1 right, 2 middle
     * @param clickCount 1 for a single click, 2 for a double-click (word select), 3 for a triple-click (paragraph).
     *                   Chromium never counts clicks itself, so this must be right or double-click does nothing.
     */
    public void sendMousePress(int mouseX, int mouseY, int button, int clickCount, int keyFlags) {
        mouseX = scaleX(mouseX);
        mouseY = scaleY(mouseY);

        // MC/GLFW numbers right=1, middle=2; the java-cef native expects left=0, middle=1, right=2.
        if (button == 1) button = 2;
        else if (button == 2) button = 1;

        if (button == 0) btnMask |= CefMouseEvent.BUTTON1_MASK;
        else if (button == 1) btnMask |= CefMouseEvent.BUTTON2_MASK;
        else if (button == 2) btnMask |= CefMouseEvent.BUTTON3_MASK;

        CefMouseEvent e = new CefMouseEvent(GLFW_PRESS, mouseX, mouseY, clickCount, button, btnMask | keyFlags);
        sendMouseEvent(e);
    }

    // TODO: it may be necessary to add modifiers here
    /** @param clickCount the same count that was sent with the matching press (Chromium needs both to agree) */
    public void sendMouseRelease(int mouseX, int mouseY, int button, int clickCount, int keyFlags) {
        mouseX = scaleX(mouseX);
        mouseY = scaleY(mouseY);

        // Same MC -> java-cef button renumbering as in sendMousePress.
        if (button == 1) button = 2;
        else if (button == 2) button = 1;

        if (button == 0 && (btnMask & CefMouseEvent.BUTTON1_MASK) != 0) btnMask ^= CefMouseEvent.BUTTON1_MASK;
        else if (button == 1 && (btnMask & CefMouseEvent.BUTTON2_MASK) != 0) btnMask ^= CefMouseEvent.BUTTON2_MASK;
        else if (button == 2 && (btnMask & CefMouseEvent.BUTTON3_MASK) != 0) btnMask ^= CefMouseEvent.BUTTON3_MASK;

        CefMouseEvent e = new CefMouseEvent(GLFW_RELEASE, mouseX, mouseY, clickCount, button, btnMask | keyFlags);
        sendMouseEvent(e);

        // drag&drop
        if (dragContext.isDragging()) {
            if (button == 0) {
                finishDragging(mouseX, mouseY);
            }
        }
    }

    // TODO: smooth scrolling
    /** @param modifiers GLFW modifier bits; with {@code GLFW_MOD_SHIFT} the native code turns the delta horizontal, like Shift+wheel in any browser */
    public void sendMouseWheel(int mouseX, int mouseY, double amount, int modifiers) {
        mouseX = scaleX(mouseX);
        mouseY = scaleY(mouseY);

        if (browserControls) {
            if ((modifiers & GLFW_MOD_CONTROL) != 0) {
                if (amount > 0) {
                    if (getZoomLevel() < 9) setZoomLevel(getZoomLevel() + 1);
                } else if (getZoomLevel() > -9) setZoomLevel(getZoomLevel() - 1);
                return;
            }
        }

        // macOS generally has a slow scroll speed that feels more natural with their magic mice / trackpads
        if (!MCEFPlatform.getPlatform().isMacOS()) {
            // This removes the feeling of "smooth scroll"
            if (amount < 0) {
                amount = Math.floor(amount);
            } else {
                amount = Math.ceil(amount);
            }

            // This feels about equivalent to chromium with smooth scrolling disabled -ds58
            amount = amount * 3;
        }

        CefMouseWheelEvent e = new CefMouseWheelEvent(CefMouseWheelEvent.WHEEL_UNIT_SCROLL, mouseX, mouseY, amount, modifiers);
        sendMouseWheelEvent(e);
    }

    // Drag & drop
    @Override
    public boolean startDragging(CefBrowser browser, CefDragData dragData, int mask, int x, int y) {
        x = scaleX(x);
        y = scaleY(y);

        dragContext.startDragging(dragData, mask);
        this.dragTargetDragEnter(dragContext.getDragData(), new Point(x, y), btnMask, dragContext.getMask());
        // Indicates to CEF to not handle the drag event natively
        // reason: native drag handling doesn't work with off screen rendering
        return false;
    }

    @Override
    public void updateDragCursor(CefBrowser browser, int operation) {
        if (dragContext.updateCursor(operation))
            // If the cursor to display for the drag event changes, then update the cursor
            this.onCursorChange(this, dragContext.getVirtualCursor(dragContext.getActualCursor()));

        super.updateDragCursor(browser, operation);
    }

    // Expose drag & drop functions
    public void startDragging(CefDragData dragData, int mask, int x, int y) { // Overload since the JCEF method requires a browser, which then goes unused
      x = scaleX(x);
      y = scaleY(y);  
      
      startDragging(dragData, mask, x, y);
    }

    public void finishDragging(int x, int y) {
        x = scaleX(x);
        y = scaleY(y);

        dragTargetDrop(new Point(x, y), btnMask);
        dragTargetDragLeave();
        dragContext.stopDragging();
        this.onCursorChange(this, dragContext.getActualCursor());
    }

    public void cancelDrag() {
        dragTargetDragLeave();
        dragContext.stopDragging();
        this.onCursorChange(this, dragContext.getActualCursor());
    }

    // Closing
    private boolean closed;

    public boolean isClosed() {
        return closed;
    }

    /** Releases the Chromium browser and its texture. Idempotent. */
    public void close() {
        if (closed) return;
        closed = true;
        renderer.cleanup();
        cursorChangeListener.onCursorChange(0);
        super.close(true);
        MCEF.browserClosed();
    }

    // Cursor handling
    @Override
    public boolean onCursorChange(CefBrowser browser, int cursorType) {
        cursorType = dragContext.getVirtualCursor(cursorType);
        pageCursor = cursorType;
        cursorChangeListener.onCursorChange(cursorType);
        return super.onCursorChange(browser, cursorType);
    }

    /** The cursor the page last asked for (Chromium only re-sends it when it changes). */
    private int pageCursor = 0;

    /**
     * Whether Chromium's middle-click autoscroll is running. Chromium announces it only through the cursor: it shows one
     * of the nine "panning" cursors for as long as the autoscroll lasts and goes back to a normal one when it ends.
     */
    public boolean isAutoscrolling() {
        return isPanning(pageCursor);
    }

    static boolean isPanning(int cursorId) {
        return cursorId >= CefCursorType.MIDDLE_PANNING.ordinal() && cursorId <= CefCursorType.WEST_PANNING.ordinal();
    }

    /** Puts the page's own cursor back, e.g. when the pointer re-enters the view after crossing the browser shell. */
    public void restoreCursor() {
        cursorChangeListener.onCursorChange(pageCursor);
    }

    public void setCursor(CefCursorType cursorType) {
        if (cursorType == CefCursorType.NONE) {
            GLFW.glfwSetInputMode(GLFW.glfwGetCurrentContext(), GLFW_CURSOR, GLFW_CURSOR_HIDDEN);
        } else {
            GLFW.glfwSetInputMode(GLFW.glfwGetCurrentContext(), GLFW_CURSOR, GLFW_CURSOR_NORMAL);
            GLFW.glfwSetCursor(GLFW.glfwGetCurrentContext(), MCEF.getGLFWCursorHandle(cursorType));
        }
    }
}
