package com.ripthulhu.xmcontrol.tiles.store;

import android.content.Context;
import android.content.SharedPreferences;

import com.ripthulhu.xmcontrol.tiles.bluetooth.HeadsetStatus;
import com.ripthulhu.xmcontrol.tiles.bluetooth.NoiseControlState;
import com.ripthulhu.xmcontrol.tiles.bluetooth.SonyCommand;

public final class TilePreferences {
    public static final int EQ_MANUAL = 0xA0;
    public static final int EQ_USER_1 = 0xA1;
    public static final int EQ_USER_2 = 0xA2;
    public static final int EQ_CLEAR_BASS_INDEX = 0;
    public static final int EQ_VALUE_COUNT = 6;

    private static final String NAME = "xm_control_tiles";
    private static final String KEY_DEVICE_ADDRESS = "device_address";
    private static final String KEY_ACTIVE_DEVICE_NAME = "active_device_name";
    private static final String KEY_NOISE_MODE = "noise_mode";
    private static final String KEY_AMBIENT_LEVEL = "ambient_level";
    private static final String KEY_AMBIENT_VOICE = "ambient_voice";
    private static final String KEY_SPEAK_TO_CHAT = "speak_to_chat";
    private static final String KEY_WEAR_PAUSE = "wear_pause";
    private static final String KEY_NOISE_LAST_REFRESH = "noise_last_refresh";
    private static final String KEY_STATUS_LAST_REFRESH = "status_last_refresh";
    private static final String KEY_HEADSET_CONNECTED = "headset_connected";
    private static final String KEY_BATTERY_TEXT = "battery_text";
    private static final String KEY_DSEE_AUTO = "dsee_auto";
    private static final String KEY_CONNECTION_QUALITY = "connection_quality";
    private static final String KEY_MULTIPOINT = "multipoint";
    private static final String KEY_TOUCH_PANEL = "touch_panel";
    private static final String KEY_AUTO_POWER = "auto_power";
    private static final String KEY_VOICE_GUIDANCE = "voice_guidance";
    private static final String KEY_VOICE_GUIDANCE_TYPE = "voice_guidance_type";
    private static final String KEY_VOICE_GUIDANCE_LANGUAGE = "voice_guidance_language";
    private static final String KEY_EQ_PRESET = "eq_preset";
    private static final String KEY_EQ_VALUE_PREFIX = "eq_value_";
    private static final String KEY_EQ_MANUAL_VALUE_PREFIX = "eq_manual_value_";
    private static final String KEY_QUICK_ACCESS_DOUBLE = "quick_access_double";
    private static final String KEY_QUICK_ACCESS_TRIPLE = "quick_access_triple";
    private static final long NOISE_REFRESH_THROTTLE_MS = 30_000L;
    private static final long STATUS_REFRESH_THROTTLE_MS = 45_000L;

    private TilePreferences() {
    }

    public static String selectedDeviceAddress(Context context) {
        return prefs(context).getString(KEY_DEVICE_ADDRESS, null);
    }

    public static void setSelectedDeviceAddress(Context context, String address) {
        SharedPreferences preferences = prefs(context);
        String previous = preferences.getString(KEY_DEVICE_ADDRESS, null);
        SharedPreferences.Editor editor = preferences.edit().putString(KEY_DEVICE_ADDRESS, address);
        if (previous == null || !previous.equals(address)) {
            editor.putBoolean(KEY_HEADSET_CONNECTED, false)
                    .remove(KEY_BATTERY_TEXT)
                    .putLong(KEY_STATUS_LAST_REFRESH, 0L)
                    .putLong(KEY_NOISE_LAST_REFRESH, 0L);
        }
        editor.apply();
    }

    public static String activeDeviceName(Context context) {
        return prefs(context).getString(KEY_ACTIVE_DEVICE_NAME, null);
    }

    public static void setActiveDeviceName(Context context, String name) {
        SharedPreferences.Editor editor = prefs(context).edit();
        if (name == null) {
            editor.remove(KEY_ACTIVE_DEVICE_NAME);
        } else {
            editor.putString(KEY_ACTIVE_DEVICE_NAME, name);
        }
        editor.apply();
    }

