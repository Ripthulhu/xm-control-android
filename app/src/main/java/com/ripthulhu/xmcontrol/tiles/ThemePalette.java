package com.ripthulhu.xmcontrol.tiles;

import android.content.Context;
import android.graphics.Color;
import android.os.Build;

final class ThemePalette {
    final int background;
    final int surface;
    final int surfaceVariant;
    final int primary;
    final int onPrimary;
    final int text;
    final int muted;
    final int sliderTrack;

    private ThemePalette(
            int background,
            int surface,
            int surfaceVariant,
            int primary,
            int onPrimary,
            int text,
            int muted,
            int sliderTrack) {
        this.background = background;
        this.surface = surface;
        this.surfaceVariant = surfaceVariant;
        this.primary = primary;
        this.onPrimary = onPrimary;
        this.text = text;
        this.muted = muted;
        this.sliderTrack = sliderTrack;
    }

    static ThemePalette from(Context context) {
        return new ThemePalette(
                systemColor(context, "system_neutral1_900", Color.rgb(13, 15, 17)),
                systemColor(context, "system_neutral1_800", Color.rgb(37, 42, 46)),
                systemColor(context, "system_neutral2_700", Color.rgb(46, 54, 62)),
                systemColor(context, "system_accent1_100", Color.rgb(205, 216, 210)),
                systemColor(context, "system_accent1_900", Color.rgb(22, 29, 26)),
                systemColor(context, "system_neutral1_50", Color.rgb(244, 247, 250)),
                systemColor(context, "system_neutral1_200", Color.rgb(198, 205, 214)),
                systemColor(context, "system_neutral2_600", Color.rgb(91, 99, 106)));
    }

    private static int systemColor(Context context, String name, int fallback) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return fallback;
        }

        int resourceId = context.getResources().getIdentifier(name, "color", "android");
        if (resourceId == 0) {
            return fallback;
        }

        try {
            return context.getColor(resourceId);
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }
}
