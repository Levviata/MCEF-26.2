package com.levviata.integratedntwiki.mcef;

import com.levviata.integratedntwiki.WikiLog;

import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.callback.CefContextMenuParams;
import org.cef.callback.CefMenuModel;
import org.cef.handler.CefContextMenuHandlerAdapter;

/**
 * Chromium's context menu for an off-screen browser. Chromium decides WHEN a menu is wanted and WHAT it is about
 * (link, selection, editable field, image); an off-screen browser cannot draw the native menu (with no handler CEF runs
 * a modal OS menu at the off-screen view's fake origin), so the native model is cancelled and the request is recorded
 * here. The screen draws its own Minecraft menu from the record and runs each item through Chromium's own editing and
 * navigation calls (cut/copy/paste/undo/redo, back/forward/reload). The right-click itself still reaches the page.
 */
final class BrowserContextMenu extends CefContextMenuHandlerAdapter {
    /** What the menu is about, copied out of the CEF params (which are only valid inside the callback). */
    record Request(CefBrowser browser, CefFrame frame, String linkUrl, String selection, String imageUrl, boolean editable, int editFlags) {}

    private static volatile Request pending;

    /** The menu Chromium asked for since the last call, or null. */
    static Request take() {
        Request r = pending;
        pending = null;
        return r;
    }

    @Override
    public void onBeforeContextMenu(CefBrowser browser, CefFrame frame, CefContextMenuParams params, CefMenuModel model) {
        String link = params.getLinkUrl();
        String selection = params.getSelectionText();
        String image = params.getMediaType() == CefContextMenuParams.MediaType.CM_MEDIATYPE_IMAGE ? params.getSourceUrl() : null;
        WikiLog.debug("Chromium context menu requested (link={}, selection={} chars, image={}, editable={})",
                link, selection == null ? 0 : selection.length(), image, params.isEditable());
        pending = new Request(browser, frame, link == null || link.isEmpty() ? null : link,
                selection == null ? "" : selection, image == null || image.isEmpty() ? null : image,
                params.isEditable(), params.getEditStateFlags());
        model.clear(); // cancels the native menu
    }
}
