package com.ripthulhu.xmcontrol.tiles.bluetooth;

public final class NoiseControlState {
    public enum Mode {
        ANC,
        AMBIENT,
        OFF,
        UNKNOWN
    }

    public final Mode mode;
    public final int ambientLevel;
    public final boolean voiceAmbient;

    public NoiseControlState(Mode mode, int ambientLevel, boolean voiceAmbient) {
        this.mode = mode == null ? Mode.UNKNOWN : mode;
        this.ambientLevel = clampLevel(ambientLevel);
        this.voiceAmbient = voiceAmbient;
    }

    public static NoiseControlState defaultState() {
        return new NoiseControlState(Mode.UNKNOWN, 20, false);
    }

    public static NoiseControlState anc(int ambientLevel, boolean voiceAmbient) {
        return new NoiseControlState(Mode.ANC, ambientLevel, voiceAmbient);
    }

    public static NoiseControlState ambient(int ambientLevel, boolean voiceAmbient) {
        return new NoiseControlState(Mode.AMBIENT, ambientLevel, voiceAmbient);
    }

    public static NoiseControlState off(int ambientLevel, boolean voiceAmbient) {
        return new NoiseControlState(Mode.OFF, ambientLevel, voiceAmbient);
    }

    public NoiseControlState nextMode() {
        if (mode == Mode.ANC) {
            return ambient(ambientLevel, voiceAmbient);
        }
        if (mode == Mode.AMBIENT) {
            return off(ambientLevel, voiceAmbient);
        }
        return anc(ambientLevel, voiceAmbient);
    }

    public String summary() {
        if (mode == Mode.ANC) {
            return "Noise cancelling";
        }
        if (mode == Mode.AMBIENT) {
            return (voiceAmbient ? "Voice" : "Normal") + " transparency " + ambientLevel;
        }
        if (mode == Mode.OFF) {
            return "Off";
        }
        return "Unknown";
    }

    public static NoiseControlState fromPayload(byte[] payload, NoiseControlState fallback) {
        NoiseControlState base = fallback == null ? defaultState() : fallback;
        if (payload == null || payload.length < 7) {
            return base;
        }

        int command = payload[0] & 0xFF;
        int type = payload[1] & 0xFF;
        if (command != 0x67 && command != 0x69) {
            return base;
        }

        boolean masterOn = (payload[3] & 0xFF) == 1;
        boolean voice = (payload[5] & 0xFF) == 1;
        int level = payload[6] & 0xFF;
        if (!masterOn) {
            return off(level, voice);
        }

        if (type == 0x17) {
            int mode = payload[4] & 0xFF;
            if (mode == 0) {
                return anc(level, voice);
            }
            if (mode == 1) {
                return ambient(level, voice);
            }
        }

        if (type == 0x13) {
            boolean ancOn = (payload[4] & 0xFF) == 1;
            return ancOn ? anc(level, voice) : ambient(level, voice);
        }

        return base;
    }

    public static int clampLevel(int value) {
        return Math.max(0, Math.min(20, value));
    }
}
