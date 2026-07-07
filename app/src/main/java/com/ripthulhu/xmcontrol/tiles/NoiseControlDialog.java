package com.ripthulhu.xmcontrol.tiles;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.ripthulhu.xmcontrol.tiles.bluetooth.NoiseControlState;
import com.ripthulhu.xmcontrol.tiles.bluetooth.SonyDeviceRepository;
import com.ripthulhu.xmcontrol.tiles.bluetooth.SonyCommand;
import com.ripthulhu.xmcontrol.tiles.bluetooth.SonyMdrClient;
import com.ripthulhu.xmcontrol.tiles.store.TilePreferences;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

public final class NoiseControlDialog extends Dialog {
    private static final int CONTROL_HEIGHT_DP = 50;
    private static final int CONTROL_RADIUS_DP = 20;
    private static final int CONTROL_HORIZONTAL_GAP_DP = 5;
    private static final int CONTROL_VERTICAL_GAP_DP = 6;

    private final int surface;
    private final int surfaceVariant;
    private final int primary;
    private final int onPrimary;
    private final int text;
    private final int muted;
    private final int sliderTrack;

    private final ExecutorService commands = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable onStateChanged;
    private final Runnable dismissWhenUnfocused = new Runnable() {
        @Override
        public void run() {
            if (!closed && !windowHasFocus) {
                dismiss();
            }
        }
    };
    private final Runnable pendingEqSend = new Runnable() {
        @Override
        public void run() {
            queueEqualizerSend();
        }
    };
    private final Runnable operationWatchdog = new Runnable() {
        @Override
        public void run() {
            if (closed || !operationRunning) {
                return;
            }
            operationRunning = false;
            clearPendingWork();
            TilePreferences.markHeadsetDisconnected(getContext());
            updateUi("Not connected");
        }
    };

    private TextView statusText;
    private TextView levelText;
    private TextView levelValueText;
    private TextView clearBassText;
    private TextView clearBassValueText;
    private Button ancButton;
    private Button transparencyButton;
    private Button offButton;
    private Button normalButton;
    private Button voiceButton;
    private Button eqManualButton;
    private Button eqUser1Button;
    private Button eqUser2Button;
    private Button speakOnButton;
    private Button speakOffButton;
    private Button wearOnButton;
    private Button wearOffButton;
    private SeekBar transparencySlider;
    private SeekBar clearBassSlider;

    private boolean closed;
    private boolean windowHasFocus = true;
    private boolean operationRunning;
    private boolean refreshQueued;
    private long lastFailureToastAt;
    private int localChangeVersion;
    private NoiseControlState queuedState;
    private Boolean queuedSpeakToChat;
    private Boolean queuedWearPause;
    private Integer queuedEqPreset;
    private int[] queuedEqValues;

    public NoiseControlDialog(Context context, Runnable onStateChanged) {
        super(context, R.style.NoiseControlTileDialogTheme);
        ThemePalette palette = ThemePalette.from(context);
        surface = palette.surface;
        surfaceVariant = palette.surfaceVariant;
        primary = palette.primary;
        onPrimary = palette.onPrimary;
        text = palette.text;
        muted = palette.muted;
        sliderTrack = palette.sliderTrack;
        this.onStateChanged = onStateChanged;
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setCanceledOnTouchOutside(true);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        startKeepAlive();
        buildUi();
        syncFromHeadset();
    }

    @Override
    protected void onStart() {
        super.onStart();
        Window window = getWindow();
        if (window == null) return;

        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        WindowManager.LayoutParams attrs = window.getAttributes();
        attrs.width = Math.min(dp(520), getContext().getResources().getDisplayMetrics().widthPixels - dp(32));
        attrs.height = WindowManager.LayoutParams.WRAP_CONTENT;
        attrs.dimAmount = 0.34f;
        window.setAttributes(attrs);
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        windowHasFocus = hasFocus;
        main.removeCallbacks(dismissWhenUnfocused);
        if (!hasFocus && !closed) {
            main.postDelayed(dismissWhenUnfocused, 180L);
        }
    }

    @Override
    public void dismiss() {
        if (closed) {
            return;
        }
        closed = true;
        main.removeCallbacks(dismissWhenUnfocused);
        main.removeCallbacks(pendingEqSend);
        main.removeCallbacks(operationWatchdog);
        commands.shutdownNow();
        stopKeepAlive();
        super.dismiss();
    }

