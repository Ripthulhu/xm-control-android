package com.ripthulhu.xmcontrol.tiles;

import android.widget.SeekBar;

final class SmoothSlider {
    private static final int VISUAL_STEPS_PER_VALUE = 100;

    private SmoothSlider() {
    }

    static void configure(SeekBar seekBar, int logicalMax) {
        seekBar.setMax(toVisual(logicalMax));
        seekBar.setKeyProgressIncrement(VISUAL_STEPS_PER_VALUE);
    }

    static int value(SeekBar seekBar) {
        return Math.max(0, (seekBar.getProgress() + VISUAL_STEPS_PER_VALUE / 2)
                / VISUAL_STEPS_PER_VALUE);
    }

    static void setValue(SeekBar seekBar, int value) {
        seekBar.setProgress(Math.min(seekBar.getMax(), toVisual(Math.max(0, value))));
    }

    static boolean isAtValue(SeekBar seekBar, int value) {
        return seekBar.getProgress() == toVisual(value);
    }

    private static int toVisual(int value) {
        return value * VISUAL_STEPS_PER_VALUE;
    }
}