    public static NoiseControlState noiseControlState(Context context) {
        SharedPreferences prefs = prefs(context);
        String modeName = prefs.getString(KEY_NOISE_MODE, NoiseControlState.Mode.UNKNOWN.name());
        NoiseControlState.Mode mode;
        try {
            mode = NoiseControlState.Mode.valueOf(modeName);
        } catch (RuntimeException ex) {
            mode = NoiseControlState.Mode.UNKNOWN;
        }
        int level = prefs.getInt(KEY_AMBIENT_LEVEL, 20);
        boolean voice = prefs.getBoolean(KEY_AMBIENT_VOICE, false);
        return new NoiseControlState(mode, level, voice);
    }

    public static void setNoiseControlState(Context context, NoiseControlState state) {
        NoiseControlState safe = state == null ? NoiseControlState.defaultState() : state;
        prefs(context).edit()
                .putString(KEY_NOISE_MODE, safe.mode.name())
                .putInt(KEY_AMBIENT_LEVEL, safe.ambientLevel)
                .putBoolean(KEY_AMBIENT_VOICE, safe.voiceAmbient)
                .apply();
    }

    public static boolean shouldRefreshNoiseState(Context context) {
        long last = prefs(context).getLong(KEY_NOISE_LAST_REFRESH, 0L);
        return System.currentTimeMillis() - last >= NOISE_REFRESH_THROTTLE_MS;
    }

    public static void markNoiseStateRefreshed(Context context) {
        prefs(context).edit().putLong(KEY_NOISE_LAST_REFRESH, System.currentTimeMillis()).apply();
    }

    public static void invalidateNoiseState(Context context) {
        prefs(context).edit().putLong(KEY_NOISE_LAST_REFRESH, 0L).apply();
    }

    public static boolean shouldRefreshStatus(Context context) {
        long last = prefs(context).getLong(KEY_STATUS_LAST_REFRESH, 0L);
        return System.currentTimeMillis() - last >= STATUS_REFRESH_THROTTLE_MS;
    }

    public static void markStatusRefreshed(Context context) {
        long now = System.currentTimeMillis();
        prefs(context).edit()
                .putLong(KEY_STATUS_LAST_REFRESH, now)
                .putLong(KEY_NOISE_LAST_REFRESH, now)
                .apply();
    }

    public static void invalidateStatus(Context context) {
        prefs(context).edit()
                .putLong(KEY_STATUS_LAST_REFRESH, 0L)
                .putLong(KEY_NOISE_LAST_REFRESH, 0L)
                .apply();
    }

    public static boolean headsetConnected(Context context) {
        return prefs(context).getBoolean(KEY_HEADSET_CONNECTED, false);
    }

    public static void markHeadsetConnected(Context context) {
        prefs(context).edit().putBoolean(KEY_HEADSET_CONNECTED, true).apply();
    }

    public static void markHeadsetDisconnected(Context context) {
        prefs(context).edit()
                .putBoolean(KEY_HEADSET_CONNECTED, false)
                .remove(KEY_BATTERY_TEXT)
                .apply();
    }

    public static boolean speakToChatEnabled(Context context) {
        return prefs(context).getBoolean(KEY_SPEAK_TO_CHAT, false);
    }

