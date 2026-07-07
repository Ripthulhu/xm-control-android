package com.ripthulhu.xmcontrol.tiles.bluetooth;

public final class SonyCommand {
    public static final SonyCommand SPEAK_TO_CHAT_ON = new SonyCommand(new byte[]{(byte) 0xF8, 0x02, 0x00, 0x00});
    public static final SonyCommand SPEAK_TO_CHAT_OFF = new SonyCommand(new byte[]{(byte) 0xF8, 0x02, 0x01, 0x01});
    public static final SonyCommand WEAR_PAUSE_ON = new SonyCommand(new byte[]{(byte) 0xF8, 0x01, 0x00});
    public static final SonyCommand WEAR_PAUSE_OFF = new SonyCommand(new byte[]{(byte) 0xF8, 0x01, 0x01});
    public static final SonyCommand DSEE_AUTO = new SonyCommand(new byte[]{(byte) 0xE8, 0x01, 0x01});
    public static final SonyCommand DSEE_OFF = new SonyCommand(new byte[]{(byte) 0xE8, 0x01, 0x00});
    public static final SonyCommand CONNECTION_QUALITY = new SonyCommand(new byte[]{(byte) 0xE8, 0x00, 0x00});
    public static final SonyCommand CONNECTION_STABILITY = new SonyCommand(new byte[]{(byte) 0xE8, 0x00, 0x01});
    public static final SonyCommand MULTIPOINT_ON = new SonyCommand(new byte[]{(byte) 0xD8, (byte) 0xD2, 0x00, 0x00});
    public static final SonyCommand MULTIPOINT_OFF = new SonyCommand(new byte[]{(byte) 0xD8, (byte) 0xD2, 0x00, 0x01});
    public static final SonyCommand TOUCH_PANEL_ON = new SonyCommand(new byte[]{(byte) 0xD8, (byte) 0xD1, 0x00, 0x00});
    public static final SonyCommand TOUCH_PANEL_OFF = new SonyCommand(new byte[]{(byte) 0xD8, (byte) 0xD1, 0x00, 0x01});
    public static final SonyCommand AUTO_POWER_ON = new SonyCommand(new byte[]{0x28, 0x05, 0x10, 0x00});
    public static final SonyCommand AUTO_POWER_OFF = new SonyCommand(new byte[]{0x28, 0x05, 0x11, 0x00});

    public final byte[] payload;
    public final NoiseControlState noiseControlState;

    private SonyCommand(byte[] payload) {
        this(payload, null);
    }

    private SonyCommand(byte[] payload, NoiseControlState noiseControlState) {
        this.payload = payload;
        this.noiseControlState = noiseControlState;
    }

    public static SonyCommand noiseControl(NoiseControlState state) {
        NoiseControlState safe = state == null ? NoiseControlState.defaultState() : state;
        if (safe.mode == NoiseControlState.Mode.AMBIENT) {
            return ambient(safe.ambientLevel, safe.voiceAmbient);
        }
        if (safe.mode == NoiseControlState.Mode.OFF) {
            return new SonyCommand(
                    new byte[]{0x68, 0x17, 0x01, 0x00, 0x00, 0x00, 0x00},
                    NoiseControlState.off(safe.ambientLevel, safe.voiceAmbient));
        }
        return new SonyCommand(
                new byte[]{0x68, 0x17, 0x01, 0x01, 0x00, 0x00, 0x00},
                NoiseControlState.anc(safe.ambientLevel, safe.voiceAmbient));
    }

    public static SonyCommand ambient(int level, boolean voice) {
        int clamped = NoiseControlState.clampLevel(level);
        return new SonyCommand(
                new byte[]{0x68, 0x17, 0x01, 0x01, 0x01, (byte) (voice ? 0x01 : 0x00), (byte) clamped},
                NoiseControlState.ambient(clamped, voice));
    }

    public static SonyCommand equalizerPreset(int preset) {
        return new SonyCommand(new byte[]{0x58, 0x00, (byte) preset, 0x00});
    }

    public static SonyCommand voiceGuidance(boolean enabled, int languageCode, int inquiredType) {
        int type = inquiredType == 0 || inquiredType == 1 || inquiredType == 2 || inquiredType == 3 ? inquiredType : 0;
        if (type != 2) {
            return new SonyCommand(new byte[]{
                    0x48,
                    (byte) type,
                    (byte) (enabled ? 0x00 : 0x01)
            });
        }
        return new SonyCommand(new byte[]{
                0x48,
                (byte) type,
                (byte) (enabled ? 0x00 : 0x01),
                (byte) languageCode
        });
    }

    public static SonyCommand quickAccessSlots(int doublePressFunction, int triplePressFunction) {
        return new SonyCommand(new byte[]{
                (byte) 0xF8,
                0x0D,
                0x02,
                (byte) clampQuickAccessFunction(doublePressFunction),
                (byte) clampQuickAccessFunction(triplePressFunction)
        });
    }

    public static SonyCommand equalizer(int preset, int[] values) {
        int[] safe = safeEqValues(values);
        return new SonyCommand(new byte[]{
                0x58,
                0x00,
                (byte) preset,
                0x06,
                (byte) safe[0],
                (byte) safe[1],
                (byte) safe[2],
                (byte) safe[3],
                (byte) safe[4],
                (byte) safe[5]
        });
    }

    public static int[] flatEqualizerValues() {
        return new int[]{10, 10, 10, 10, 10, 10};
    }

    private static int[] safeEqValues(int[] values) {
        int[] safe = flatEqualizerValues();
        if (values == null) {
            return safe;
        }
        for (int i = 0; i < safe.length && i < values.length; i++) {
            safe[i] = HeadsetStatus.clampEq(values[i]);
        }
        return safe;
    }

    private static int clampQuickAccessFunction(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
