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

    /** Texture to draw, or null until the first frame has arrived. */
    public Identifier getTexture() {
        return texture == null ? null : id;
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
            cleanup();
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
        if (texture != null) {
            Minecraft.getInstance().getTextureManager().release(id);
            texture = null;
        }
    }
}
