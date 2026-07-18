package com.ripthulhu.xmcontrol.tiles.bluetooth;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.ParcelUuid;

import com.ripthulhu.xmcontrol.tiles.store.TilePreferences;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class SonyDeviceRepository {
    private static final UUID TABLE_SET_2 = UUID.fromString("956c7b26-d49a-4ba8-b03f-b17d393cb6e2");
    private static final UUID TABLE_SET_1 = UUID.fromString("96cc203e-5068-46ad-b32d-e316f5e069ba");
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

    /** Returns one control-capable row for each physical headset model. */
    @SuppressLint("MissingPermission")
    public static List<BluetoothDevice> bondedSupportedDevices(Context context) {
        List<BluetoothDevice> result = new ArrayList<>();
        if (!hasConnectPermission(context)) return result;

        String selectedAddress = TilePreferences.selectedDeviceAddress(context);
        for (DeviceGroup group : bondedGroups(context)) {
            List<BluetoothDevice> endpoints = rankedControlEndpoints(group, selectedAddress);
            if (!endpoints.isEmpty()) {
                result.add(endpoints.get(0));
            }
        }
        return result;
    }

    @SuppressLint("MissingPermission")
    public static BluetoothDevice selectedOrFirstSupportedDevice(Context context) {
        List<BluetoothDevice> candidates = controlCandidates(context);
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    /**
     * Returns possible RFCOMM endpoints for the active logical headset. Devices
     * advertising Sony's control UUIDs always win; legacy fallback is retained
     * for Android versions that have not cached SDP UUIDs yet.
     */
    @SuppressLint("MissingPermission")
    public static List<BluetoothDevice> controlCandidates(Context context) {
        List<BluetoothDevice> result = new ArrayList<>();
        if (!hasConnectPermission(context)) return result;

        List<DeviceGroup> groups = bondedGroups(context);
        DeviceGroup target = targetGroup(context, groups);
        if (target == null) return result;
        return rankedControlEndpoints(target, TilePreferences.selectedDeviceAddress(context));
    }

    /** Returns true while Android's LE Audio profile is carrying the selected headset. */
    @SuppressLint("MissingPermission")
    public static boolean prefersGattTransport(Context context) {
        if (!hasConnectPermission(context) || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return false;
        }

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) return false;
        DeviceGroup target = targetGroup(context, bondedGroups(context));
        if (target == null) return false;

        try {
            return adapter.getProfileConnectionState(BluetoothProfile.LE_AUDIO)
                    == BluetoothProfile.STATE_CONNECTED;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /** Keeps GATT available as a fallback when Android has bonded an LE companion identity. */
    @SuppressLint("MissingPermission")
    public static boolean hasGattCompanion(Context context) {
        if (!hasConnectPermission(context)) return false;
        DeviceGroup target = targetGroup(context, bondedGroups(context));
        if (target == null) return false;

        boolean hasControlIdentity = false;
        boolean hasCompanionIdentity = false;
        for (BluetoothDevice identity : target.identities) {
            if (hasControlService(identity)) {
                hasControlIdentity = true;
            } else {
                hasCompanionIdentity = true;
            }
        }
        return hasControlIdentity && hasCompanionIdentity;
    }

    @SuppressLint("MissingPermission")
    public static String connectedSupportedAddress(Context context, List<BluetoothDevice> ignored) {
        if (!hasConnectPermission(context)) return null;

        List<DeviceGroup> groups = bondedGroups(context);
        DeviceGroup target = targetGroup(context, groups);
        if (target == null) return null;

        boolean connected = isGroupLiveConnected(target);
        if (!connected) {
            connected = target.name.equals(TilePreferences.activeDeviceName(context))
                    && TilePreferences.headsetConnected(context);
        }
        if (!connected) return null;

        List<BluetoothDevice> endpoints = rankedControlEndpoints(
                target, TilePreferences.selectedDeviceAddress(context));
        return endpoints.isEmpty() ? null : endpoints.get(0).getAddress();
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

        DeviceGroup target = targetGroup(context, bondedGroups(context));
        if (target == null) return false;

        boolean observedState = false;
        for (BluetoothDevice identity : target.identities) {
            Boolean liveState = connectedState(identity);
            if (Boolean.TRUE.equals(liveState)) return true;
            observedState |= liveState != null;
        }
        if (observedState) return false;

        return target.name.equals(TilePreferences.activeDeviceName(context))
                && TilePreferences.headsetConnected(context);
    }

    @SuppressLint("MissingPermission")
    public static void noteConnectedIdentity(Context context, BluetoothDevice device) {
        String model = canonicalName(device == null ? null : device.getName());
        if (model == null) return;

        TilePreferences.setActiveDeviceName(context, model);
        DeviceGroup group = groupForName(bondedGroups(context), model);
        if (group != null) {
            List<BluetoothDevice> endpoints = rankedControlEndpoints(
                    group, TilePreferences.selectedDeviceAddress(context));
            if (!endpoints.isEmpty()) {
                TilePreferences.setSelectedDeviceAddress(context, endpoints.get(0).getAddress());
            }
        }
        TilePreferences.markHeadsetConnected(context);
    }

    @SuppressLint("MissingPermission")
    public static void rememberControlDevice(Context context, BluetoothDevice device) {
        if (device == null) return;
        String model = canonicalName(device.getName());
        if (model == null) return;

        TilePreferences.setActiveDeviceName(context, model);
        TilePreferences.setSelectedDeviceAddress(context, device.getAddress());
        TilePreferences.markHeadsetConnected(context);
    }

    @SuppressLint("MissingPermission")
    public static void clearActiveDeviceIfDisconnected(Context context) {
        DeviceGroup target = targetGroup(context, bondedGroups(context));
        if (target != null && !isGroupLiveConnected(target)) {
            TilePreferences.setActiveDeviceName(context, null);
        }
    }

    @SuppressLint("MissingPermission")
    public static boolean isSelectedOrSupportedDevice(Context context, BluetoothDevice device) {
        return hasConnectPermission(context) && isSupportedIdentity(device);
    }

    @SuppressLint("MissingPermission")
    public static boolean isSupportedDevice(BluetoothDevice device) {
        if (!isSupportedIdentity(device)) return false;
        String name = device.getName();
        return !isLeAlias(name) && device.getType() != BluetoothDevice.DEVICE_TYPE_LE;
    }

    @SuppressLint("MissingPermission")
    private static List<DeviceGroup> bondedGroups(Context context) {
        Map<String, DeviceGroup> grouped = new LinkedHashMap<>();
        for (String supported : SUPPORTED_NAMES) {
            grouped.put(supported, new DeviceGroup(supported));
        }

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        Set<BluetoothDevice> bonded = adapter == null ? null : adapter.getBondedDevices();
        if (bonded != null) {
            for (BluetoothDevice device : bonded) {
                String model = canonicalName(device.getName());
                DeviceGroup group = model == null ? null : grouped.get(model);
                if (group != null) {
                    group.identities.add(device);
                }
            }
        }

        List<DeviceGroup> result = new ArrayList<>();
        for (DeviceGroup group : grouped.values()) {
            if (!group.identities.isEmpty()) {
                result.add(group);
            }
        }
        return result;
    }

    private static DeviceGroup targetGroup(Context context, List<DeviceGroup> groups) {
        if (groups == null || groups.isEmpty()) return null;

        String selectedModel = modelForAddress(groups, TilePreferences.selectedDeviceAddress(context));
        String activeModel = TilePreferences.activeDeviceName(context);
        List<DeviceGroup> connected = new ArrayList<>();
        for (DeviceGroup group : groups) {
            if (isGroupLiveConnected(group)) {
                connected.add(group);
            }
        }

        if (connected.size() == 1) return connected.get(0);
        if (connected.size() > 1) {
            DeviceGroup active = groupForName(connected, activeModel);
            if (active != null) return active;
            DeviceGroup selected = groupForName(connected, selectedModel);
            return selected != null ? selected : connected.get(0);
        }

        DeviceGroup active = groupForName(groups, activeModel);
        if (active != null && TilePreferences.headsetConnected(context)) return active;
        DeviceGroup selected = groupForName(groups, selectedModel);
        return selected != null ? selected : groups.get(0);
    }

    private static List<BluetoothDevice> rankedControlEndpoints(DeviceGroup group, String selectedAddress) {
        List<BluetoothDevice> eligible = new ArrayList<>();
        boolean hasAdvertisedControlEndpoint = false;
        for (BluetoothDevice device : group.identities) {
            hasAdvertisedControlEndpoint |= hasControlService(device);
        }

        for (BluetoothDevice device : group.identities) {
            if (hasAdvertisedControlEndpoint) {
                if (hasControlService(device)) eligible.add(device);
            } else if (isSupportedDevice(device)) {
                eligible.add(device);
            }
        }

        eligible.sort(Comparator
                .comparingInt((BluetoothDevice device) -> endpointScore(device, selectedAddress))
                .reversed()
                .thenComparing(BluetoothDevice::getAddress));
        return eligible;
    }

    @SuppressLint("MissingPermission")
    private static int endpointScore(BluetoothDevice device, String selectedAddress) {
        int score = hasControlService(device) ? 10_000 : 0;
        if (selectedAddress != null && selectedAddress.equals(device.getAddress())) score += 1_000;
        if (isConnectedDevice(device)) score += 100;
        if (device.getType() == BluetoothDevice.DEVICE_TYPE_CLASSIC) score += 20;
        if (device.getType() == BluetoothDevice.DEVICE_TYPE_DUAL) score += 10;
        return score;
    }

    @SuppressLint("MissingPermission")
    private static boolean hasControlService(BluetoothDevice device) {
        ParcelUuid[] uuids = device == null ? null : device.getUuids();
        if (uuids == null) return false;
        for (ParcelUuid uuid : uuids) {
            UUID value = uuid == null ? null : uuid.getUuid();
            if (TABLE_SET_2.equals(value) || TABLE_SET_1.equals(value)) return true;
        }
        return false;
    }

    private static boolean isGroupLiveConnected(DeviceGroup group) {
        for (BluetoothDevice identity : group.identities) {
            if (isConnectedDevice(identity)) return true;
        }
        return false;
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
    private static boolean isSupportedIdentity(BluetoothDevice device) {
        return device != null && canonicalName(device.getName()) != null;
    }

    private static String modelForAddress(List<DeviceGroup> groups, String address) {
        if (address == null) return null;
        for (DeviceGroup group : groups) {
            for (BluetoothDevice device : group.identities) {
                if (address.equals(device.getAddress())) return group.name;
            }
        }
        return null;
    }

    private static DeviceGroup groupForName(List<DeviceGroup> groups, String name) {
        if (name == null) return null;
        for (DeviceGroup group : groups) {
            if (name.equals(group.name)) return group;
        }
        return null;
    }

    private static String canonicalName(String name) {
        if (name == null) return null;
        String candidate = isLeAlias(name) ? name.substring(3) : name;
        String compactCandidate = candidate.replace("-", "");
        for (String supported : SUPPORTED_NAMES) {
            if (candidate.equalsIgnoreCase(supported)
                    || compactCandidate.equalsIgnoreCase(supported.replace("-", ""))) {
                return supported;
            }
        }
        return null;
    }

    private static boolean isLeAlias(String name) {
        return name != null && name.regionMatches(true, 0, "LE_", 0, 3);
    }

    private static final class DeviceGroup {
        final String name;
        final List<BluetoothDevice> identities = new ArrayList<>();

        DeviceGroup(String name) {
            this.name = name;
        }
    }
}
