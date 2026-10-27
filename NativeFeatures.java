package com.levviata.integratedntwiki.mcef;

/**
 * Reads the string MCEF's libjcef reports about itself: {@code "<build id> | <feature> <feature> ..."}. Pure (no JCEF or Minecraft
 * types), so it is tested without the game.
 */
public final class NativeFeatures {
    private NativeFeatures() {}

    /** @return whether {@code info} lists {@code feature} as a whole word after the {@code |}; null (an upstream library) has none */
    public static boolean has(String info, String feature) {
        if (info == null || feature == null) return false;
        int bar = info.indexOf('|');
        if (bar < 0) return false;
        for (String f : info.substring(bar + 1).trim().split("\\s+")) if (f.equals(feature)) return true;
        return false;
    }

    /** @return the build id part of {@code info} (before the {@code |}), or null */
    public static String buildId(String info) {
        if (info == null) return null;
        int bar = info.indexOf('|');
        return (bar < 0 ? info : info.substring(0, bar)).trim();
    }
}
