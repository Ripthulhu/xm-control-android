package com.ripthulhu.xmcontrol.tiles.tile;

import android.service.quicksettings.Tile;

import com.ripthulhu.xmcontrol.tiles.NoiseControlDialog;
import com.ripthulhu.xmcontrol.tiles.bluetooth.NoiseControlState;
import com.ripthulhu.xmcontrol.tiles.bluetooth.SonyDeviceRepository;
import com.ripthulhu.xmcontrol.tiles.store.TilePreferences;

import java.lang.ref.WeakReference;

public final class AncTileService extends BaseSonyTileService {
    private static WeakReference<NoiseControlDialog> activeDialog = new WeakReference<>(null);

    @Override
    public void onClick() {
        if (!SonyDeviceRepository.hasConnectPermission(this)) {
            updateTile(false, "Bluetooth access required");
            return;
        }
        if (!SonyDeviceRepository.hasConfiguredDevice(this)) {
            updateTile(false, "Set up in XM Control");
            return;
        }

        Runnable showNoiseDialog = () -> {
            NoiseControlDialog existing = activeDialog.get();
            if (existing != null && existing.isShowing()) {
                existing.dismiss();
                return;
            }
            NoiseControlDialog dialog = new NoiseControlDialog(this, () -> updateTile(false, null));
            activeDialog = new WeakReference<>(dialog);
            dialog.setOnDismissListener(dismissed -> {
                if (activeDialog.get() == dismissed) {
                    activeDialog = new WeakReference<>(null);
                }
                updateTile(false, null);
            });
            showDialog(dialog);
        };
        if (isLocked()) {
            unlockAndRun(showNoiseDialog);
            return;
        }
        showNoiseDialog.run();
    }

    @Override
    public void onDestroy() {
        dismissActiveDialog();
        super.onDestroy();
    }

    private void dismissActiveDialog() {
        NoiseControlDialog dialog = activeDialog.get();
        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss();
        }
        activeDialog = new WeakReference<>(null);
    }

    @Override
    protected int tileState() {
        NoiseControlState.Mode mode = TilePreferences.noiseControlState(this).mode;
        return mode == NoiseControlState.Mode.OFF || mode == NoiseControlState.Mode.UNKNOWN
                ? Tile.STATE_INACTIVE
                : Tile.STATE_ACTIVE;
    }

    @Override
    protected String tileLabel() {
        return "XM Control";
    }

    @Override
    protected String tileSubtitle() {
        return TilePreferences.noiseControlState(this).summary();
    }
}
