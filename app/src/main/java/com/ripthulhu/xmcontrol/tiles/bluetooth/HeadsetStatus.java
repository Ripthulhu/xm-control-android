package com.ripthulhu.xmcontrol.tiles.bluetooth;

import java.util.List;

public final class HeadsetStatus {
    public String batteryText;
    public String caseBatteryText;
    public NoiseControlState noiseControl;
    public Boolean dseeAuto;
    public Boolean connectionQuality;
    public Boolean multipoint;
    public Boolean touchPanel;
    public Boolean speakToChat;
    public Boolean wearPause;
    public Boolean autoPower;
    public Boolean voiceGuidance;
    public int voiceGuidanceType = -1;
    public int voiceGuidanceLanguage = -1;
    public int eqPreset = -1;
    public int[] eqValues;
    public int[] quickAccessFunctions;

    public static HeadsetStatus voiceGuidanceSnapshot(boolean enabled, int type, int language) {
        HeadsetStatus status = new HeadsetStatus();
        status.voiceGuidance = enabled;
        status.voiceGuidanceType = isVoiceGuidanceType(type) ? type : 0;
        if (isVoiceGuidanceLanguage(language)) {
            status.voiceGuidanceLanguage = language;
        }
        return status;
    }

    public static HeadsetStatus fromPayloads(List<byte[]> payloads, NoiseControlState fallbackNoise) {
        HeadsetStatus status = new HeadsetStatus();
        if (payloads == null) {
            return status;
        }

        for (byte[] payload : payloads) {
            status.accept(payload, fallbackNoise);
        }
        if (status.batteryText == null && status.caseBatteryText != null) {
            status.batteryText = status.caseBatteryText;
            status.caseBatteryText = null;
        }
        return status;
    }

    private void accept(byte[] payload, NoiseControlState fallbackNoise) {
        if (payload == null || payload.length == 0) {
            return;
        }

        int command = u(payload[0]);
        switch (command) {
            case 0x11:
            case 0x13:
            case 0x23:
            case 0x25:
                parseBattery(payload);
                return;
            case 0x67:
            case 0x69:
                noiseControl = NoiseControlState.fromPayload(payload, fallbackNoise);
                return;
            case 0xD7:
                parseD7(payload);
                return;
            case 0x57:
            case 0x59:
                parseEqualizer(payload);
                return;
            case 0xE7:
                parseE7(payload);
                return;
            case 0xF7:
                parseF7(payload);
                return;
            case 0x47:
            case 0x49:
                parseVoiceGuidance(payload);
                return;
            case 0x43:
            case 0x45:
                parseVoiceGuidanceStatus(payload);
                return;
            case 0x27:
                parsePower(payload);
                return;
            default:
                return;
        }
    }

    private void parseBattery(byte[] payload) {
        if (payload.length < 4) {
            return;
        }

        int type = u(payload[1]);
        if (type == 0x00) {
            batteryText = u(payload[2]) + "%";
        } else if (type == 0x01 && payload.length >= 6) {
            batteryText = "L " + u(payload[2]) + "%  R " + u(payload[4]) + "%";
        } else if (type == 0x02) {
            caseBatteryText = "Case " + u(payload[2]) + "%";
        }
    }

    private void parseD7(byte[] payload) {
        if (payload.length < 4 || u(payload[2]) != 0x00) {
            return;
        }

        boolean enabled = u(payload[3]) == 0x00;
        int setting = u(payload[1]);
        if (setting == 0xD1) {
            touchPanel = enabled;
        } else if (setting == 0xD2) {
            multipoint = enabled;
        }
    }

    private void parseEqualizer(byte[] payload) {
        if (payload.length < 4 || u(payload[1]) != 0x00) {
            return;
        }

        eqPreset = u(payload[2]);
        if (u(payload[3]) == 0x06 && payload.length >= 10) {
            eqValues = new int[6];
            for (int i = 0; i < eqValues.length; i++) {
                eqValues[i] = clampEq(u(payload[i + 4]));
            }
        }
    }

    private void parseE7(byte[] payload) {
        if (payload.length < 3) {
            return;
        }

        int setting = u(payload[1]);
        int value = u(payload[2]);
        if (setting == 0x00) {
            connectionQuality = value == 0x00;
        } else if (setting == 0x01) {
            dseeAuto = value == 0x01;
        }
    }

    private void parseF7(byte[] payload) {
        if (payload.length < 3) {
            return;
        }

        int setting = u(payload[1]);
        if (setting == 0x0D) {
            parseQuickAccess(payload);
            return;
        }

        boolean enabled = u(payload[2]) == 0x00;
        if (setting == 0x01) {
            wearPause = enabled;
        } else if (setting == 0x02) {
            speakToChat = enabled;
        }
    }

    private void parseQuickAccess(byte[] payload) {
        if (payload.length < 4) {
            return;
        }

        int count = Math.min(u(payload[2]), payload.length - 3);
        if (count <= 0) {
            return;
        }
        quickAccessFunctions = new int[count];
        for (int i = 0; i < count; i++) {
            quickAccessFunctions[i] = u(payload[i + 3]);
        }
    }

    private void parsePower(byte[] payload) {
        if (payload.length < 4 || u(payload[1]) != 0x05) {
            return;
        }

        int mode = u(payload[2]);
        if (mode == 0x10) {
            autoPower = true;
        } else if (mode == 0x11) {
            autoPower = false;
        }
    }

    private void parseVoiceGuidance(byte[] payload) {
        if (payload.length < 3) {
            return;
        }

        int type = u(payload[1]);
        if (!isVoiceGuidanceType(type)) {
            return;
        }
        if (voiceGuidanceType >= 0 && voiceGuidanceType != type) {
            return;
        }
        voiceGuidanceType = type;

        int onOff = u(payload[2]);
        if (onOff == 0x00 || onOff == 0x01) {
            voiceGuidance = onOff == 0x00;
        }
        if (payload.length < 4) {
            return;
        }
        int language = u(payload[3]);
        if (isVoiceGuidanceLanguage(language)) {
            voiceGuidanceLanguage = language;
        }
    }

    private void parseVoiceGuidanceStatus(byte[] payload) {
        if (payload.length < 4) {
            return;
        }

        int type = u(payload[1]);
        if (!isVoiceGuidanceType(type)) {
            return;
        }
        if (voiceGuidanceType >= 0 && voiceGuidanceType != type) {
            return;
        }
        voiceGuidanceType = type;
    }

    public static int clampEq(int value) {
        return Math.max(0, Math.min(20, value));
    }

    public static boolean isVoiceGuidanceLanguage(int value) {
        switch (value) {
            case 1:
            case 2:
            case 3:
            case 4:
            case 5:
            case 6:
            case 7:
            case 8:
            case 9:
            case 10:
            case 11:
            case 13:
            case 15:
            case 16:
            case 240:
                return true;
            default:
                return false;
        }
    }

    public static boolean isVoiceGuidanceType(int value) {
        return value == 0 || value == 1 || value == 2 || value == 3;
    }

    private static int u(byte value) {
        return value & 0xFF;
    }
}
