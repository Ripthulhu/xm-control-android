package com.ripthulhu.xmcontrol.tiles;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothLeAudio;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.TileService;

import com.ripthulhu.xmcontrol.tiles.bluetooth.SonyDeviceRepository;
import com.ripthulhu.xmcontrol.tiles.store.TilePreferences;
import com.ripthulhu.xmcontrol.tiles.tile.AncTileService;

public final class SystemEventReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }

        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            TilePreferences.setActiveDeviceName(context, null);
            TilePreferences.markHeadsetDisconnected(context);
            requestTileRefresh(context);
            return;
        }

        if (!SonyDeviceRepository.hasConnectPermission(context) || !SonyDeviceRepository.hasConfiguredDevice(context)) {
            return;
        }

        handleBluetoothEvent(context, intent, action);
    }

    @SuppressLint("MissingPermission")
    private void handleBluetoothEvent(Context context, Intent intent, String action) {
        BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        if (device != null && !SonyDeviceRepository.isSelectedOrSupportedDevice(context, device)) {
            return;
        }
        int state = connectionState(intent, action);
        if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action) || state == BluetoothProfile.STATE_CONNECTED) {
            if (device != null) {
                SonyDeviceRepository.noteConnectedIdentity(context, device);
            } else {
                TilePreferences.markHeadsetConnected(context);
            }
            requestTileRefresh(context);
            return;
        }

        if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action) || state == BluetoothProfile.STATE_DISCONNECTED) {
            BroadcastReceiver.PendingResult pendingResult = goAsync();
            Thread verifier = new Thread(() -> {
                try {
                    Thread.sleep(1200L);
                    if (SonyDeviceRepository.isSelectedDeviceConnected(context)) {
                        TilePreferences.markHeadsetConnected(context);
                    } else {
                        SonyDeviceRepository.clearActiveDeviceIfDisconnected(context);
                        TilePreferences.markHeadsetDisconnected(context);
                    }
                    requestTileRefresh(context);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } finally {
                    pendingResult.finish();
                }
            }, "xm-connection-verifier");
            verifier.start();
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

    private void requestTileRefresh(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            TileService.requestListeningState(context, new ComponentName(context, AncTileService.class));
        }
    }
}
