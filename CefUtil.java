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

import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.CefSettings;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashSet;
import java.util.Set;

/**
 * This class mostly just interacts with org.cef.* for internal use in {@link MCEF}.
 * Differences from MCEF 2.1.6: no --disable-web-security / widevine / autoplay switches (this browser is
 * restricted to one site and must keep Chromium's isolation), and the cache lives next to the libraries.
 */
final class CefUtil {
    private CefUtil() {
    }

    private static boolean init;
    private static CefApp cefAppInstance;
    private static CefClient cefClientInstance;

    private static void setUnixExecutable(File file) {
        Set<PosixFilePermission> perms = new HashSet<>();
        perms.add(PosixFilePermission.OWNER_READ);
        perms.add(PosixFilePermission.OWNER_WRITE);
        perms.add(PosixFilePermission.OWNER_EXECUTE);

        try {
            Files.setPosixFilePermissions(file.toPath(), perms);
        } catch (IOException e) {
            MCEF.getLogger().error("Failed to set " + file + " as executable.", e);
        }
    }

    static boolean init() {
        MCEFPlatform platform = MCEFPlatform.getPlatform();

        // Ensure binaries are executable
        if (platform.isLinux()) {
            File jcefHelperFile = new File(System.getProperty("mcef.libraries.path"), platform.getNormalizedName() + "/jcef_helper");
            setUnixExecutable(jcefHelperFile);
        } else if (platform.isMacOS()) {
            File mcefLibrariesPath = new File(System.getProperty("mcef.libraries.path"));
            // The archive also has "jcef Helper (Alerts)". ArchiveSupport restores the modes on extraction; this is the fallback for a runtime
            // an older version of the mod unpacked (its files have no executable bit).
            for (String helper : new String[]{"jcef Helper", "jcef Helper (GPU)", "jcef Helper (Plugin)", "jcef Helper (Renderer)", "jcef Helper (Alerts)"}) {
                setUnixExecutable(new File(mcefLibrariesPath, platform.getNormalizedName()
                        + "/jcef_app.app/Contents/Frameworks/" + helper + ".app/Contents/MacOS/" + helper));
            }
        }

        // Chromium's own middle-click autoscroll (the scroll-by-dragging mode). It is a renderer feature that is
        // on by default on Windows only; this makes it available on every platform. An unknown feature name is ignored.
        String[] cefSwitches = {"--enable-features=MiddleClickAutoscroll"};
        if (!CefApp.startup(cefSwitches)) {
            return false;
        }

        // Which libjcef is this? MCEF's own build has extra features; the CinemaMod download does not (and the mod then works as before).
        String nativeInfo = NativeExt.buildInfo();
        MCEF.getLogger().info("Chromium runtime: {}", nativeInfo == null ? "upstream java-cef natives (no MCEF extensions: Chromium stays at 30 fps, host-pumped loop)" : nativeInfo);
        boolean threaded = NativeExt.requestThreadedLoop(MCEF.threadedLoopRequested(), platform.isWindows());
        if (MCEF.threadedLoopRequested() && !threaded)
            MCEF.getLogger().warn("The threaded Chromium loop was requested but is not available here (needs Windows and MCEF's libjcef): using the host-pumped loop");
        else if (threaded)
            MCEF.getLogger().warn("Chromium runs its own message loop thread (experimental): its callbacks arrive off the render thread");

        CefSettings cefSettings = new CefSettings();
        cefSettings.windowless_rendering_enabled = true;
        Path cache = new File(System.getProperty("mcef.libraries.path")).toPath().resolveSibling("cache");
        cefSettings.cache_path = cache.toAbsolutePath().toString(); // jcef wants an absolute path
        cefSettings.background_color = cefSettings.new ColorType(0, 255, 255, 255);
        cefSettings.user_agent_product = "IntegratedNTWiki/1";

        cefAppInstance = CefApp.getInstance(cefSwitches, cefSettings);
        cefClientInstance = cefAppInstance.createClient();

        return init = true;
    }

    static void shutdown() {
        if (isInit()) {
            init = false;
            cefClientInstance.dispose();
            cefAppInstance.dispose();
        }
    }

    static boolean isInit() {
        return init;
    }

    static CefApp getCefApp() {
        return cefAppInstance;
    }

    static CefClient getCefClient() {
        return cefClientInstance;
    }
}
