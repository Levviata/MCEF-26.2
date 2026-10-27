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

import com.levviata.integratedntwiki.IntegratedNTWiki;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import com.mojang.blaze3d.platform.NativeImage;

import java.awt.Rectangle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minecraft 26.2 port of MCEF's renderer. The original uploaded CEF's BGRA frame with raw
 * OpenGL calls (glTexImage2D); 26.x hides the graphics backend behind GpuDevice, so the
 * frame is instead written into a {@link NativeImage} owned by a {@link DynamicTexture}
 * registered with the {@link net.minecraft.client.renderer.texture.TextureManager}.
 * Must only be used from the render thread (CEF is pumped from there, see {@link MCEF#pump()}).
 */
public class MCEFRenderer {
    private static final AtomicInteger IDS = new AtomicInteger();

    private final Identifier id = Identifier.fromNamespaceAndPath(
            IntegratedNTWiki.MODID, "dynamic/browser/" + IDS.incrementAndGet());
    private DynamicTexture texture;
    private int width, height;

    /** Texture to draw, or null until the first frame has arrived. Render thread. Shows a frame offered by another thread first. */
    public Identifier getTexture() {
        flush();
        return texture == null ? null : id;
    }

    // ---- frames from CEF's own thread (the opt-in threaded message loop; with the host-pumped loop onPaint runs on the render thread)

    private final Object handoff = new Object();
    private int[] staging; // the last frame as CEF delivers it (BGRA bytes read as ints), only touched under 'handoff'
    private int stagingW, stagingH;
    private int pendX0, pendY0, pendX1, pendY1; // the dirty area not shown yet; empty when pendX1 <= pendX0
    private boolean pendFull;

    /**
     * Any thread. Keeps a copy of the changed rows of a frame for the render thread to show at its next draw ({@link #getTexture()}). A
     * frame that was not shown yet is merged into the next one, so a render thread that is slow never has a queue to catch up on.
     */
    public void offerFrame(ByteBuffer buffer, int width, int height, Rectangle[] dirtyRects) {
        IntBuffer src = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
        synchronized (handoff) {
            boolean resized = staging == null || width != stagingW || height != stagingH;
            if (resized) {
                staging = new int[width * height];
                stagingW = width;
                stagingH = height;
                pendFull = true;
                dirtyRects = new Rectangle[]{new Rectangle(0, 0, width, height)};
            }
            for (Rectangle r : dirtyRects) {
                int x0 = Math.max(0, r.x), x1 = Math.min(width, r.x + r.width);
                int y0 = Math.max(0, r.y), y1 = Math.min(height, r.y + r.height);
                if (x1 <= x0 || y1 <= y0) continue;
                for (int y = y0; y < y1; y++) {
                    src.position(y * width + x0);
                    src.get(staging, y * width + x0, x1 - x0);
                }
                if (pendX1 <= pendX0) { pendX0 = x0; pendY0 = y0; pendX1 = x1; pendY1 = y1; }
                else { pendX0 = Math.min(pendX0, x0); pendY0 = Math.min(pendY0, y0); pendX1 = Math.max(pendX1, x1); pendY1 = Math.max(pendY1, y1); }
            }
        }
    }

    /** Render thread. Shows what {@link #offerFrame} kept, if anything. Cheap when there is nothing new. */
    public void flush() {
        synchronized (handoff) {
            if (staging == null || (pendX1 <= pendX0 && !pendFull)) return;
            boolean fresh = ensureTexture(stagingW, stagingH);
            NativeImage image = texture.getPixels();
            if (image == null) return;
            int x0 = pendX0, y0 = pendY0, x1 = pendX1, y1 = pendY1;
            if (fresh || pendFull) { x0 = 0; y0 = 0; x1 = stagingW; y1 = stagingH; }
            for (int y = y0; y < y1; y++) {
                int row = y * stagingW;
                for (int x = x0; x < x1; x++) {
                    int argb = staging[row + x];
                    image.setPixelABGR(x, y, (argb & 0xFF00FF00) | ((argb >> 16) & 0xFF) | ((argb & 0xFF) << 16));
                }
            }
            texture.upload();
            pendX0 = pendY0 = pendX1 = pendY1 = 0;
            pendFull = false;
        }
    }

    /** (Re)creates the texture when the size changed. @return true when it is new and has to be filled completely */
    private boolean ensureTexture(int width, int height) {
        if (texture != null && width == this.width && height == this.height) return false;
        releaseTexture();
        this.width = width;
        this.height = height;
        NativeImage image = new NativeImage(NativeImage.Format.RGBA, width, height, false);
        texture = new DynamicTexture(id::toString, image);
        Minecraft.getInstance().getTextureManager().register(id, texture);
        return true;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    /** Copies the dirty rectangles of a CEF frame (BGRA, {@code width}x{@code height}) into the texture. */
    public void onPaint(ByteBuffer buffer, int width, int height, Rectangle[] dirtyRects) {
        if (texture == null || width != this.width || height != this.height) {
            releaseTexture();
            this.width = width;
            this.height = height;
            NativeImage image = new NativeImage(NativeImage.Format.RGBA, width, height, false);
            texture = new DynamicTexture(id::toString, image);
            Minecraft.getInstance().getTextureManager().register(id, texture);
            dirtyRects = new Rectangle[]{new Rectangle(0, 0, width, height)};
        }
        NativeImage image = texture.getPixels();
        if (image == null) return;
        // CEF delivers BGRA bytes: read little-endian that is ARGB; NativeImage wants ABGR.
        IntBuffer pixels = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
        for (Rectangle rect : dirtyRects) {
            int x1 = Math.min(width, rect.x + rect.width), y1 = Math.min(height, rect.y + rect.height);
            for (int y = Math.max(0, rect.y); y < y1; y++) {
                for (int x = Math.max(0, rect.x); x < x1; x++) {
                    int argb = pixels.get(y * width + x);
                    image.setPixelABGR(x, y, (argb & 0xFF00FF00) | ((argb >> 16) & 0xFF) | ((argb & 0xFF) << 16));
                }
            }
        }
        texture.upload();
    }

    /** Releases the texture; safe to call repeatedly. */
    public void cleanup() {
        synchronized (handoff) {
            staging = null;
            pendX0 = pendY0 = pendX1 = pendY1 = 0;
            pendFull = false;
        }
        releaseTexture();
    }

    private void releaseTexture() {
        if (texture != null) {
            Minecraft.getInstance().getTextureManager().release(id);
            texture = null;
        }
    }
}