    private void startKeepAlive() {
        try {
            getContext().startService(new Intent(getContext(), PanelKeepAliveService.class));
        } catch (RuntimeException ignored) {
        }
    }

    private void stopKeepAlive() {
        try {
            getContext().stopService(new Intent(getContext(), PanelKeepAliveService.class));
        } catch (RuntimeException ignored) {
        }
    }

    private void buildUi() {
        MaxHeightScrollView scroller = new MaxHeightScrollView(
                getContext(),
                getContext().getResources().getDisplayMetrics().heightPixels - dp(88));
        scroller.setFillViewport(false);
        scroller.setClipToPadding(false);
        scroller.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        scroller.setBackgroundColor(Color.TRANSPARENT);

        LinearLayout root = new LinearLayout(getContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(26), dp(24), dp(26), dp(24));
        root.setBackground(panelBackground());

        TextView title = label("Noise control", 23, Typeface.NORMAL, text);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        statusText = label("", 15, Typeface.NORMAL, muted);
        statusText.setGravity(Gravity.CENTER);
        statusText.setPadding(0, dp(5), 0, dp(18));
        root.addView(statusText);

        LinearLayout modeRow = row();
        ancButton = pillButton("ANC");
        transparencyButton = pillButton("Transparency");
        offButton = pillButton("Off");
        modeRow.addView(ancButton);
        modeRow.addView(transparencyButton);
        modeRow.addView(offButton);
        root.addView(modeRow);

        ancButton.setOnClickListener(v -> send(NoiseControlState.Mode.ANC));
        transparencyButton.setOnClickListener(v -> send(NoiseControlState.Mode.AMBIENT));
        offButton.setOnClickListener(v -> send(NoiseControlState.Mode.OFF));

        levelText = label("Transparency level", 14, Typeface.BOLD, text);
        levelValueText = label("", 14, Typeface.BOLD, text);
        root.addView(labelValueRow(levelText, levelValueText, 18, 6));

        transparencySlider = seekBar(20);
        root.addView(transparencySlider, sliderParams(0, 14));

        transparencySlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                NoiseControlState current = TilePreferences.noiseControlState(getContext());
                TilePreferences.setNoiseControlState(getContext(),
                        new NoiseControlState(current.mode, progress, current.voiceAmbient));
                updateUi(null);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                NoiseControlState current = TilePreferences.noiseControlState(getContext());
                sendState(NoiseControlState.ambient(seekBar.getProgress(), current.voiceAmbient));
            }
        });

        LinearLayout kindRow = row();
        normalButton = pillButton("Normal");
        voiceButton = pillButton("Voice");
        kindRow.addView(normalButton);
        kindRow.addView(voiceButton);
        root.addView(kindRow);

        normalButton.setOnClickListener(v -> setTransparencyKind(false));
        voiceButton.setOnClickListener(v -> setTransparencyKind(true));

        root.addView(divider());
        root.addView(sectionLabel("Equalizer"));

        LinearLayout eqPresetRow = row();
        eqManualButton = pillButton("Manual");
        eqUser1Button = pillButton("User 1");
        eqUser2Button = pillButton("User 2");
        eqPresetRow.addView(eqManualButton);
        eqPresetRow.addView(eqUser1Button);
        eqPresetRow.addView(eqUser2Button);
        root.addView(eqPresetRow);

        eqManualButton.setOnClickListener(v -> selectEqPreset(TilePreferences.EQ_MANUAL));
        eqUser1Button.setOnClickListener(v -> selectEqPreset(TilePreferences.EQ_USER_1));
        eqUser2Button.setOnClickListener(v -> selectEqPreset(TilePreferences.EQ_USER_2));

        clearBassText = label("Clear Bass", 14, Typeface.BOLD, text);
        clearBassValueText = label("", 14, Typeface.BOLD, text);
        root.addView(labelValueRow(clearBassText, clearBassValueText, 10, 5));

        clearBassSlider = seekBar(20);
        root.addView(clearBassSlider, sliderParams(0, 14));

        clearBassSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                queuedEqPreset = null;
                TilePreferences.editEqValueAsManual(getContext(), TilePreferences.EQ_CLEAR_BASS_INDEX, progress);
                updateUi(null);
                scheduleEqualizerSend(420L);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                scheduleEqualizerSend(90L);
            }
        });

        root.addView(divider());
        root.addView(sectionLabel("Speak-to-Chat"));
        LinearLayout speakRow = row();
        speakOnButton = pillButton("On");
        speakOffButton = pillButton("Off");
        speakRow.addView(speakOnButton);
        speakRow.addView(speakOffButton);
        root.addView(speakRow);
        speakOnButton.setOnClickListener(v -> sendSpeakToChat(true));
        speakOffButton.setOnClickListener(v -> sendSpeakToChat(false));

        root.addView(sectionLabel("Pause when removed"));
        LinearLayout wearRow = row();
        wearOnButton = pillButton("On");
        wearOffButton = pillButton("Off");
        wearRow.addView(wearOnButton);
        wearRow.addView(wearOffButton);
        root.addView(wearRow);
        wearOnButton.setOnClickListener(v -> sendWearPause(true));
        wearOffButton.setOnClickListener(v -> sendWearPause(false));

        root.addView(spacer(8));
        LinearLayout bottomRow = row();
        Button closeButton = pillButton("Done");
        bottomRow.addView(closeButton);
        root.addView(bottomRow);
        closeButton.setOnClickListener(v -> dismiss());

        scroller.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroller);
        updateUi("Updating...");
    }

    private void setTransparencyKind(boolean voice) {
        NoiseControlState current = TilePreferences.noiseControlState(getContext());
        sendState(NoiseControlState.ambient(current.ambientLevel, voice));
    }

    private void sendSpeakToChat(boolean enabled) {
        if (closed) return;
        if (!canSendCommand()) return;
        queuedSpeakToChat = enabled;
        TilePreferences.setSpeakToChatEnabled(getContext(), enabled);
        updateUi(operationRunning ? "Queued" : "Applying...");
        if (!operationRunning) {
            drainQueue();
        }
    }

    private void sendWearPause(boolean enabled) {
        if (closed) return;
        if (!canSendCommand()) return;
        queuedWearPause = enabled;
        TilePreferences.setWearPauseEnabled(getContext(), enabled);
        updateUi(operationRunning ? "Queued" : "Applying...");
        if (!operationRunning) {
            drainQueue();
        }
    }

    private void selectEqPreset(int preset) {
        if (closed) return;
        if (!canSendCommand()) return;
        if (preset == TilePreferences.EQ_MANUAL) {
            int[] values = TilePreferences.manualEqValues(getContext());
            queuedEqPreset = null;
            queuedEqValues = values;
            TilePreferences.setEqState(getContext(), TilePreferences.EQ_MANUAL, values);
            updateUi("Applying...");
        } else {
            queuedEqValues = null;
            queuedEqPreset = preset;
            TilePreferences.setEqPreset(getContext(), preset);
            updateUi("Applying...");
        }
        if (!operationRunning) {
            drainQueue();
        }
    }

    private void send(NoiseControlState.Mode mode) {
        NoiseControlState current = TilePreferences.noiseControlState(getContext());
        if (mode == NoiseControlState.Mode.AMBIENT) {
            sendState(NoiseControlState.ambient(current.ambientLevel, current.voiceAmbient));
        } else if (mode == NoiseControlState.Mode.OFF) {
            sendState(NoiseControlState.off(current.ambientLevel, current.voiceAmbient));
        } else {
            sendState(NoiseControlState.anc(current.ambientLevel, current.voiceAmbient));
        }
    }

    private void sendState(NoiseControlState state) {
        if (closed) return;
        if (!canSendCommand()) return;
        localChangeVersion++;
        queuedState = state;
        TilePreferences.setNoiseControlState(getContext(), state);
        updateUi(operationRunning ? "Queued" : "Applying...");
        if (!operationRunning) {
            drainQueue();
        }
    }

    private void syncFromHeadset() {
        if (closed) return;
        if (!canTargetHeadset()) {
            clearPendingWork();
            updateUi(SonyDeviceRepository.hasConnectPermission(getContext())
                    ? "Set up in XM Control"
                    : "Bluetooth access required");
            return;
        }
        if (TilePreferences.headsetConnected(getContext()) && !TilePreferences.shouldRefreshNoiseState(getContext())) {
            updateUi(null);
            return;
        }
        if (operationRunning) {
            refreshQueued = true;
            updateUi("Refresh pending");
            return;
        }
        runSync(localChangeVersion);
    }

    private void drainQueue() {
        if (closed || operationRunning) return;
        NoiseControlState nextState = queuedState;
        if (nextState != null) {
            queuedState = null;
            runSend(nextState);
            return;
        }
        if (refreshQueued) {
            refreshQueued = false;
            runSync(localChangeVersion);
            return;
        }
        if (queuedSpeakToChat != null) {
            boolean enabled = queuedSpeakToChat;
            queuedSpeakToChat = null;
            runToggle("Speak-to-Chat", enabled, enabled ? SonyCommand.SPEAK_TO_CHAT_ON : SonyCommand.SPEAK_TO_CHAT_OFF,
                    () -> TilePreferences.setSpeakToChatEnabled(getContext(), enabled));
            return;
        }
        if (queuedWearPause != null) {
            boolean enabled = queuedWearPause;
            queuedWearPause = null;
            runToggle("Pause on Remove", enabled, enabled ? SonyCommand.WEAR_PAUSE_ON : SonyCommand.WEAR_PAUSE_OFF,
                    () -> TilePreferences.setWearPauseEnabled(getContext(), enabled));
            return;
        }
        if (queuedEqPreset != null) {
            int preset = queuedEqPreset;
            queuedEqPreset = null;
            runEqualizerPreset(preset);
            return;
        }
        if (queuedEqValues != null) {
            int[] values = queuedEqValues;
            queuedEqValues = null;
            runEqualizer(values);
            return;
        }
        updateUi(null);
    }

    private void runSend(NoiseControlState state) {
        beginOperation();
        TilePreferences.setNoiseControlState(getContext(), state);
        updateUi("Applying...");
        executeCommand(() -> {
            SonyMdrClient.Result result = SonyMdrClient.send(getContext(), SonyCommand.noiseControl(state));
            postUi(() -> {
                boolean newerStateQueued = queuedState != null;
                if (result.success && !newerStateQueued) {
                    TilePreferences.markHeadsetConnected(getContext());
                    TilePreferences.setNoiseControlState(getContext(), state);
                    TilePreferences.markNoiseStateRefreshed(getContext());
                    notifyStateChanged();
                } else if (!result.success) {
                    TilePreferences.markNoiseStateRefreshed(getContext());
                    clearPendingWork();
                    showFailureToast(result.message);
                }

                endOperation();
                if (result.success && hasPendingWork()) {
                    drainQueue();
                    return;
                }
                updateUi(result.success ? null : "Headset unavailable");
            });
        });
    }

    private void runEqualizer(int[] values) {
        beginOperation();
        TilePreferences.setEqState(getContext(), TilePreferences.EQ_MANUAL, values);
        updateUi("Applying...");
        executeCommand(() -> {
            SonyMdrClient.Result result = SonyMdrClient.send(getContext(), SonyCommand.equalizer(TilePreferences.EQ_MANUAL, values));
            postUi(() -> {
                if (result.success) {
                    TilePreferences.markHeadsetConnected(getContext());
                    TilePreferences.setEqState(getContext(), TilePreferences.EQ_MANUAL, values);
                    notifyStateChanged();
                } else {
                    TilePreferences.markNoiseStateRefreshed(getContext());
                    clearPendingWork();
                    showFailureToast(result.message);
                }

                endOperation();
                if (result.success && hasPendingWork()) {
                    drainQueue();
                    return;
                }
                updateUi(result.success ? null : "Headset unavailable");
            });
        });
    }

    private void runEqualizerPreset(int preset) {
        beginOperation();
        TilePreferences.setEqPreset(getContext(), preset);
        updateUi("Applying...");
        executeCommand(() -> {
            SonyMdrClient.Result result = SonyMdrClient.send(getContext(), SonyCommand.equalizerPreset(preset));
            SonyMdrClient.StatusResult eqResult = result.success
                    ? SonyMdrClient.readEqualizerState(getContext())
                    : null;
            postUi(() -> {
                String nextStatus = null;
                if (result.success) {
                    TilePreferences.markHeadsetConnected(getContext());
                    if (eqResult != null
                            && eqResult.success
                            && eqResult.status != null
                            && eqResult.status.eqValues != null
                            && eqResult.status.eqValues.length >= TilePreferences.EQ_VALUE_COUNT) {
                        int returnedPreset = eqResult.status.eqPreset >= 0 ? eqResult.status.eqPreset : preset;
                        TilePreferences.setEqState(getContext(), returnedPreset, eqResult.status.eqValues);
                    } else {
                        TilePreferences.setEqPreset(getContext(), preset);
                        nextStatus = "Preset selected";
                    }
                    notifyStateChanged();
                } else {
                    TilePreferences.markNoiseStateRefreshed(getContext());
                    clearPendingWork();
                    showFailureToast(result.message);
                }

                endOperation();
                if (result.success && hasPendingWork()) {
                    drainQueue();
                    return;
                }
                updateUi(result.success ? nextStatus : "Headset unavailable");
            });
        });
    }

    private void runToggle(String label, boolean enabled, SonyCommand command, Runnable onSuccess) {
        beginOperation();
        updateUi("Applying...");
        executeCommand(() -> {
            SonyMdrClient.Result result = SonyMdrClient.send(getContext(), command);
            postUi(() -> {
                if (result.success) {
                    TilePreferences.markHeadsetConnected(getContext());
                    onSuccess.run();
                    notifyStateChanged();
                } else {
                    TilePreferences.markNoiseStateRefreshed(getContext());
                    clearPendingWork();
                    showFailureToast(result.message);
                }

                endOperation();
                if (result.success && hasPendingWork()) {
                    drainQueue();
                    return;
                }
                updateUi(result.success ? null : "Headset unavailable");
            });
        });
    }

    private void runSync(int requestedAtVersion) {
        beginOperation();
        updateUi("Updating...");
        NoiseControlState fallback = TilePreferences.noiseControlState(getContext());
        executeCommand(() -> {
            SonyMdrClient.NoiseControlResult result = SonyMdrClient.readNoiseControlState(getContext(), fallback);
            postUi(() -> {
                if (result.success && requestedAtVersion == localChangeVersion && queuedState == null) {
                    TilePreferences.markHeadsetConnected(getContext());
                    TilePreferences.setNoiseControlState(getContext(), result.state);
                    TilePreferences.markNoiseStateRefreshed(getContext());
                    notifyStateChanged();
                } else if (!result.success) {
                    TilePreferences.markNoiseStateRefreshed(getContext());
                    refreshQueued = false;
                }

                endOperation();
                if (hasPendingWork()) {
                    drainQueue();
                    return;
                }
                updateUi(result.success ? null : "Headset unavailable");
            });
        });
    }

    private void beginOperation() {
        operationRunning = true;
        main.removeCallbacks(operationWatchdog);
        main.postDelayed(operationWatchdog, 9500L);
    }

    private void endOperation() {
        operationRunning = false;
        main.removeCallbacks(operationWatchdog);
    }

    private void executeCommand(Runnable work) {
        try {
            commands.execute(() -> {
                try {
                    work.run();
                } catch (Throwable ignored) {
                    postUi(() -> {
                        endOperation();
                        clearPendingWork();
                        TilePreferences.markHeadsetDisconnected(getContext());
                        updateUi("Not connected");
                    });
                }
            });
        } catch (RejectedExecutionException ignored) {
            if (!closed) {
                endOperation();
                clearPendingWork();
                updateUi("Not connected");
            }
        }
    }

    private boolean hasPendingWork() {
        return queuedState != null
                || refreshQueued
                || queuedSpeakToChat != null
                || queuedWearPause != null
                || queuedEqPreset != null
                || queuedEqValues != null;
    }

    private void postUi(Runnable runnable) {
        main.post(() -> {
            if (!closed) {
                runnable.run();
            }
        });
    }

    private void notifyStateChanged() {
        if (onStateChanged != null) {
            onStateChanged.run();
        }
    }

    private void showFailureToast(String message) {
        long now = System.currentTimeMillis();
        if (now - lastFailureToastAt < 3500L) {
            return;
        }
        lastFailureToastAt = now;
        Toast.makeText(getContext(), shortMessage(message), Toast.LENGTH_SHORT).show();
    }

    private void updateUi(String overrideStatus) {
        NoiseControlState state = TilePreferences.noiseControlState(getContext());
        String status = overrideStatus == null
                ? defaultStatus(state)
                : overrideStatus;
        statusText.setText(status);
        levelValueText.setText(String.valueOf(state.ambientLevel));
        if (transparencySlider.getProgress() != state.ambientLevel) {
            transparencySlider.setProgress(state.ambientLevel);
        }
        setSelected(ancButton, state.mode == NoiseControlState.Mode.ANC);
        setSelected(transparencyButton, state.mode == NoiseControlState.Mode.AMBIENT);
        setSelected(offButton, state.mode == NoiseControlState.Mode.OFF);
        setSelected(normalButton, !state.voiceAmbient);
        setSelected(voiceButton, state.voiceAmbient);
        int preset = TilePreferences.eqPreset(getContext());
        setSelected(eqManualButton, preset == TilePreferences.EQ_MANUAL);
        setSelected(eqUser1Button, preset == TilePreferences.EQ_USER_1);
        setSelected(eqUser2Button, preset == TilePreferences.EQ_USER_2);
        int[] eqValues = TilePreferences.eqValues(getContext());
        int clearBass = eqValues[TilePreferences.EQ_CLEAR_BASS_INDEX];
        clearBassValueText.setText(eqValue(clearBass));
        if (clearBassSlider.getProgress() != clearBass) {
            clearBassSlider.setProgress(clearBass);
        }
        setOnOff(speakOnButton, speakOffButton, TilePreferences.speakToChatEnabled(getContext()));
        setOnOff(wearOnButton, wearOffButton, TilePreferences.wearPauseEnabled(getContext()));
        setControlsEnabled(canTargetHeadset() && SonyDeviceRepository.hasConnectedAudioProfile(getContext()));
    }

    private void scheduleEqualizerSend(long delayMs) {
        if (closed || !canTargetHeadset()) return;
        main.removeCallbacks(pendingEqSend);
        main.postDelayed(pendingEqSend, delayMs);
    }

    private void queueEqualizerSend() {
        if (closed || !canTargetHeadset()) {
            clearPendingWork();
            updateUi("Set up in XM Control");
            return;
        }
        queuedEqPreset = null;
        queuedEqValues = TilePreferences.eqValues(getContext());
        updateUi(operationRunning ? "Queued" : "Applying...");
        if (!operationRunning) {
            drainQueue();
        }
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        return row;
    }

    private LinearLayout labelValueRow(TextView label, TextView value, int topDp, int bottomDp) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(2), 0, dp(2), 0);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.setMargins(0, dp(topDp), 0, dp(bottomDp));
        row.setLayoutParams(rowParams);

        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f);
        row.addView(label, labelParams);
        row.addView(value, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private SeekBar seekBar(int max) {
        SeekBar seekBar = new SeekBar(getContext());
        seekBar.setMax(max);
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

    private View spacer(int heightDp) {
        View view = new View(getContext());
        view.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(heightDp)));
        return view;
    }

    private boolean canSendCommand() {
        if (!canTargetHeadset()) {
            clearPendingWork();
            updateUi(SonyDeviceRepository.hasConnectPermission(getContext())
                    ? "Set up in XM Control"
                    : "Bluetooth access required");
            return false;
        }
        if (!SonyDeviceRepository.hasConnectedAudioProfile(getContext())) {
            TilePreferences.markHeadsetDisconnected(getContext());
            clearPendingWork();
            updateUi("Not connected");
            return false;
        }
        return true;
    }

    private boolean canTargetHeadset() {
        return SonyDeviceRepository.hasConnectPermission(getContext())
                && SonyDeviceRepository.hasConfiguredDevice(getContext());
    }

    private String defaultStatus(NoiseControlState state) {
        if (!SonyDeviceRepository.hasConnectPermission(getContext())) {
            return "Bluetooth access required";
        }
        if (!SonyDeviceRepository.hasConfiguredDevice(getContext())) {
            return "Set up in XM Control";
        }
        if (!SonyDeviceRepository.hasConnectedAudioProfile(getContext())) {
            return "Not connected";
        }
        return TilePreferences.headsetConnected(getContext())
                ? state.summary()
                : "Ready";
    }

    private void clearPendingWork() {
        main.removeCallbacks(pendingEqSend);
        queuedState = null;
        refreshQueued = false;
        queuedSpeakToChat = null;
        queuedWearPause = null;
        queuedEqPreset = null;
        queuedEqValues = null;
    }

    private Button pillButton(String value) {
        Button button = new Button(getContext());
        button.setText(value);
        button.setAllCaps(false);
        button.setSingleLine(true);
        button.setMaxLines(1);
        button.setEllipsize(TextUtils.TruncateAt.END);
        button.setIncludeFontPadding(false);
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setPadding(dp(10), 0, dp(10), 0);
        button.setTextColor(text);
        button.setTextSize(14);
        button.setAutoSizeTextTypeUniformWithConfiguration(12, 14, 1, TypedValue.COMPLEX_UNIT_SP);
        button.setGravity(Gravity.CENTER);
        button.setBackground(buttonBackground(surfaceVariant));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(CONTROL_HEIGHT_DP), 1f);
        params.setMargins(dp(CONTROL_HORIZONTAL_GAP_DP), dp(CONTROL_VERTICAL_GAP_DP),
                dp(CONTROL_HORIZONTAL_GAP_DP), dp(CONTROL_VERTICAL_GAP_DP));
        button.setLayoutParams(params);
        return button;
    }

    private void setSelected(Button button, boolean selected) {
        if (button == null) return;
        button.setTextColor(selected ? onPrimary : text);
        button.setBackground(buttonBackground(selected ? primary : surfaceVariant));
    }

    private void setOnOff(Button onButton, Button offButton, boolean enabled) {
        setSelected(onButton, enabled);
        setSelected(offButton, !enabled);
    }

    private TextView sectionLabel(String value) {
        TextView view = label(value, 14, Typeface.BOLD, text);
        view.setPadding(dp(2), dp(13), 0, dp(4));
        return view;
    }

    private TextView label(String value, int sp, int style, int color) {
        TextView view = new TextView(getContext());
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setTypeface(Typeface.DEFAULT, style);
        view.setIncludeFontPadding(false);
        return view;
    }

    private GradientDrawable panelBackground() {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(surface);
        bg.setCornerRadius(dp(28));
        bg.setStroke(Math.max(1, dp(1)), withAlpha(muted, 44));
        return bg;
    }

    private GradientDrawable buttonBackground(int fill) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(fill);
        bg.setCornerRadius(dp(CONTROL_RADIUS_DP));
        return bg;
    }

    private View divider() {
        View line = new View(getContext());
        line.setBackgroundColor(withAlpha(muted, 42));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Math.max(1, dp(1)));
        params.setMargins(0, dp(16), 0, dp(3));
        line.setLayoutParams(params);
        return line;
    }

    private void setControlsEnabled(boolean enabled) {
        setEnabled(ancButton, enabled);
        setEnabled(transparencyButton, enabled);
        setEnabled(offButton, enabled);
        setEnabled(normalButton, enabled);
        setEnabled(voiceButton, enabled);
        setEnabled(eqManualButton, enabled);
        setEnabled(eqUser1Button, enabled);
        setEnabled(eqUser2Button, enabled);
        setEnabled(speakOnButton, enabled);
        setEnabled(speakOffButton, enabled);
        setEnabled(wearOnButton, enabled);
        setEnabled(wearOffButton, enabled);
        if (transparencySlider != null) {
            transparencySlider.setEnabled(enabled);
            transparencySlider.setAlpha(1f);
        }
        if (clearBassSlider != null) {
            clearBassSlider.setEnabled(enabled);
            clearBassSlider.setAlpha(1f);
        }
    }

    private void setEnabled(Button button, boolean enabled) {
        if (button == null) return;
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : 0.68f);
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

    private static String eqValue(int value) {
        int centered = Math.max(0, Math.min(20, value)) - 10;
        return centered > 0 ? "+" + centered : String.valueOf(centered);
    }

    private static String eqPresetName(int preset) {
        if (preset == TilePreferences.EQ_USER_1) return "User 1";
        if (preset == TilePreferences.EQ_USER_2) return "User 2";
        return "Manual";
    }

    private int dp(int value) {
        return Math.round(value * getContext().getResources().getDisplayMetrics().density);
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
