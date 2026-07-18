package com.ripthulhu.xmcontrol.tiles;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.app.StatusBarManager;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothLeAudio;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.ripthulhu.xmcontrol.tiles.bluetooth.HeadsetStatus;
import com.ripthulhu.xmcontrol.tiles.bluetooth.NoiseControlState;
import com.ripthulhu.xmcontrol.tiles.bluetooth.SonyCommand;
import com.ripthulhu.xmcontrol.tiles.bluetooth.SonyDeviceRepository;
import com.ripthulhu.xmcontrol.tiles.bluetooth.SonyMdrClient;
import com.ripthulhu.xmcontrol.tiles.store.TilePreferences;
import com.ripthulhu.xmcontrol.tiles.tile.AncTileService;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class MainActivity extends Activity {
    private static final int REQUEST_BLUETOOTH = 7001;
    private static final int EQ_MANUAL = 0xA0;
    private static final int EQ_USER_1 = 0xA1;
    private static final int EQ_USER_2 = 0xA2;
    private static final int[] EQ_PRESETS = {
            0x00, 0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, EQ_MANUAL, EQ_USER_1, EQ_USER_2
    };
    private static final int[] QUICK_ACCESS_FUNCTIONS = {0, 1, 2, 3, 4, 5, 6, 7};
    private static final int[] VOICE_GUIDANCE_LANGUAGES = {
            1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 16, 240
    };
    private static final String[] EQ_BAND_LABELS = {"Clear Bass", "400", "1k", "2.5k", "6.3k", "16k"};
    private static final int CONTENT_SIDE_PADDING_DP = 20;
    private static final int CARD_PADDING_DP = 20;
    private static final int CARD_RADIUS_DP = 26;
    private static final int CONTROL_HEIGHT_DP = 50;
    private static final int CONTROL_RADIUS_DP = 20;
    private static final int CONTROL_HORIZONTAL_GAP_DP = 5;
    private static final int CONTROL_VERTICAL_GAP_DP = 6;
    private static final int MAX_CONTENT_WIDTH_DP = 980;
    private static final int TWO_PANE_MIN_WIDTH_DP = 840;
    private static final int COLUMN_GAP_DP = 16;

    private int background;
    private int surface;
    private int surfaceVariant;
    private int primary;
    private int onPrimary;
    private int text;
    private int muted;
    private int sliderTrack;

    private final ExecutorService commands = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable pendingEqSend = this::sendCurrentEqualizer;
    private final Runnable connectionRetry = new Runnable() {
        @Override
        public void run() {
            if (destroyed || !bluetoothReceiverRegistered) {
                return;
            }
            if (!statusReadRunning && SonyDeviceRepository.hasConfiguredDevice(MainActivity.this)) {
                boolean cachedConnected = TilePreferences.headsetConnected(MainActivity.this);
                boolean audioConnected = SonyDeviceRepository.isSelectedDeviceConnected(MainActivity.this);
                if (!audioConnected) {
                    if (cachedConnected) {
                        statusFailureCount = 2;
                        TilePreferences.markHeadsetDisconnected(MainActivity.this);
                        renderState("Not connected");
                    } else if (statusFailureCount < 2) {
                        requestStatusRefresh(true);
                    }
                } else {
                    requestStatusRefresh(!cachedConnected);
                }
            }
            scheduleConnectionRetry();
        }
    };
    private LinearLayout root;
    private LinearLayout deviceList;
    private ScrollView scrollView;
    private TextView deviceSummaryText;
    private TextView batteryText;
    private TextView noiseStatusText;
    private TextView ambientLevelText;
    private TextView eqSummaryText;
    private TextView dseeStateText;
    private TextView qualityStateText;
    private TextView multipointStateText;
    private TextView speakStateText;
    private TextView wearStateText;
    private TextView touchStateText;
    private TextView autoPowerStateText;
    private TextView voiceGuidanceStateText;
    private TextView voiceGuidanceLanguageStateText;
    private TextView quickAccessDoubleStateText;
    private TextView quickAccessTripleStateText;
    private Button normalAmbientButton;
    private Button voiceAmbientButton;
    private Button noiseAncButton;
    private Button noiseAmbientButton;
    private Button noiseOffButton;
    private Button dseeAutoButton;
    private Button dseeOffButton;
    private Button qualityButton;
    private Button stabilityButton;
    private Button multipointOnButton;
    private Button multipointOffButton;
    private Button speakOnButton;
    private Button speakOffButton;
    private Button wearOnButton;
    private Button wearOffButton;
    private Button touchOnButton;
    private Button touchOffButton;
    private Button autoPowerOnButton;
    private Button autoPowerOffButton;
    private Button voiceGuidanceOnButton;
    private Button voiceGuidanceOffButton;
    private Button voiceGuidanceLanguageButton;
    private Button quickAccessDoubleButton;
    private Button quickAccessTripleButton;
    private Button eqPresetButton;
    private Button eqSaveButton;
    private Button eqFlatButton;
    private SeekBar ambientSeekBar;
    private final SeekBar[] eqSeekBars = new SeekBar[6];
    private final TextView[] eqValueTexts = new TextView[6];
    private final boolean[] eqSliderDragging = new boolean[6];

    private boolean updatingUi;
    private boolean ambientSliderDragging;
    private boolean destroyed;
    private boolean statusReadRunning;
    private boolean statusRefreshQueued;
    private boolean bluetoothReceiverRegistered;
    private boolean skipNextResumeRefresh;
    private long lastFailureToastAt;
    private int localChangeVersion;
    private int restoredScrollY;
    private int statusFailureCount;
    private int statusReadGeneration;
    private Future<?> statusReadFuture;
    private final Runnable delayedConnectionCheck = () -> {
        if (destroyed || !bluetoothReceiverRegistered || statusReadRunning) {
            return;
        }
        requestStatusRefresh(true);
    };
    private final BroadcastReceiver bluetoothReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            handleBluetoothEvent(intent);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            restoredScrollY = savedInstanceState.getInt("scroll_y", 0);
        }
        loadPalette();
        configureWindow();
        buildUi();
        refreshDevices();
        skipNextResumeRefresh = true;
    }

    @Override
    protected void onStart() {
        super.onStart();
        registerBluetoothReceiver();
        scheduleConnectionRetry();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (skipNextResumeRefresh) {
            skipNextResumeRefresh = false;
            return;
        }
        if (root != null) {
            refreshDevices();
        }
    }

    @Override
    protected void onStop() {
        main.removeCallbacks(connectionRetry);
        main.removeCallbacks(delayedConnectionCheck);
        unregisterBluetoothReceiver();
        super.onStop();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (scrollView != null) {
            outState.putInt("scroll_y", scrollView.getScrollY());
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        cancelStatusRefreshForCommand();
        main.removeCallbacksAndMessages(null);
        commands.shutdownNow();
        super.onDestroy();
    }

    private void loadPalette() {
        ThemePalette palette = ThemePalette.from(this);
        background = palette.background;
        surface = palette.surface;
        surfaceVariant = palette.surfaceVariant;
        primary = palette.primary;
        onPrimary = palette.onPrimary;
        text = palette.text;
        muted = palette.muted;
        sliderTrack = palette.sliderTrack;
    }

    private void configureWindow() {
        Window window = getWindow();
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.setStatusBarContrastEnforced(false);
            window.setNavigationBarContrastEnforced(false);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
        }
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scrollView = scroll;
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        scroll.setBackgroundColor(background);

        FrameLayout viewport = new FrameLayout(this);
        scroll.addView(viewport, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        root = new MaxWidthLinearLayout(this, MAX_CONTENT_WIDTH_DP);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(CONTENT_SIDE_PADDING_DP), dp(24), dp(CONTENT_SIDE_PADDING_DP), dp(24));
        FrameLayout.LayoutParams rootParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL);
        viewport.addView(root, rootParams);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            scroll.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                root.setPadding(
                        dp(CONTENT_SIDE_PADDING_DP) + bars.left,
                        dp(24) + bars.top,
                        dp(CONTENT_SIDE_PADDING_DP) + bars.right,
                        dp(24) + bars.bottom);
                return insets;
            });
        }

        TextView title = label("XM Control", 31, Typeface.BOLD, text);
        title.setPadding(dp(2), 0, 0, 0);
        root.addView(title);

        deviceSummaryText = label("Looking for a paired Sony XM device...", 15, Typeface.NORMAL, muted);
        deviceSummaryText.setPadding(0, dp(4), 0, dp(20));
        root.addView(deviceSummaryText);

        if (!SonyDeviceRepository.hasConnectPermission(this)) {
            root.addView(permissionCard());
        }
        root.addView(contentColumns());

        setContentView(scroll);
        if (restoredScrollY > 0) {
            scroll.post(() -> scroll.scrollTo(0, restoredScrollY));
        }
        renderState(null);
    }

    private LinearLayout contentColumns() {
        AdaptiveColumnsLayout columns = new AdaptiveColumnsLayout(this);
        LinearLayout primary = column();
        LinearLayout secondary = column();
        columns.addView(primary);
        columns.addView(secondary);

        primary.addView(deviceCard());
        primary.addView(noiseControlCard());
        primary.addView(soundCard());
        secondary.addView(settingsCard());
        secondary.addView(quickAccessCard());
        secondary.addView(tileCard());
        return columns;
    }

    private LinearLayout column() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        return column;
    }

    private LinearLayout permissionCard() {
        LinearLayout card = card();
        card.addView(cardTitle("Bluetooth permission"));
        card.addView(subhead("Allow nearby device access so XM Control can talk to an already paired headset."));
        Button button = primaryButton("Grant permission");
        button.setOnClickListener(v -> requestBluetoothPermission());
        card.addView(button);
        return card;
    }

    private LinearLayout deviceCard() {
        LinearLayout card = card();
        card.addView(cardTitle("Headset"));
        batteryText = subhead("Battery: Waiting");
        batteryText.setPadding(0, dp(4), 0, dp(12));
        card.addView(batteryText);
        deviceList = new LinearLayout(this);
        deviceList.setOrientation(LinearLayout.VERTICAL);
        deviceList.setPadding(0, dp(2), 0, 0);
        card.addView(deviceList);
        return card;
    }

    private LinearLayout noiseControlCard() {
        LinearLayout card = card();
        card.addView(cardTitle("Noise control"));
        noiseStatusText = subhead("Waiting");
        noiseStatusText.setPadding(0, dp(4), 0, dp(15));
        card.addView(noiseStatusText);

        LinearLayout modeRow = row();
        noiseAncButton = rowButton("ANC");
        noiseAmbientButton = rowButton("Transparency");
        noiseOffButton = rowButton("Off");
        modeRow.addView(noiseAncButton);
        modeRow.addView(noiseAmbientButton);
        modeRow.addView(noiseOffButton);
        card.addView(modeRow);

        noiseAncButton.setOnClickListener(v -> sendNoiseControl(NoiseControlState.Mode.ANC));
        noiseAmbientButton.setOnClickListener(v -> sendNoiseControl(NoiseControlState.Mode.AMBIENT));
        noiseOffButton.setOnClickListener(v -> sendNoiseControl(NoiseControlState.Mode.OFF));

        card.addView(divider());

        ambientLevelText = label("", 14, Typeface.BOLD, text);
        card.addView(labelValueRow(settingLabel("Transparency level"), ambientLevelText, 14, 6));

        ambientSeekBar = seekBar(20);
        card.addView(ambientSeekBar, sliderParams(0, 13));
        ambientSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                int level = SmoothSlider.value(seekBar);
                setTextIfChanged(ambientLevelText, String.valueOf(level));
                if (!ambientSliderDragging) {
                    NoiseControlState current = TilePreferences.noiseControlState(MainActivity.this);
                    sendNoiseState(NoiseControlState.ambient(level, current.voiceAmbient));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                ambientSliderDragging = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                ambientSliderDragging = false;
                NoiseControlState current = TilePreferences.noiseControlState(MainActivity.this);
                sendNoiseState(NoiseControlState.ambient(SmoothSlider.value(seekBar), current.voiceAmbient));
            }
        });

        LinearLayout ambientRow = row();
        normalAmbientButton = rowButton("Normal");
        voiceAmbientButton = rowButton("Voice");
        ambientRow.addView(normalAmbientButton);
        ambientRow.addView(voiceAmbientButton);
        card.addView(ambientRow);

        normalAmbientButton.setOnClickListener(v -> setAmbientVoice(false));
        voiceAmbientButton.setOnClickListener(v -> setAmbientVoice(true));
        return card;
    }

    private LinearLayout soundCard() {
        LinearLayout card = card();
        card.addView(cardTitle("Sound"));

        dseeStateText = settingTitle("DSEE Extreme", TilePreferences.dseeAuto(this) ? "Auto" : "Off");
        card.addView(dseeStateText);
        LinearLayout dseeRow = row();
        dseeAutoButton = rowButton("Auto");
        dseeOffButton = rowButton("Off");
        dseeRow.addView(dseeAutoButton);
        dseeRow.addView(dseeOffButton);
        card.addView(dseeRow);
        dseeAutoButton.setOnClickListener(v -> setDsee(true));
        dseeOffButton.setOnClickListener(v -> setDsee(false));

        card.addView(divider());
        eqSummaryText = settingTitle("Equalizer",
                eqSummary(TilePreferences.eqPreset(this), TilePreferences.eqValues(this)));
        card.addView(eqSummaryText);

        eqPresetButton = secondaryButton(eqPresetName(TilePreferences.eqPreset(this)));
        eqSaveButton = rowButton("Save preset");
        eqFlatButton = rowButton("Flat");
        card.addView(eqPresetButton);
        LinearLayout eqActionRow = row();
        eqActionRow.addView(eqSaveButton);
        eqActionRow.addView(eqFlatButton);
        card.addView(eqActionRow);
        eqPresetButton.setOnClickListener(v -> showEqPresetMenu());
        eqSaveButton.setOnClickListener(v -> showSavePresetMenu(eqSaveButton));
        eqFlatButton.setOnClickListener(v -> setEqualizerFlat());

        for (int i = 0; i < EQ_BAND_LABELS.length; i++) {
            card.addView(eqSliderRow(i));
        }
        return card;
    }

    private LinearLayout settingsCard() {
        LinearLayout card = card();
        card.addView(cardTitle("Settings"));

        qualityStateText = settingTitle("Bluetooth connection quality", TilePreferences.connectionQuality(this) ? "Quality" : "Stability");
        card.addView(qualityStateText);
        LinearLayout qualityRow = row();
        qualityButton = rowButton("Quality");
        stabilityButton = rowButton("Stability");
        qualityRow.addView(qualityButton);
        qualityRow.addView(stabilityButton);
        card.addView(qualityRow);
        qualityButton.setOnClickListener(v -> setConnectionQuality(true));
        stabilityButton.setOnClickListener(v -> setConnectionQuality(false));

        multipointStateText = settingTitle("Multipoint support", TilePreferences.multipointEnabled(this) ? "On" : "Off");
        card.addView(multipointStateText);
        LinearLayout multipointRow = row();
        multipointOnButton = rowButton("On");
        multipointOffButton = rowButton("Off");
        multipointRow.addView(multipointOnButton);
        multipointRow.addView(multipointOffButton);
        card.addView(multipointRow);
        multipointOnButton.setOnClickListener(v -> setMultipoint(true));
        multipointOffButton.setOnClickListener(v -> setMultipoint(false));

        card.addView(divider());
        speakStateText = settingTitle("Speak-to-Chat", TilePreferences.speakToChatEnabled(this) ? "On" : "Off");
        card.addView(speakStateText);
        LinearLayout speakRow = row();
        speakOnButton = rowButton("On");
        speakOffButton = rowButton("Off");
        speakRow.addView(speakOnButton);
        speakRow.addView(speakOffButton);
        card.addView(speakRow);
        speakOnButton.setOnClickListener(v -> setSpeakToChat(true));
        speakOffButton.setOnClickListener(v -> setSpeakToChat(false));

        wearStateText = settingTitle("Pause when headphones are removed", TilePreferences.wearPauseEnabled(this) ? "On" : "Off");
        card.addView(wearStateText);
        LinearLayout wearRow = row();
        wearOnButton = rowButton("On");
        wearOffButton = rowButton("Off");
        wearRow.addView(wearOnButton);
        wearRow.addView(wearOffButton);
        card.addView(wearRow);
        wearOnButton.setOnClickListener(v -> setWearPause(true));
        wearOffButton.setOnClickListener(v -> setWearPause(false));

        touchStateText = settingTitle("Touch sensor control panel", TilePreferences.touchPanelEnabled(this) ? "On" : "Off");
        card.addView(touchStateText);
        LinearLayout touchRow = row();
        touchOnButton = rowButton("On");
        touchOffButton = rowButton("Off");
        touchRow.addView(touchOnButton);
        touchRow.addView(touchOffButton);
        card.addView(touchRow);
        touchOnButton.setOnClickListener(v -> setTouchPanel(true));
        touchOffButton.setOnClickListener(v -> setTouchPanel(false));

        card.addView(divider());
        autoPowerStateText = settingTitle("Automatic power off", TilePreferences.autoPowerEnabled(this) ? "On" : "Off");
        card.addView(autoPowerStateText);
        LinearLayout powerRow = row();
        autoPowerOnButton = rowButton("On");
        autoPowerOffButton = rowButton("Off");
        powerRow.addView(autoPowerOnButton);
        powerRow.addView(autoPowerOffButton);
        card.addView(powerRow);
        autoPowerOnButton.setOnClickListener(v -> setAutoPower(true));
        autoPowerOffButton.setOnClickListener(v -> setAutoPower(false));

        card.addView(divider());
        voiceGuidanceStateText = settingTitle("Notification & Voice Guide",
                TilePreferences.voiceGuidanceEnabled(this) ? "On" : "Off");
        card.addView(voiceGuidanceStateText);
        LinearLayout voiceGuideRow = row();
        voiceGuidanceOnButton = rowButton("On");
        voiceGuidanceOffButton = rowButton("Off");
        voiceGuideRow.addView(voiceGuidanceOnButton);
        voiceGuideRow.addView(voiceGuidanceOffButton);
        card.addView(voiceGuideRow);
        voiceGuidanceOnButton.setOnClickListener(v -> setVoiceGuidance(true));
        voiceGuidanceOffButton.setOnClickListener(v -> setVoiceGuidance(false));

        voiceGuidanceLanguageStateText = settingTitle("Language",
                voiceGuidanceLanguageName(TilePreferences.voiceGuidanceLanguage(this)));
        card.addView(voiceGuidanceLanguageStateText);
        voiceGuidanceLanguageButton = secondaryButton(
                voiceGuidanceLanguageName(TilePreferences.voiceGuidanceLanguage(this)) + "  ▾");
        voiceGuidanceLanguageButton.setOnClickListener(v -> showVoiceGuidanceLanguageMenu());
        card.addView(voiceGuidanceLanguageButton);

        return card;
    }

    private LinearLayout quickAccessCard() {
        LinearLayout card = card();
        card.addView(cardTitle("Quick Access"));
        card.addView(subhead("Assign the services used when you press the NC/AMB button twice or three times."));

        quickAccessDoubleStateText = settingTitle("Press NC/AMB twice",
                quickAccessFunctionName(TilePreferences.quickAccessDoubleFunction(this)));
        card.addView(quickAccessDoubleStateText);
        quickAccessDoubleButton = secondaryButton(quickAccessFunctionName(
                TilePreferences.quickAccessDoubleFunction(this)) + "  ▾");
        quickAccessDoubleButton.setOnClickListener(v -> showQuickAccessMenu(true));
        card.addView(quickAccessDoubleButton);

        quickAccessTripleStateText = settingTitle("Press NC/AMB three times",
                quickAccessFunctionName(TilePreferences.quickAccessTripleFunction(this)));
        card.addView(quickAccessTripleStateText);
        quickAccessTripleButton = secondaryButton(quickAccessFunctionName(
                TilePreferences.quickAccessTripleFunction(this)) + "  ▾");
        quickAccessTripleButton.setOnClickListener(v -> showQuickAccessMenu(false));
        card.addView(quickAccessTripleButton);
        return card;
    }

    private LinearLayout tileCard() {
        LinearLayout card = card();
        card.addView(cardTitle("Quick Settings"));
        card.addView(subhead("Add one tile for noise control, Speak-to-Chat, and pause on remove."));
        Button button = secondaryButton("Add XM Control tile");
        button.setOnClickListener(v -> requestTile(AncTileService.class, R.string.tile_anc, R.drawable.ic_tile_anc));
        card.addView(button);
        return card;
    }

    private LinearLayout eqSliderRow(int index) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(index == 0 ? 12 : 8), 0, dp(3));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView name = label(EQ_BAND_LABELS[index], 14, Typeface.NORMAL, index == 0 ? primary : text);
        name.setPadding(dp(2), 0, 0, 0);
        header.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView value = label("", 14, Typeface.BOLD, text);
        value.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        value.setPadding(0, 0, dp(2), 0);
        eqValueTexts[index] = value;
        header.addView(value, new LinearLayout.LayoutParams(dp(44), ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(header);

        SeekBar slider = seekBar(20);
        eqSeekBars[index] = slider;
        row.addView(slider, sliderParams(2, 0));

        final int band = index;
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int value = SmoothSlider.value(seekBar);
                updateEqValueLabel(band, value);
                if (!fromUser || updatingUi) return;
                if (!eqSliderDragging[band]) {
                    TilePreferences.editEqValueAsManual(MainActivity.this, band, value);
                    renderEqualizer();
                    scheduleEqSend(90L);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                eqSliderDragging[band] = true;
                cancelPendingEqSend();
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                eqSliderDragging[band] = false;
                TilePreferences.editEqValueAsManual(
                        MainActivity.this, band, SmoothSlider.value(seekBar));
                renderEqualizer();
                scheduleEqSend(90L);
            }
        });
        return row;
    }

    private void refreshDevices() {
        refreshDevices(false);
    }

    private void refreshDevices(boolean forceStatus) {
        if (deviceList == null) return;
        deviceList.removeAllViews();
        if (!SonyDeviceRepository.hasConnectPermission(this)) {
            TilePreferences.markHeadsetDisconnected(this);
            deviceList.addView(paragraph("Permission is not granted yet."));
            renderState("Bluetooth access required");
            return;
        }

        List<BluetoothDevice> devices = SonyDeviceRepository.bondedSupportedDevices(this);
        if (devices.isEmpty()) {
            TilePreferences.markHeadsetDisconnected(this);
            deviceList.addView(paragraph("Pair a supported Sony XM headset in Android Bluetooth settings."));
            renderState("No headset found");
            return;
        }

        String previous = TilePreferences.selectedDeviceAddress(this);
        String selected = selectedOrFallback(devices);
        boolean selectionChanged = !selected.equals(previous);
        if (selectionChanged) {
            TilePreferences.setSelectedDeviceAddress(this, selected);
        }

        for (BluetoothDevice device : devices) {
            deviceList.addView(deviceRow(device, selected));
        }
        renderState(null);
        requestStatusRefresh(forceStatus || selectionChanged);
    }

    @SuppressLint("MissingPermission")
    private RadioButton deviceRow(BluetoothDevice device, String selected) {
        RadioButton row = new RadioButton(this);
        row.setText(device.getName());
        row.setTextColor(text);
        row.setTextSize(15);
        row.setIncludeFontPadding(false);
        row.setLineSpacing(dp(3), 1f);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinHeight(dp(58));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            row.setButtonTintList(ColorStateList.valueOf(primary));
        }
        row.setPadding(0, dp(10), 0, dp(10));
        row.setChecked(device.getAddress().equals(selected));
        row.setOnClickListener(v -> {
            if (device.getAddress().equals(TilePreferences.selectedDeviceAddress(this))) {
                requestStatusRefresh(true);
                return;
            }
            TilePreferences.setSelectedDeviceAddress(this, device.getAddress());
            localChangeVersion++;
            refreshDevices(true);
        });
        return row;
    }

    private void registerBluetoothReceiver() {
        if (bluetoothReceiverRegistered) return;
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        filter.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            filter.addAction(BluetoothLeAudio.ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(bluetoothReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(bluetoothReceiver, filter);
        }
        bluetoothReceiverRegistered = true;
    }

    private void unregisterBluetoothReceiver() {
        if (!bluetoothReceiverRegistered) return;
        unregisterReceiver(bluetoothReceiver);
        bluetoothReceiverRegistered = false;
    }

    @SuppressLint("MissingPermission")
    private void handleBluetoothEvent(Intent intent) {
        if (intent == null || !SonyDeviceRepository.hasConnectPermission(this)) return;

        BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        String action = intent.getAction();
        int state = connectionState(intent, action);

        if (device != null && !SonyDeviceRepository.isSelectedOrSupportedDevice(this, device)) {
            return;
        }

        if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action) || state == BluetoothProfile.STATE_CONNECTED) {
            main.removeCallbacks(delayedConnectionCheck);
            if (device != null) {
                SonyDeviceRepository.noteConnectedIdentity(this, device);
            }
            statusFailureCount = 0;
            localChangeVersion++;
            refreshDevices(true);
            return;
        }

        if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action) || state == BluetoothProfile.STATE_DISCONNECTED) {
            statusFailureCount = Math.max(statusFailureCount, 1);
            renderState("Checking connection...");
            main.removeCallbacks(delayedConnectionCheck);
            main.postDelayed(delayedConnectionCheck, 1_500L);
            scheduleConnectionRetry();
        }
    }

    private int connectionState(Intent intent, String action) {
        if (BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED.equals(action)
                || BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED.equals(action)
                || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && BluetoothLeAudio.ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED.equals(action))) {
            return intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED);
        }
        return -1;
    }

    private void scheduleConnectionRetry() {
        main.removeCallbacks(connectionRetry);
        if (!destroyed && bluetoothReceiverRegistered) {
            main.postDelayed(connectionRetry, TilePreferences.headsetConnected(this) ? 12_000L : 4_000L);
        }
    }

    private void requestStatusRefresh(boolean force) {
        if (!SonyDeviceRepository.hasConnectPermission(this) || statusReadRunning) {
            if (force && statusReadRunning) {
                statusRefreshQueued = true;
            }
            return;
        }
        if (!force && !TilePreferences.shouldRefreshStatus(this)) {
            return;
        }

        int requestVersion = localChangeVersion;
        int requestGeneration = ++statusReadGeneration;
        statusReadRunning = true;
        renderState("Updating...");
        NoiseControlState fallback = TilePreferences.noiseControlState(this);
        statusReadFuture = commands.submit(() -> {
            SonyMdrClient.StatusResult result = SonyMdrClient.readStatus(this, fallback);
            main.post(() -> {
                if (destroyed) return;
                if (requestGeneration != statusReadGeneration) return;
                statusReadFuture = null;
                statusReadRunning = false;
                if (result.success && requestVersion == localChangeVersion) {
                    statusFailureCount = 0;
                    TilePreferences.applyStatus(this, result.status, result.complete);
                    renderState(null);
                } else {
                    boolean audioConnected = SonyDeviceRepository.isSelectedDeviceConnected(this);
                    if (!result.success) {
                        statusFailureCount++;
                        if (!audioConnected || statusFailureCount >= 2) {
                            TilePreferences.markHeadsetDisconnected(this);
                        }
                    }
                    renderState(result.success ? null
                            : !audioConnected || statusFailureCount >= 2 ? "Not connected" : "Connection unavailable");
                }
                if (statusRefreshQueued) {
                    statusRefreshQueued = false;
                    requestStatusRefresh(true);
                }
                scheduleConnectionRetry();
            });
        });
    }

    private void cancelStatusRefreshForCommand() {
        statusReadGeneration++;
        statusReadRunning = false;
        statusRefreshQueued = false;
        Future<?> future = statusReadFuture;
        statusReadFuture = null;
        if (future != null) {
            future.cancel(true);
        }
    }

    private void renderState(String statusOverride) {
        updatingUi = true;
        try {
            NoiseControlState noise = TilePreferences.noiseControlState(this);
            if (deviceSummaryText != null) {
                deviceSummaryText.setText(statusOverride == null ? summaryLine() : statusOverride);
            }
            if (batteryText != null) {
                batteryText.setText(batteryLine());
            }
            if (noiseStatusText != null) {
                noiseStatusText.setText(noise.summary());
            }
            if (!ambientSliderDragging) {
                setTextIfChanged(ambientLevelText, String.valueOf(noise.ambientLevel));
            }
            if (ambientSeekBar != null
                    && !ambientSliderDragging
                    && !SmoothSlider.isAtValue(ambientSeekBar, noise.ambientLevel)) {
                SmoothSlider.setValue(ambientSeekBar, noise.ambientLevel);
            }

            setSelected(noiseAncButton, noise.mode == NoiseControlState.Mode.ANC);
            setSelected(noiseAmbientButton, noise.mode == NoiseControlState.Mode.AMBIENT);
            setSelected(noiseOffButton, noise.mode == NoiseControlState.Mode.OFF);
            setSelected(normalAmbientButton, !noise.voiceAmbient);
            setSelected(voiceAmbientButton, noise.voiceAmbient);

            boolean dsee = TilePreferences.dseeAuto(this);
            boolean quality = TilePreferences.connectionQuality(this);
            boolean multipoint = TilePreferences.multipointEnabled(this);
            boolean speak = TilePreferences.speakToChatEnabled(this);
            boolean wear = TilePreferences.wearPauseEnabled(this);
            boolean touch = TilePreferences.touchPanelEnabled(this);
            boolean autoPower = TilePreferences.autoPowerEnabled(this);
            boolean voiceGuidance = TilePreferences.voiceGuidanceEnabled(this);
            int voiceGuidanceLanguage = TilePreferences.voiceGuidanceLanguage(this);
            setSettingText(dseeStateText, "DSEE Extreme", dsee ? "Auto" : "Off");
            setSettingText(qualityStateText, "Bluetooth connection quality", quality ? "Quality" : "Stability");
            setSettingText(multipointStateText, "Multipoint support", multipoint ? "On" : "Off");
            setSettingText(speakStateText, "Speak-to-Chat", speak ? "On" : "Off");
            setSettingText(wearStateText, "Pause when headphones are removed", wear ? "On" : "Off");
            setSettingText(touchStateText, "Touch sensor control panel", touch ? "On" : "Off");
            setSettingText(autoPowerStateText, "Automatic power off", autoPower ? "On" : "Off");
            setSettingText(voiceGuidanceStateText, "Notification & Voice Guide", voiceGuidance ? "On" : "Off");
            setSettingText(voiceGuidanceLanguageStateText, "Language", voiceGuidanceLanguageName(voiceGuidanceLanguage));
            setOnOff(dseeAutoButton, dseeOffButton, dsee);
            setQualityButtons(quality);
            setOnOff(multipointOnButton, multipointOffButton, multipoint);
            setOnOff(speakOnButton, speakOffButton, speak);
            setOnOff(wearOnButton, wearOffButton, wear);
            setOnOff(touchOnButton, touchOffButton, touch);
            setOnOff(autoPowerOnButton, autoPowerOffButton, autoPower);
            setOnOff(voiceGuidanceOnButton, voiceGuidanceOffButton, voiceGuidance);
            if (voiceGuidanceLanguageButton != null) {
                String languageLabel = voiceGuidanceLanguageName(voiceGuidanceLanguage);
                voiceGuidanceLanguageButton.setText(TilePreferences.voiceGuidanceType(this) == 2
                        ? languageLabel + "  ▾"
                        : languageLabel);
            }
            renderEqualizer();
            renderQuickAccess();
            setControlsEnabled(TilePreferences.headsetConnected(this)
                    && SonyDeviceRepository.isSelectedDeviceConnected(this));

        } finally {
            updatingUi = false;
        }
    }

    private void renderQuickAccess() {
        int doublePress = TilePreferences.quickAccessDoubleFunction(this);
        int triplePress = TilePreferences.quickAccessTripleFunction(this);
        if (quickAccessDoubleStateText != null) {
            setSettingText(quickAccessDoubleStateText, "Press NC/AMB twice", quickAccessFunctionName(doublePress));
        }
        if (quickAccessTripleStateText != null) {
            setSettingText(quickAccessTripleStateText, "Press NC/AMB three times", quickAccessFunctionName(triplePress));
        }
        if (quickAccessDoubleButton != null) {
            quickAccessDoubleButton.setText(quickAccessFunctionName(doublePress) + "  ▾");
        }
        if (quickAccessTripleButton != null) {
            quickAccessTripleButton.setText(quickAccessFunctionName(triplePress) + "  ▾");
        }
    }

    private String summaryLine() {
        if (!SonyDeviceRepository.hasConnectPermission(this)) {
            return "Bluetooth access required";
        }
        String target = TilePreferences.selectedDeviceAddress(this);
        if (target == null) {
            return "Select a headset";
        }
        if (statusReadRunning) {
            return "Updating...";
        }
        if (!TilePreferences.headsetConnected(this)) {
            return "Not connected";
        }
        return "Connected";
    }

    private String batteryLine() {
        if (TilePreferences.selectedDeviceAddress(this) == null) {
            return "Battery: Not available";
        }
        String cached = TilePreferences.batteryText(this);
        if (!TilePreferences.headsetConnected(this)) {
            return "Battery: Not available";
        }
        if (statusReadRunning && !TilePreferences.hasBatteryText(this)) {
            return "Battery: Updating";
        }
        return "Battery: " + cached;
    }

    private void renderEqualizer() {
        int preset = TilePreferences.eqPreset(this);
        int[] values = TilePreferences.eqValues(this);
        if (eqPresetButton != null) {
            eqPresetButton.setText(eqPresetName(preset) + "  ▾");
        }
        if (eqSummaryText != null) {
            setSettingText(eqSummaryText, "Equalizer", eqSummary(preset, values));
        }
        for (int i = 0; i < eqSeekBars.length; i++) {
            if (eqSliderDragging[i]) {
                continue;
            }
            if (eqSeekBars[i] != null && !SmoothSlider.isAtValue(eqSeekBars[i], values[i])) {
                SmoothSlider.setValue(eqSeekBars[i], values[i]);
            }
            updateEqValueLabel(i, values[i]);
        }
    }

    private void updateEqValueLabel(int index, int value) {
        if (index < 0 || index >= eqValueTexts.length || eqValueTexts[index] == null) return;
        setTextIfChanged(eqValueTexts[index], eqValue(value));
    }

    private void sendNoiseControl(NoiseControlState.Mode mode) {
        NoiseControlState current = TilePreferences.noiseControlState(this);
        if (mode == NoiseControlState.Mode.AMBIENT) {
            sendNoiseState(NoiseControlState.ambient(current.ambientLevel, current.voiceAmbient));
        } else if (mode == NoiseControlState.Mode.OFF) {
            sendNoiseState(NoiseControlState.off(current.ambientLevel, current.voiceAmbient));
        } else {
            sendNoiseState(NoiseControlState.anc(current.ambientLevel, current.voiceAmbient));
        }
    }

    private void sendNoiseState(NoiseControlState state) {
        sendCommand("Noise control", SonyCommand.noiseControl(state), () -> {
            TilePreferences.setNoiseControlState(this, state);
            TilePreferences.markNoiseStateRefreshed(this);
        });
    }

    private void setAmbientVoice(boolean voice) {
        NoiseControlState current = TilePreferences.noiseControlState(this);
        sendNoiseState(NoiseControlState.ambient(current.ambientLevel, voice));
    }

    private void setDsee(boolean auto) {
        sendCommand("DSEE Extreme", auto ? SonyCommand.DSEE_AUTO : SonyCommand.DSEE_OFF,
                () -> TilePreferences.setDseeAuto(this, auto));
    }

    private void setConnectionQuality(boolean quality) {
        sendCommand("Bluetooth connection quality", quality ? SonyCommand.CONNECTION_QUALITY : SonyCommand.CONNECTION_STABILITY,
                () -> TilePreferences.setConnectionQuality(this, quality));
    }

    private void setMultipoint(boolean enabled) {
        sendCommand("Multipoint support", enabled ? SonyCommand.MULTIPOINT_ON : SonyCommand.MULTIPOINT_OFF,
                () -> TilePreferences.setMultipointEnabled(this, enabled));
    }

    private void setSpeakToChat(boolean enabled) {
        sendCommand("Speak-to-Chat", enabled ? SonyCommand.SPEAK_TO_CHAT_ON : SonyCommand.SPEAK_TO_CHAT_OFF,
                () -> TilePreferences.setSpeakToChatEnabled(this, enabled));
    }

    private void setWearPause(boolean enabled) {
        sendCommand("Pause when removed", enabled ? SonyCommand.WEAR_PAUSE_ON : SonyCommand.WEAR_PAUSE_OFF,
                () -> TilePreferences.setWearPauseEnabled(this, enabled));
    }

    private void setTouchPanel(boolean enabled) {
        sendCommand("Touch sensor control panel", enabled ? SonyCommand.TOUCH_PANEL_ON : SonyCommand.TOUCH_PANEL_OFF,
                () -> TilePreferences.setTouchPanelEnabled(this, enabled));
    }

    private void setAutoPower(boolean enabled) {
        sendCommand("Automatic power off", enabled ? SonyCommand.AUTO_POWER_ON : SonyCommand.AUTO_POWER_OFF,
                () -> TilePreferences.setAutoPowerEnabled(this, enabled));
    }

    private void setVoiceGuidance(boolean enabled) {
        int language = TilePreferences.voiceGuidanceLanguage(this);
        int type = TilePreferences.voiceGuidanceType(this);
        sendVoiceGuidanceCommand("Notification & Voice Guide", enabled, language, type);
    }

    private void setVoiceGuidanceLanguage(int language) {
        if (TilePreferences.voiceGuidanceType(this) != 2) {
            showFailureToast("Language changes are not supported for this headset.");
            renderState(null);
            return;
        }
        boolean enabled = TilePreferences.voiceGuidanceEnabled(this);
        sendVoiceGuidanceCommand("Voice guide language", enabled, language, 2);
    }

    private void sendVoiceGuidanceCommand(String label, boolean enabled, int language, int type) {
        if (!SonyDeviceRepository.hasConnectPermission(this)) {
            showFailureToast("Bluetooth access required.");
            return;
        }
        if (!TilePreferences.headsetConnected(this) && !statusReadRunning) {
            renderState("Connect your headset to make changes");
            return;
        }
        boolean previousEnabled = TilePreferences.voiceGuidanceEnabled(this);
        int previousLanguage = TilePreferences.voiceGuidanceLanguage(this);
        localChangeVersion++;
        TilePreferences.setVoiceGuidance(this, enabled, language);
        renderState("Applying...");
        SonyCommand command = SonyCommand.voiceGuidance(enabled, language, type);
        NoiseControlState fallback = TilePreferences.noiseControlState(this);
        cancelStatusRefreshForCommand();
        commands.execute(() -> {
            SonyMdrClient.StatusResult result = SonyMdrClient.sendVoiceGuidance(this, command, fallback);
            main.post(() -> {
                if (destroyed) return;
                if (result.success && result.status != null) {
                    statusFailureCount = 0;
                    TilePreferences.applyStatus(this, result.status);
                    renderState("Applied");
                    return;
                }

                statusFailureCount = Math.max(statusFailureCount, 1);
                TilePreferences.setVoiceGuidance(this, previousEnabled, previousLanguage);
                TilePreferences.invalidateStatus(this);
                main.removeCallbacks(delayedConnectionCheck);
                main.postDelayed(delayedConnectionCheck, 1_500L);
                showFailureToast(result.message);
                requestStatusRefresh(true);
                renderState(shortMessage(result.message));
            });
        });
    }

    private void setQuickAccessFunction(boolean doublePress, int function) {
        int doublePressFunction = TilePreferences.quickAccessDoubleFunction(this);
        int triplePressFunction = TilePreferences.quickAccessTripleFunction(this);
        if (doublePress) {
            doublePressFunction = function;
        } else {
            triplePressFunction = function;
        }
        int finalDoublePressFunction = doublePressFunction;
        int finalTriplePressFunction = triplePressFunction;
        sendCommand("Quick Access", SonyCommand.quickAccessSlots(finalDoublePressFunction, finalTriplePressFunction),
                () -> {
                    TilePreferences.setQuickAccessFunctions(this, finalDoublePressFunction, finalTriplePressFunction);
                });
    }

    private void selectEqPreset(int preset) {
        if (!SonyDeviceRepository.hasConnectPermission(this)) {
            showFailureToast("Bluetooth access required.");
            return;
        }
        if (!TilePreferences.headsetConnected(this) && !statusReadRunning) {
            renderState("Connect your headset to make changes");
            return;
        }

        cancelPendingEqSend();
        int previousPreset = TilePreferences.eqPreset(this);
        int[] previousValues = TilePreferences.eqValues(this);
        localChangeVersion++;
        TilePreferences.setEqPreset(this, preset);
        renderState("Applying equalizer...");
        cancelStatusRefreshForCommand();
        commands.execute(() -> {
            SonyMdrClient.Result result = SonyMdrClient.send(this, SonyCommand.equalizerPreset(preset));
            SonyMdrClient.StatusResult eqResult = result.success ? SonyMdrClient.readEqualizerState(this) : null;
            main.post(() -> {
                if (destroyed) return;
                if (!result.success) {
                    statusFailureCount = Math.max(statusFailureCount, 1);
                    TilePreferences.setEqState(this, previousPreset, previousValues);
                    TilePreferences.invalidateStatus(this);
                    main.removeCallbacks(delayedConnectionCheck);
                    main.postDelayed(delayedConnectionCheck, 1_500L);
                    showFailureToast(result.message);
                    renderState(shortMessage(result.message));
                    return;
                }

                TilePreferences.markHeadsetConnected(this);
                statusFailureCount = 0;
                if (eqResult != null
                        && eqResult.success
                        && eqResult.status != null
                        && eqResult.status.eqPreset >= 0
                        && eqResult.status.eqValues != null
                        && eqResult.status.eqValues.length >= TilePreferences.EQ_VALUE_COUNT) {
                    TilePreferences.setEqState(this, eqResult.status.eqPreset, eqResult.status.eqValues);
                    renderState("Applied");
                    return;
                }

                TilePreferences.setEqPreset(this, preset);
                renderState("Preset selected");
            });
        });
    }

    private void setEqualizerFlat() {
        cancelPendingEqSend();
        int[] flat = SonyCommand.flatEqualizerValues();
        sendCommand("Equalizer flat", SonyCommand.equalizer(EQ_MANUAL, flat),
                () -> TilePreferences.setEqState(this, EQ_MANUAL, flat));
    }

    private void saveEqualizerPreset(int preset) {
        cancelPendingEqSend();
        int[] values = TilePreferences.eqValues(this);
        sendCommand("Save " + eqPresetName(preset), SonyCommand.equalizer(preset, values),
                () -> TilePreferences.setEqState(this, preset, values));
    }

    private void scheduleEqSend(long delayMs) {
        if (!TilePreferences.headsetConnected(this)) {
            return;
        }
        main.removeCallbacks(pendingEqSend);
        main.postDelayed(pendingEqSend, delayMs);
    }

    private void cancelPendingEqSend() {
        main.removeCallbacks(pendingEqSend);
    }

    private void sendCurrentEqualizer() {
        if (!TilePreferences.headsetConnected(this)) {
            return;
        }
        int[] values = TilePreferences.eqValues(this);
        sendCommand("Equalizer", SonyCommand.equalizer(EQ_MANUAL, values), null);
    }

    private void sendCommand(String label, SonyCommand command, Runnable optimisticUpdate) {
        if (!SonyDeviceRepository.hasConnectPermission(this)) {
            showFailureToast("Bluetooth access required.");
            return;
        }
        if (!TilePreferences.headsetConnected(this) && !statusReadRunning) {
            renderState("Connect your headset to make changes");
            return;
        }
        localChangeVersion++;
        if (optimisticUpdate != null) {
            optimisticUpdate.run();
        }
        renderState("Applying...");
        cancelStatusRefreshForCommand();
        commands.execute(() -> {
            SonyMdrClient.Result result = SonyMdrClient.send(this, command);
            main.post(() -> {
                if (destroyed) return;
                if (!result.success) {
                    statusFailureCount = Math.max(statusFailureCount, 1);
                    TilePreferences.invalidateStatus(this);
                    main.removeCallbacks(delayedConnectionCheck);
                    main.postDelayed(delayedConnectionCheck, 1_500L);
                    showFailureToast(result.message);
                } else {
                    statusFailureCount = 0;
                    TilePreferences.markHeadsetConnected(this);
                }
                renderState(result.success ? "Applied" : shortMessage(result.message));
            });
        });
    }

    private void showFailureToast(String message) {
        long now = System.currentTimeMillis();
        if (now - lastFailureToastAt < 3500L) {
            return;
        }
        lastFailureToastAt = now;
        Toast.makeText(this, shortMessage(message), Toast.LENGTH_SHORT).show();
    }

    private void showEqPresetMenu() {
        if (eqPresetButton == null) return;
        LinearLayout menu = optionList();
        Dialog dialog = optionDialog("Equalizer preset", menu, 390);
        int currentPreset = TilePreferences.eqPreset(this);
        for (int preset : EQ_PRESETS) {
            TextView item = optionItem(eqPresetName(preset), preset == currentPreset);
            item.setOnClickListener(v -> {
                dialog.dismiss();
                selectEqPreset(preset);
            });
            menu.addView(item);
        }
        dialog.show();
    }

    private void showSavePresetMenu(View anchor) {
        LinearLayout menu = optionList();
        Dialog dialog = optionDialog("Save preset", menu, 180);
        TextView user1 = optionItem("Save to User 1", false);
        TextView user2 = optionItem("Save to User 2", false);
        user1.setOnClickListener(v -> {
            dialog.dismiss();
            saveEqualizerPreset(EQ_USER_1);
        });
        user2.setOnClickListener(v -> {
            dialog.dismiss();
            saveEqualizerPreset(EQ_USER_2);
        });
        menu.addView(user1);
        menu.addView(user2);
        dialog.show();
    }

    private void showQuickAccessMenu(boolean doublePress) {
        LinearLayout menu = optionList();
        Dialog dialog = optionDialog(doublePress ? "Press NC/AMB twice" : "Press NC/AMB three times", menu, 360);
        int current = doublePress
                ? TilePreferences.quickAccessDoubleFunction(this)
                : TilePreferences.quickAccessTripleFunction(this);
        boolean currentKnown = false;
        for (int function : QUICK_ACCESS_FUNCTIONS) {
            currentKnown |= function == current;
            TextView item = optionItem(quickAccessFunctionName(function), function == current);
            item.setOnClickListener(v -> {
                dialog.dismiss();
                setQuickAccessFunction(doublePress, function);
            });
            menu.addView(item);
        }
        if (!currentKnown) {
            TextView item = optionItem(quickAccessFunctionName(current), true);
            item.setOnClickListener(v -> dialog.dismiss());
            menu.addView(item);
        }
        dialog.show();
    }

    private void showVoiceGuidanceLanguageMenu() {
        LinearLayout menu = optionList();
        Dialog dialog = optionDialog("Voice guide language", menu, 390);
        int current = TilePreferences.voiceGuidanceLanguage(this);
        for (int language : VOICE_GUIDANCE_LANGUAGES) {
            TextView item = optionItem(voiceGuidanceLanguageName(language), language == current);
            item.setOnClickListener(v -> {
                dialog.dismiss();
                setVoiceGuidanceLanguage(language);
            });
            menu.addView(item);
        }
        dialog.show();
    }

    private Dialog optionDialog(String title, LinearLayout menu, int maxHeightDp) {
        Dialog dialog = new Dialog(this, R.style.NoiseControlTileDialogTheme);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(CARD_PADDING_DP), dp(CARD_PADDING_DP), dp(CARD_PADDING_DP), dp(18));
        panel.setBackground(popupBackground());

        TextView titleView = label(title, 20, Typeface.BOLD, text);
        titleView.setPadding(dp(2), 0, dp(2), dp(12));
        panel.addView(titleView);

        MaxHeightScrollView scroller = new MaxHeightScrollView(this, dp(maxHeightDp));
        scroller.setFillViewport(false);
        scroller.setVerticalScrollBarEnabled(true);
        scroller.setScrollbarFadingEnabled(false);
        scroller.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
        scroller.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        scroller.setPadding(0, 0, dp(6), 0);
        scroller.setClipToPadding(false);
        scroller.addView(menu, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(scroller, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        dialog.setContentView(panel);
        dialog.setOnShowListener(d -> {
            Window window = dialog.getWindow();
            if (window == null) return;
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            WindowManager.LayoutParams attrs = window.getAttributes();
            attrs.width = Math.min(dp(520), getResources().getDisplayMetrics().widthPixels - dp(32));
            attrs.height = WindowManager.LayoutParams.WRAP_CONTENT;
            attrs.dimAmount = 0.36f;
            window.setAttributes(attrs);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        });
        return dialog;
    }

    private LinearLayout optionList() {
        LinearLayout menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setPadding(0, 0, 0, 0);
        return menu;
    }

    private TextView optionItem(String value, boolean selected) {
        TextView item = label(value, 15, selected ? Typeface.BOLD : Typeface.NORMAL, selected ? onPrimary : text);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setSingleLine(true);
        item.setEllipsize(TextUtils.TruncateAt.END);
        item.setMinHeight(dp(50));
        item.setPadding(dp(18), 0, dp(18), 0);
        item.setBackground(selected ? buttonBackground(primary, dp(22)) : buttonBackground(Color.TRANSPARENT, dp(22)));
        item.setClickable(true);
        item.setFocusable(true);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(50));
        params.setMargins(0, dp(3), 0, dp(3));
        item.setLayoutParams(params);
        return item;
    }

    private GradientDrawable popupBackground() {
        GradientDrawable bg = buttonBackground(surface, dp(24));
        bg.setStroke(Math.max(1, dp(1)), withAlpha(muted, 76));
        return bg;
    }

    private void requestTile(Class<?> serviceClass, int labelRes, int iconRes) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, "Add XM Control from Android Quick Settings.", Toast.LENGTH_LONG).show();
            return;
        }

        StatusBarManager manager = getSystemService(StatusBarManager.class);
        if (manager == null) {
            Toast.makeText(this, "Quick Settings is unavailable.", Toast.LENGTH_SHORT).show();
            return;
        }

        ComponentName component = new ComponentName(this, serviceClass);
        manager.requestAddTileService(
                component,
                getString(labelRes),
                Icon.createWithResource(this, iconRes),
                getMainExecutor(),
                result -> Toast.makeText(this, tileResultText(result), Toast.LENGTH_SHORT).show());
    }

    private static String tileResultText(int result) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            switch (result) {
                case StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED:
                    return "Tile added";
                case StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED:
                    return "Tile already added";
                case StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED:
                    return "Tile not added";
                default:
                    return "Tile request finished";
            }
        }
        return "Tile request finished";
    }

    private void requestBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && !SonyDeviceRepository.hasConnectPermission(this)) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQUEST_BLUETOOTH);
            return;
        }
        onBluetoothPermissionAvailable();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_BLUETOOTH) {
            if (SonyDeviceRepository.hasConnectPermission(this)) {
                onBluetoothPermissionAvailable();
            } else {
                TilePreferences.markHeadsetDisconnected(this);
                renderState("Bluetooth access required");
            }
        }
    }

    private void onBluetoothPermissionAvailable() {
        buildUi();
        refreshDevices(true);
        main.postDelayed(() -> refreshDevices(false), 450L);
        main.postDelayed(() -> refreshDevices(false), 1_500L);
        scheduleConnectionRetry();
    }

    private String selectedOrFallback(List<BluetoothDevice> devices) {
        String connected = SonyDeviceRepository.connectedSupportedAddress(this, devices);
        if (connected != null) {
            return connected;
        }

        String selected = TilePreferences.selectedDeviceAddress(this);
        for (BluetoothDevice device : devices) {
            if (device.getAddress().equals(selected)) {
                return selected;
            }
        }
        return devices.get(0).getAddress();
    }

    private void setQualityButtons(boolean quality) {
        setSelected(qualityButton, quality);
        setSelected(stabilityButton, !quality);
    }

    private void setControlsEnabled(boolean enabled) {
        setEnabled(noiseAncButton, enabled);
        setEnabled(noiseAmbientButton, enabled);
        setEnabled(noiseOffButton, enabled);
        setEnabled(normalAmbientButton, enabled);
        setEnabled(voiceAmbientButton, enabled);
        setEnabled(dseeAutoButton, enabled);
        setEnabled(dseeOffButton, enabled);
        setEnabled(qualityButton, enabled);
        setEnabled(stabilityButton, enabled);
        setEnabled(multipointOnButton, enabled);
        setEnabled(multipointOffButton, enabled);
        setEnabled(speakOnButton, enabled);
        setEnabled(speakOffButton, enabled);
        setEnabled(wearOnButton, enabled);
        setEnabled(wearOffButton, enabled);
        setEnabled(touchOnButton, enabled);
        setEnabled(touchOffButton, enabled);
        setEnabled(autoPowerOnButton, enabled);
        setEnabled(autoPowerOffButton, enabled);
        setEnabled(voiceGuidanceOnButton, enabled);
        setEnabled(voiceGuidanceOffButton, enabled);
        setEnabled(voiceGuidanceLanguageButton, enabled && TilePreferences.voiceGuidanceType(this) == 2);
        setEnabled(quickAccessDoubleButton, enabled);
        setEnabled(quickAccessTripleButton, enabled);
        setEnabled(eqPresetButton, enabled);
        setEnabled(eqSaveButton, enabled);
        setEnabled(eqFlatButton, enabled);
        if (ambientSeekBar != null) {
            if (!ambientSliderDragging && ambientSeekBar.isEnabled() != enabled) {
                ambientSeekBar.setEnabled(enabled);
            }
            ambientSeekBar.setAlpha(1f);
        }
        for (int i = 0; i < eqSeekBars.length; i++) {
            SeekBar seekBar = eqSeekBars[i];
            if (seekBar != null) {
                if (!eqSliderDragging[i] && seekBar.isEnabled() != enabled) {
                    seekBar.setEnabled(enabled);
                }
                seekBar.setAlpha(1f);
            }
        }
    }

    private void setEnabled(Button button, boolean enabled) {
        if (button == null) return;
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : 0.68f);
    }

    private void setOnOff(Button onButton, Button offButton, boolean enabled) {
        setSelected(onButton, enabled);
        setSelected(offButton, !enabled);
    }

    private void setSelected(Button button, boolean selected) {
        if (button == null) return;
        button.setTextColor(selected ? onPrimary : text);
        button.setBackground(buttonBackground(selected ? primary : surfaceVariant, dp(CONTROL_RADIUS_DP)));
    }

    private void setTextIfChanged(TextView view, String value) {
        if (view != null && !TextUtils.equals(view.getText(), value)) {
            view.setText(value);
        }
    }

    private TextView settingTitle(String title, String value) {
        TextView view = label("", 14, Typeface.NORMAL, text);
        view.setPadding(0, dp(14), 0, dp(6));
        view.setLineSpacing(dp(3), 1f);
        setSettingText(view, title, value);
        return view;
    }

    private void setSettingText(TextView view, String title, String value) {
        if (view != null) {
            String combined = title + "\n" + value;
            SpannableString styled = new SpannableString(combined);
            styled.setSpan(new StyleSpan(Typeface.BOLD), 0, title.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            styled.setSpan(new ForegroundColorSpan(text), 0, title.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            styled.setSpan(new StyleSpan(Typeface.NORMAL), title.length() + 1, combined.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            styled.setSpan(new ForegroundColorSpan(muted), title.length() + 1, combined.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            styled.setSpan(new LeadingMarginSpan.Standard(dp(2), 0), 0, combined.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            view.setText(styled);
        }
    }

    private TextView settingLabel(String value) {
        TextView view = label(value, 14, Typeface.BOLD, text);
        view.setPadding(dp(2), 0, 0, 0);
        return view;
    }

    private TextView cardTitle(String value) {
        TextView view = label(value, 20, Typeface.BOLD, text);
        view.setPadding(dp(2), 0, 0, 0);
        return view;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        return row;
    }

    private LinearLayout labelValueRow(TextView label, TextView value, int topDp, int bottomDp) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 0, 0, 0);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.setMargins(0, dp(topDp), 0, dp(bottomDp));
        row.setLayoutParams(rowParams);
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(value, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(CARD_PADDING_DP), dp(CARD_PADDING_DP), dp(CARD_PADDING_DP), dp(CARD_PADDING_DP));
        card.setBackground(cardBackground());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, dp(15));
        card.setLayoutParams(params);
        return card;
    }

    private View divider() {
        View line = new View(this);
        line.setBackgroundColor(withAlpha(muted, 46));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Math.max(1, dp(1)));
        params.setMargins(0, dp(17), 0, dp(10));
        line.setLayoutParams(params);
        return line;
    }

    private TextView paragraph(String value) {
        TextView view = label(value, 14, Typeface.NORMAL, muted);
        view.setLineSpacing(dp(2), 1f);
        view.setPadding(0, dp(7), 0, dp(12));
        return view;
    }

    private TextView subhead(String value) {
        TextView view = label(value, 14, Typeface.NORMAL, muted);
        view.setLineSpacing(dp(2), 1f);
        view.setPadding(0, dp(4), 0, dp(14));
        return view;
    }

    private TextView label(String value, int sp, int style, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setTypeface(Typeface.DEFAULT, style);
        view.setIncludeFontPadding(false);
        return view;
    }

    private Button primaryButton(String value) {
        Button button = button(value, primary, onPrimary);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return button;
    }

    private Button secondaryButton(String value) {
        return button(value, surfaceVariant, text);
    }

    private Button rowButton(String value) {
        Button button = button(value, surfaceVariant, text);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(CONTROL_HEIGHT_DP), 1f);
        params.setMargins(dp(CONTROL_HORIZONTAL_GAP_DP), dp(CONTROL_VERTICAL_GAP_DP),
                dp(CONTROL_HORIZONTAL_GAP_DP), dp(CONTROL_VERTICAL_GAP_DP));
        button.setLayoutParams(params);
        return button;
    }

    private Button button(String value, int fill, int textColor) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        button.setSingleLine(true);
        button.setMaxLines(1);
        button.setEllipsize(TextUtils.TruncateAt.END);
        button.setIncludeFontPadding(false);
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setPadding(dp(10), 0, dp(10), 0);
        button.setTextColor(textColor);
        button.setTextSize(14);
        button.setAutoSizeTextTypeUniformWithConfiguration(12, 14, 1, TypedValue.COMPLEX_UNIT_SP);
        button.setGravity(Gravity.CENTER);
        button.setBackground(buttonBackground(fill, dp(CONTROL_RADIUS_DP)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(CONTROL_HEIGHT_DP));
        params.setMargins(0, dp(CONTROL_VERTICAL_GAP_DP), 0, dp(CONTROL_VERTICAL_GAP_DP));
        button.setLayoutParams(params);
        return button;
    }

    private SeekBar seekBar(int max) {
        SeekBar seekBar = new SeekBar(this);
        SmoothSlider.configure(seekBar, max);
        seekBar.setSplitTrack(false);
        seekBar.setMinimumHeight(dp(40));
        seekBar.setThumb(sliderThumb());
        seekBar.setThumbOffset(dp(10));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            seekBar.setProgressTintList(statefulColor(withAlpha(text, 176), primary));
            seekBar.setProgressBackgroundTintList(statefulColor(withAlpha(muted, 92), sliderTrack));
        }
        return seekBar;
    }

    private StateListDrawable sliderThumb() {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{-android.R.attr.state_enabled}, sliderThumbDot(withAlpha(text, 220)));
        states.addState(new int[]{}, sliderThumbDot(primary));
        return states;
    }

    private GradientDrawable sliderThumbDot(int fill) {
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(fill);
        dot.setSize(dp(20), dp(20));
        return dot;
    }

    private LinearLayout.LayoutParams sliderParams(int topDp, int bottomDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(6), dp(topDp), dp(6), dp(bottomDp));
        return params;
    }

    private GradientDrawable cardBackground() {
        GradientDrawable bg = buttonBackground(surface, dp(CARD_RADIUS_DP));
        bg.setStroke(Math.max(1, dp(1)), withAlpha(muted, 44));
        return bg;
    }

    private GradientDrawable buttonBackground(int fill, int radius) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(fill);
        bg.setCornerRadius(radius);
        return bg;
    }

    private int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private ColorStateList statefulColor(int disabledColor, int enabledColor) {
        return new ColorStateList(
                new int[][]{
                        new int[]{-android.R.attr.state_enabled},
                        new int[]{}
                },
                new int[]{disabledColor, enabledColor});
    }

    private String shortMessage(String message) {
        if (message == null) {
            return "Unable to apply changes";
        }
        String clean = message.replace('\n', ' ').replace('\r', ' ').trim();
        if (clean.isEmpty()) {
            return "Unable to apply changes";
        }
        String lower = clean.toLowerCase(Locale.ROOT);
        if (lower.contains("permission")) {
            return "Bluetooth access required";
        }
        if (lower.contains("not connected")
                || lower.contains("could not connect")
                || lower.contains("timed out")
                || lower.contains("timeout")
                || lower.contains("socket")
                || lower.contains("read failed")) {
            return "Headset unavailable";
        }
        if (lower.contains("no paired") || lower.contains("no supported")) {
            return "No headset found";
        }
        return "Unable to apply changes";
    }

    private static String eqPresetName(int value) {
        switch (value) {
            case 0x00:
                return "Off";
            case 0x10:
                return "Bright";
            case 0x11:
                return "Excited";
            case 0x12:
                return "Mellow";
            case 0x13:
                return "Relaxed";
            case 0x14:
                return "Vocal";
            case 0x15:
                return "Treble";
            case 0x16:
                return "Bass";
            case 0x17:
                return "Speech";
            case EQ_MANUAL:
                return "Manual";
            case EQ_USER_1:
                return "User 1";
            case EQ_USER_2:
                return "User 2";
            default:
                return "0x" + Integer.toHexString(value).toUpperCase(Locale.ROOT);
        }
    }

    private static String eqSummary(int preset, int[] values) {
        if (preset == 0x00) {
            return "Off";
        }
        int[] safe = values == null || values.length < 6 ? SonyCommand.flatEqualizerValues() : values;
        return eqPresetName(preset) + " / Clear Bass " + eqValue(safe[0]);
    }

    private static String eqValue(int value) {
        int centered = HeadsetStatus.clampEq(value) - 10;
        return centered > 0 ? "+" + centered : String.valueOf(centered);
    }

    private static String quickAccessFunctionName(int function) {
        switch (function) {
            case 0:
                return "None";
            case 1:
                return "Spotify";
            case 2:
                return "Endel";
            case 3:
                return "Amazon Music";
            case 4:
                return "Xiaowei";
            case 5:
                return "Ximalaya";
            case 6:
                return "Audible";
            case 7:
                return "QQ Music";
            default:
                return "Unsupported 0x" + Integer.toHexString(function).toUpperCase(Locale.ROOT);
        }
    }

    private static String voiceGuidanceLanguageName(int language) {
        switch (language) {
            case 1:
                return "English";
            case 2:
                return "French";
            case 3:
                return "German";
            case 4:
                return "Spanish";
            case 5:
                return "Italian";
            case 6:
                return "Portuguese";
            case 7:
                return "Dutch";
            case 8:
                return "Swedish";
            case 9:
                return "Finnish";
            case 10:
                return "Russian";
            case 11:
                return "Japanese";
            case 13:
                return "Brazilian Portuguese";
            case 15:
                return "Korean";
            case 16:
                return "Turkish";
            case 240:
                return "Chinese";
            default:
                return "English";
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private final class MaxWidthLinearLayout extends LinearLayout {
        private final int maxWidthDp;

        MaxWidthLinearLayout(Context context, int maxWidthDp) {
            super(context);
            this.maxWidthDp = maxWidthDp;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int mode = View.MeasureSpec.getMode(widthMeasureSpec);
            int size = View.MeasureSpec.getSize(widthMeasureSpec);
            int maxWidth = dp(maxWidthDp);
            if (mode != View.MeasureSpec.UNSPECIFIED && size > maxWidth) {
                widthMeasureSpec = View.MeasureSpec.makeMeasureSpec(maxWidth, mode);
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
    }

    private final class AdaptiveColumnsLayout extends LinearLayout {
        private boolean expanded;
        private boolean configured;

        AdaptiveColumnsLayout(Context context) {
            super(context);
            setOrientation(VERTICAL);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = View.MeasureSpec.getSize(widthMeasureSpec);
            boolean nextExpanded = width >= dp(TWO_PANE_MIN_WIDTH_DP);
            if (!configured || nextExpanded != expanded) {
                expanded = nextExpanded;
                configured = true;
                configureChildren();
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }

        private void configureChildren() {
            setOrientation(expanded ? HORIZONTAL : VERTICAL);
            for (int i = 0; i < getChildCount(); i++) {
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        expanded ? 0 : ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        expanded ? 1f : 0f);
                if (expanded && i > 0) {
                    params.setMargins(dp(COLUMN_GAP_DP), 0, 0, 0);
                }
                getChildAt(i).setLayoutParams(params);
            }
        }
    }

    private final class MaxHeightScrollView extends ScrollView {
        private final int maxHeight;

        MaxHeightScrollView(Context context, int maxHeight) {
            super(context);
            this.maxHeight = maxHeight;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int cappedHeight = View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST);
            super.onMeasure(widthMeasureSpec, cappedHeight);
        }
    }
}
