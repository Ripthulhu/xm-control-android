package com.ripthulhu.xmcontrol.tiles.tile;

import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import com.ripthulhu.xmcontrol.tiles.bluetooth.SonyDeviceRepository;
import com.ripthulhu.xmcontrol.tiles.store.TilePreferences;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

abstract class BaseSonyTileService extends TileService {
    private static final ExecutorService COMMANDS = Executors.newSingleThreadExecutor();

    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    public void onStartListening() {
        super.onStartListening();
        if (syncOnStart() && isReady() && SonyDeviceRepository.hasConfiguredDevice(this)) {
            updateTile(true, "Updating");
            COMMANDS.execute(() -> {
                syncStateFromHeadset();
                main.post(() -> updateTile(false, null));
            });
            return;
        }
        updateTile(false, null);
    }

    protected abstract int tileState();

    protected abstract String tileLabel();

    protected abstract String tileSubtitle();

    protected boolean syncOnStart() {
        return false;
    }

    protected void syncStateFromHeadset() {
    }

    protected final void updateTile(boolean running, String overrideSubtitle) {
        Tile tile = getQsTile();
        if (tile == null) return;
        tile.setLabel(tileLabel());
        boolean permitted = isReady();
        boolean configured = SonyDeviceRepository.hasConfiguredDevice(this);
        boolean connected = configured && SonyDeviceRepository.isSelectedDeviceConnected(this);
        if (connected && !TilePreferences.headsetConnected(this)) {
            TilePreferences.markHeadsetConnected(this);
        } else if (!connected && TilePreferences.headsetConnected(this)) {
            TilePreferences.markHeadsetDisconnected(this);
        }
        int state;
        if (!permitted) {
            state = Tile.STATE_UNAVAILABLE;
        } else if (!configured || running || !connected) {
            state = Tile.STATE_INACTIVE;
        } else {
            state = tileState();
        }
        tile.setState(state);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            String subtitle = overrideSubtitle != null
                    ? overrideSubtitle
                    : !permitted ? "Bluetooth access required"
                    : !configured ? "Set up in XM Control"
                    : connected ? tileSubtitle() : "Not connected";
            tile.setSubtitle(subtitle);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                tile.setStateDescription(subtitle);
            }
            tile.setContentDescription(tileLabel());
        }
        tile.updateTile();
    }

    private boolean isReady() {
        return SonyDeviceRepository.hasConnectPermission(this);
    }
}