    public static void setSpeakToChatEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_SPEAK_TO_CHAT, enabled).apply();
    }

    public static boolean wearPauseEnabled(Context context) {
        return prefs(context).getBoolean(KEY_WEAR_PAUSE, false);
    }

    public static void setWearPauseEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_WEAR_PAUSE, enabled).apply();
    }

    public static String batteryText(Context context) {
        return prefs(context).getString(KEY_BATTERY_TEXT, "Waiting");
    }

    public static boolean hasBatteryText(Context context) {
        String value = prefs(context).getString(KEY_BATTERY_TEXT, null);
        return value != null && !value.trim().isEmpty() && !"Waiting".equalsIgnoreCase(value.trim());
    }

    public static boolean dseeAuto(Context context) {
        return prefs(context).getBoolean(KEY_DSEE_AUTO, true);
    }

    public static void setDseeAuto(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_DSEE_AUTO, enabled).apply();
    }

    public static boolean connectionQuality(Context context) {
        return prefs(context).getBoolean(KEY_CONNECTION_QUALITY, true);
    }

    public static void setConnectionQuality(Context context, boolean quality) {
        prefs(context).edit().putBoolean(KEY_CONNECTION_QUALITY, quality).apply();
    }

    public static boolean multipointEnabled(Context context) {
        return prefs(context).getBoolean(KEY_MULTIPOINT, false);
    }

    public static void setMultipointEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_MULTIPOINT, enabled).apply();
    }

    public static boolean touchPanelEnabled(Context context) {
        return prefs(context).getBoolean(KEY_TOUCH_PANEL, true);
    }

    public static void setTouchPanelEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_TOUCH_PANEL, enabled).apply();
    }

    public static boolean autoPowerEnabled(Context context) {
        return prefs(context).getBoolean(KEY_AUTO_POWER, true);
    }

    public static void setAutoPowerEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_AUTO_POWER, enabled).apply();
    }

    public static boolean voiceGuidanceEnabled(Context context) {
        return prefs(context).getBoolean(KEY_VOICE_GUIDANCE, true);
    }

    public static int voiceGuidanceLanguage(Context context) {
        int value = prefs(context).getInt(KEY_VOICE_GUIDANCE_LANGUAGE, 1);
        return HeadsetStatus.isVoiceGuidanceLanguage(value) ? value : 1;
    }

    public static int voiceGuidanceType(Context context) {
        int value = prefs(context).getInt(KEY_VOICE_GUIDANCE_TYPE, -1);
        return HeadsetStatus.isVoiceGuidanceType(value) ? value : -1;
    }

    public static void setVoiceGuidance(Context context, boolean enabled, int languageCode) {
        prefs(context).edit()
                .putBoolean(KEY_VOICE_GUIDANCE, enabled)
                .putInt(KEY_VOICE_GUIDANCE_LANGUAGE,
                        HeadsetStatus.isVoiceGuidanceLanguage(languageCode) ? languageCode : 1)
                .apply();
    }

    public static int eqPreset(Context context) {
        return prefs(context).getInt(KEY_EQ_PRESET, EQ_MANUAL);
    }

    public static void setEqPreset(Context context, int preset) {
        prefs(context).edit().putInt(KEY_EQ_PRESET, preset).apply();
    }

    public static int[] eqValues(Context context) {
        return readEqValues(prefs(context), KEY_EQ_VALUE_PREFIX);
    }

    public static int[] manualEqValues(Context context) {
        SharedPreferences preferences = prefs(context);
        if (!preferences.contains(KEY_EQ_MANUAL_VALUE_PREFIX + 0)) {
            return readEqValues(preferences, KEY_EQ_VALUE_PREFIX);
        }
        return readEqValues(preferences, KEY_EQ_MANUAL_VALUE_PREFIX);
    }

    public static void setEqValues(Context context, int[] values) {
        SharedPreferences preferences = prefs(context);
        SharedPreferences.Editor editor = preferences.edit();
        writeEqValues(editor, KEY_EQ_VALUE_PREFIX, values);
        if (preferences.getInt(KEY_EQ_PRESET, EQ_MANUAL) == EQ_MANUAL) {
            writeEqValues(editor, KEY_EQ_MANUAL_VALUE_PREFIX, values);
        }
        editor.apply();
    }

    public static void setEqState(Context context, int preset, int[] values) {
        SharedPreferences.Editor editor = prefs(context).edit();
        editor.putInt(KEY_EQ_PRESET, preset);
        if (values != null && values.length >= EQ_VALUE_COUNT) {
            writeEqValues(editor, KEY_EQ_VALUE_PREFIX, values);
            if (preset == EQ_MANUAL) {
                writeEqValues(editor, KEY_EQ_MANUAL_VALUE_PREFIX, values);
            }
        }
        editor.apply();
    }

    public static int[] editEqValueAsManual(Context context, int index, int value) {
        int[] values = eqValues(context);
        if (index >= 0 && index < values.length) {
            values[index] = HeadsetStatus.clampEq(value);
        }
        setEqState(context, EQ_MANUAL, values);
        return values;
    }

    public static int quickAccessDoubleFunction(Context context) {
        return clampQuickAccessFunction(prefs(context).getInt(KEY_QUICK_ACCESS_DOUBLE, 0));
    }

    public static int quickAccessTripleFunction(Context context) {
        return clampQuickAccessFunction(prefs(context).getInt(KEY_QUICK_ACCESS_TRIPLE, 0));
    }

    public static void setQuickAccessFunctions(Context context, int doublePressFunction, int triplePressFunction) {
        prefs(context).edit()
                .putInt(KEY_QUICK_ACCESS_DOUBLE, clampQuickAccessFunction(doublePressFunction))
                .putInt(KEY_QUICK_ACCESS_TRIPLE, clampQuickAccessFunction(triplePressFunction))
                .apply();
    }

    public static void applyStatus(Context context, HeadsetStatus status) {
        applyStatus(context, status, false);
    }

    public static void applyStatus(Context context, HeadsetStatus status, boolean markRefreshed) {
        if (status == null) {
            return;
        }

        SharedPreferences.Editor editor = prefs(context).edit();
        editor.putBoolean(KEY_HEADSET_CONNECTED, true);
        if (markRefreshed) {
            long now = System.currentTimeMillis();
            editor.putLong(KEY_STATUS_LAST_REFRESH, now);
            editor.putLong(KEY_NOISE_LAST_REFRESH, now);
        }
        if (status.batteryText != null) {
            String battery = status.batteryText;
            if (status.caseBatteryText != null) {
                battery += "  " + status.caseBatteryText;
            }
            editor.putString(KEY_BATTERY_TEXT, battery);
        }
        if (status.noiseControl != null) {
            editor.putString(KEY_NOISE_MODE, status.noiseControl.mode.name());
            editor.putInt(KEY_AMBIENT_LEVEL, status.noiseControl.ambientLevel);
            editor.putBoolean(KEY_AMBIENT_VOICE, status.noiseControl.voiceAmbient);
        }
        if (status.dseeAuto != null) editor.putBoolean(KEY_DSEE_AUTO, status.dseeAuto);
        if (status.connectionQuality != null) editor.putBoolean(KEY_CONNECTION_QUALITY, status.connectionQuality);
        if (status.multipoint != null) editor.putBoolean(KEY_MULTIPOINT, status.multipoint);
        if (status.touchPanel != null) editor.putBoolean(KEY_TOUCH_PANEL, status.touchPanel);
        if (status.speakToChat != null) editor.putBoolean(KEY_SPEAK_TO_CHAT, status.speakToChat);
        if (status.wearPause != null) editor.putBoolean(KEY_WEAR_PAUSE, status.wearPause);
        if (status.autoPower != null) editor.putBoolean(KEY_AUTO_POWER, status.autoPower);
        if (status.voiceGuidance != null) editor.putBoolean(KEY_VOICE_GUIDANCE, status.voiceGuidance);
        if (status.voiceGuidanceType >= 0) {
            editor.putInt(KEY_VOICE_GUIDANCE_TYPE, status.voiceGuidanceType);
        }
        if (status.voiceGuidanceLanguage >= 0) {
            editor.putInt(KEY_VOICE_GUIDANCE_LANGUAGE, status.voiceGuidanceLanguage);
        }
        if (status.eqPreset >= 0) editor.putInt(KEY_EQ_PRESET, status.eqPreset);
        if (status.eqValues != null && status.eqValues.length >= EQ_VALUE_COUNT) {
            writeEqValues(editor, KEY_EQ_VALUE_PREFIX, status.eqValues);
            if (status.eqPreset == EQ_MANUAL) {
                writeEqValues(editor, KEY_EQ_MANUAL_VALUE_PREFIX, status.eqValues);
            }
        }
        if (status.quickAccessFunctions != null && status.quickAccessFunctions.length > 0) {
            editor.putInt(KEY_QUICK_ACCESS_DOUBLE, clampQuickAccessFunction(status.quickAccessFunctions[0]));
            if (status.quickAccessFunctions.length > 1) {
                editor.putInt(KEY_QUICK_ACCESS_TRIPLE, clampQuickAccessFunction(status.quickAccessFunctions[1]));
            }
        }
        editor.apply();
    }

    private static int[] readEqValues(SharedPreferences prefs, String prefix) {
        int[] fallback = SonyCommand.flatEqualizerValues();
        int[] values = new int[EQ_VALUE_COUNT];
        for (int i = 0; i < values.length; i++) {
            values[i] = HeadsetStatus.clampEq(prefs.getInt(prefix + i, fallback[i]));
        }
        return values;
    }

    private static void writeEqValues(SharedPreferences.Editor editor, String prefix, int[] values) {
        int[] safe = values == null ? SonyCommand.flatEqualizerValues() : values;
        for (int i = 0; i < EQ_VALUE_COUNT; i++) {
            int value = i < safe.length ? safe[i] : 10;
            editor.putInt(prefix + i, HeadsetStatus.clampEq(value));
        }
    }

    private static int clampQuickAccessFunction(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }
}
