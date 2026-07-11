package com.ripthulhu.xmcontrol.tiles.bluetooth;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import com.ripthulhu.xmcontrol.tiles.store.TilePreferences;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class SonyDeviceRepository {
    private static final String[] SUPPORTED_NAMES = {
            "WH-1000XM5",
            "WF-1000XM5",
            "WH-1000XM6",
            "WF-1000XM6"
    };

    private SonyDeviceRepository() {
    }

    public static boolean hasConnectPermission(Context context) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressLint("MissingPermission")
    public static List<BluetoothDevice> bondedSupportedDevices(Context context) {
        List<BluetoothDevice> result = new ArrayList<>();
        if (!hasConnectPermission(context)) return result;

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) return result;

        String selectedAddress = TilePreferences.selectedDeviceAddress(context);
        Set<BluetoothDevice> bonded = adapter.getBondedDevices();
        if (bonded == null) return result;

        for (BluetoothDevice device : bonded) {
            if (isSupportedDevice(device) || isSelectedClassicDevice(device, selectedAddress)) {
                result.add(device);
            }
        }
        return result;
    }

    @SuppressLint("MissingPermission")
    public static BluetoothDevice selectedOrFirstSupportedDevice(Context context) {
        if (!hasConnectPermission(context)) return null;

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) return null;

        String selectedAddress = TilePreferences.selectedDeviceAddress(context);
        Set<BluetoothDevice> bonded = adapter.getBondedDevices();
        if (bonded == null) return null;

        BluetoothDevice selected = null;
        BluetoothDevice connected = null;
        BluetoothDevice fallback = null;
        for (BluetoothDevice device : bonded) {
            if (isSelectedClassicDevice(device, selectedAddress)) {
                selected = device;
            }
            if (isSupportedDevice(device) && isConnectedDevice(device) && connected == null) {
                connected = device;
            }
            if (isSupportedDevice(device) && fallback == null) {
                fallback = device;
            }
        }

        if (selected != null) {
            return selected;
        }
        if (connected != null) {
            return connected;
        }
        return fallback;
    }

    @SuppressLint("MissingPermission")
    public static String connectedSupportedAddress(Context context, List<BluetoothDevice> devices) {
        if (!hasConnectPermission(context) || devices == null) return null;
        for (BluetoothDevice device : devices) {
            if ((isSupportedDevice(device) || isSelectedClassicDevice(device, TilePreferences.selectedDeviceAddress(context)))
                    && isConnectedDevice(device)) {
                return device.getAddress();
            }
        }
        return null;
    }

    public static boolean hasSupportedDevice(Context context) {
        return selectedOrFirstSupportedDevice(context) != null;
    }

    public static boolean hasConfiguredDevice(Context context) {
        return hasConnectPermission(context)
                && (TilePreferences.selectedDeviceAddress(context) != null || hasSupportedDevice(context));
    }

    @SuppressLint("MissingPermission")
    public static boolean isSelectedDeviceConnected(Context context) {
        if (!hasConnectPermission(context)) return false;

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) return false;

        BluetoothDevice target = null;
        String selectedAddress = TilePreferences.selectedDeviceAddress(context);
        Set<BluetoothDevice> bonded = adapter.getBondedDevices();
        if (bonded != null && selectedAddress != null) {
            for (BluetoothDevice device : bonded) {
                if (isSelectedClassicDevice(device, selectedAddress)) {
                    target = device;
                    break;
                }
            }
        }
        if (target == null) {
            target = selectedOrFirstSupportedDevice(context);
        }
        if (target == null) return false;

        Boolean liveState = connectedState(target);
        return liveState != null ? liveState : TilePreferences.headsetConnected(context);
    }

    @SuppressLint("MissingPermission")
    public static boolean isSelectedOrSupportedDevice(Context context, BluetoothDevice device) {
        if (device == null || device.getType() == BluetoothDevice.DEVICE_TYPE_LE || isLeAlias(device.getName())) {
            return false;
        }
        String selectedAddress = TilePreferences.selectedDeviceAddress(context);
        return isSelectedClassicDevice(device, selectedAddress) || isSupportedDevice(device);
    }

    @SuppressLint("MissingPermission")
    public static boolean isSupportedDevice(BluetoothDevice device) {
        String name = device.getName();
        return isSupportedName(name)
                && !isLeAlias(name)
                && device.getType() != BluetoothDevice.DEVICE_TYPE_LE;
    }

    private static boolean isConnectedDevice(BluetoothDevice device) {
        return Boolean.TRUE.equals(connectedState(device));
    }

    private static Boolean connectedState(BluetoothDevice device) {
        if (device == null) return false;
        try {
            Method method = BluetoothDevice.class.getMethod("isConnected");
            Object value = method.invoke(device);
            return value instanceof Boolean ? (Boolean) value : null;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return null;
        }
    }

    @SuppressLint("MissingPermission")
    private static boolean isSelectedClassicDevice(BluetoothDevice device, String selectedAddress) {
        return selectedAddress != null
                && device != null
                && selectedAddress.equals(device.getAddress())
                && !isLeAlias(device.getName())
                && device.getType() != BluetoothDevice.DEVICE_TYPE_LE;
    }

    private static boolean isSupportedName(String name) {
        if (name == null) return false;
        for (String supported : SUPPORTED_NAMES) {
            if (name.equalsIgnoreCase(supported)
                    || name.equalsIgnoreCase("LE_" + supported)
                    || name.replace("-", "").equalsIgnoreCase(supported.replace("-", ""))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLeAlias(String name) {
        return name != null && name.regionMatches(true, 0, "LE_", 0, 3);
    }

}
