package com.levviata.integratedntwiki.mcef;

import com.levviata.integratedntwiki.WikiLog;

import org.cef.browser.CefFrame;
import org.lwjgl.glfw.GLFW;

/**
 * Chromium-to-OS clipboard bridge.
 * <p>
 * Problem: in this off-screen browser Ctrl+C reached Chromium but its selection did not end up on the OS clipboard, so
 * nothing could be pasted in the URL bar or in other applications. Fix: Ctrl+C / Ctrl+X (and Ctrl+Insert / Shift+Delete)
 * are no longer forwarded as keys. The shell asks the page for its selected text ({@link PageQuery}, run in the focused
 * frame, so a selection inside an iframe works too) and puts that text on the real OS clipboard with GLFW, the same
 * clipboard the URL bar and every other application use. There is no internal clipboard.
 * <ul>
 *   <li>The selection itself is untouched (nothing is cleared or changed); text selected by mouse, keyboard, double or
 *       triple click, or Select all is read the same way, including text inside input and textarea fields.</li>
 *   <li>Cut copies the same way and then deletes the selection, in editable fields only; in a read-only page it does
 *       nothing, as in Chrome. Password fields are never copied.</li>
 *   <li>No selection: the clipboard is left alone.</li>
 *   <li>If the page does not answer in time (blocked script, navigating) Chromium's own {@code frame.copy()} /
 *       {@code frame.cut()} runs instead, so this is never worse than before.</li>
 * </ul>
 * Paste is not bridged: Chromium reads the OS clipboard itself, and what is copied now is on it.
 */
public final class ClipboardBridge {
    private static final int MAX_CHARS = 1_000_000;

    private ClipboardBridge() {}

    /** The text on the OS clipboard, or null when there is none (empty, or not text). */
    static String read() {
        try {
            return GLFW.glfwGetClipboardString(GLFW.glfwGetCurrentContext());
        } catch (Throwable t) {
            MCEF.LOGGER.warn("Could not read the OS clipboard", t);
            return null;
        }
    }

    /** Puts plain text on the OS clipboard. @return false for empty text or when the clipboard refused it */
    static boolean write(String text) {
        if (text == null || text.isEmpty()) return false;
        try {
            GLFW.glfwSetClipboardString(GLFW.glfwGetCurrentContext(), text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS) : text);
            return true;
        } catch (Throwable t) {
            MCEF.LOGGER.warn("Could not write the OS clipboard", t);
            return false;
        }
    }

    /** Ctrl+C (or Ctrl+X when {@code cut}) in the page. */
    public static void copy(MCEFBrowser browser, boolean cut) {
        CefFrame frame = browser.getFocusedFrame();
        if (frame == null) return;
        String body = "var cut=" + cut + ";var a=document.activeElement,t='',field=false,edit=false;"
                + "if(a&&(a.tagName==='TEXTAREA'||a.tagName==='INPUT')){field=true;edit=!a.readOnly&&!a.disabled;}"
                + "else if(a&&a.isContentEditable){edit=true;}"
                + "if(field){if(a.type!=='password'&&typeof a.selectionStart==='number'&&a.selectionStart!==a.selectionEnd)"
                + "{t=a.value.substring(a.selectionStart,a.selectionEnd);}}"
                + "else{var s=window.getSelection();t=s?s.toString():'';}"
                + "if(cut){if(!edit||t===''){return '';}document.execCommand('delete');}"
                + "return t;";
        PageQuery.ask(browser, frame, body, text -> {
            if (text.isEmpty()) return; // nothing selected: the clipboard keeps what it had
            if (write(text)) WikiLog.info("{} {} chars from the page to the OS clipboard", cut ? "Cut" : "Copied", text.length());
        }, () -> {
            if (browser.isClosed()) return;
            MCEF.LOGGER.warn("The page did not answer the copy request; using Chromium's own {}", cut ? "cut" : "copy");
            if (cut) frame.cut();
            else frame.copy();
        });
    }
}
