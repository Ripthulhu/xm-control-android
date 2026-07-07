package com.ripthulhu.xmcontrol.tiles.bluetooth;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class SonyMdrClient {
    private static final String TAG = "SonyMdrClient";
    private static final UUID TABLE_SET_2 = UUID.fromString("956c7b26-d49a-4ba8-b03f-b17d393cb6e2");
    private static final UUID TABLE_SET_1 = UUID.fromString("96cc203e-5068-46ad-b32d-e316f5e069ba");
    private static final UUID[] SERVICE_UUIDS = {TABLE_SET_2, TABLE_SET_1};
    private static final int DATA_MDR = 0x0C;
    private static final int DATA_MDR_NO2 = 0x0E;
    private static final int ACK = 0x01;
    private static final int COMMAND_SEQUENCE = 0;
    private static final byte[] NOISE_CONTROL_STATUS = new byte[]{0x66, 0x17};
    private static final byte[] EQUALIZER_STATUS = new byte[]{0x56, 0x00};
    private static final byte[] VOICE_GUIDANCE_PARAM_MTK_NO_LANGUAGE = new byte[]{0x46, 0x00};
    private static final byte[] VOICE_GUIDANCE_PARAM_MTK = new byte[]{0x46, 0x01};
    private static final byte[] VOICE_GUIDANCE_PARAM_DIRECT = new byte[]{0x46, 0x02};
    private static final byte[] VOICE_GUIDANCE_PARAM_ON_OFF = new byte[]{0x46, 0x03};
    private static final byte[] VOICE_GUIDANCE_STATUS_MTK_NO_LANGUAGE_ON_OFF = new byte[]{0x42, 0x00, 0x00};
    private static final byte[] VOICE_GUIDANCE_STATUS_MTK_ON_OFF = new byte[]{0x42, 0x01, 0x00};
    private static final byte[] VOICE_GUIDANCE_STATUS_MTK_LANGUAGE = new byte[]{0x42, 0x01, 0x01};
    private static final byte[][] FULL_STATUS_COMMANDS = new byte[][]{
            new byte[]{0x22, 0x00},
            new byte[]{0x66, 0x17},
            new byte[]{(byte) 0xD6, (byte) 0xD1},
            new byte[]{(byte) 0xD6, (byte) 0xD2},
            new byte[]{0x52, 0x00},
            new byte[]{0x56, 0x00},
            new byte[]{0x5A, 0x00},
            new byte[]{(byte) 0xE6, 0x01},
            new byte[]{(byte) 0xE6, 0x00},
            new byte[]{(byte) 0xF6, 0x02},
            new byte[]{(byte) 0xF6, 0x01},
            new byte[]{(byte) 0xF6, 0x0D},
            VOICE_GUIDANCE_STATUS_MTK_NO_LANGUAGE_ON_OFF,
            VOICE_GUIDANCE_STATUS_MTK_ON_OFF,
            VOICE_GUIDANCE_STATUS_MTK_LANGUAGE,
            VOICE_GUIDANCE_PARAM_MTK_NO_LANGUAGE,
            VOICE_GUIDANCE_PARAM_MTK,
            VOICE_GUIDANCE_PARAM_DIRECT,
            VOICE_GUIDANCE_PARAM_ON_OFF,
            new byte[]{0x26, 0x05}
    };
    private static final int READ_ATTEMPTS_PER_SERVICE = 1;

    private SonyMdrClient() {
    }

    public static synchronized Result send(Context context, SonyCommand command) {
        if (!SonyDeviceRepository.hasConnectPermission(context)) {
            return Result.error("Bluetooth permission is not granted.");
        }
        if (!SonyDeviceRepository.hasConnectedAudioProfile(context)) {
            return Result.error("Headset is not connected.");
        }

        BluetoothDevice device = SonyDeviceRepository.selectedOrFirstSupportedDevice(context);
        if (device == null) {
            return Result.error("No paired supported Sony XM device found.");
        }

        Exception lastError = null;
        for (UUID serviceUuid : SERVICE_UUIDS) {
            BluetoothSocket socket = null;
            try {
                socket = connect(context, device, serviceUuid);
                ExchangeResult exchange = exchangePayload(socket, command.payload, -2);
                return exchange.success ? Result.ok() : Result.error(exchange.message);
            } catch (Exception ex) {
                Log.w(TAG, "Command failed through " + serviceUuid, ex);
                lastError = ex;
            } finally {
                closeQuietly(socket);
            }
        }

        return Result.error(lastError == null ? "Could not connect to headset." : lastError.getMessage());
    }

    public static synchronized NoiseControlResult readNoiseControlState(Context context, NoiseControlState fallback) {
        if (!SonyDeviceRepository.hasConnectPermission(context)) {
            return NoiseControlResult.error("Bluetooth permission is not granted.");
        }
        if (!SonyDeviceRepository.hasConnectedAudioProfile(context)) {
            return NoiseControlResult.error("Headset is not connected.");
        }

        BluetoothDevice device = SonyDeviceRepository.selectedOrFirstSupportedDevice(context);
        if (device == null) {
            return NoiseControlResult.error("No paired supported Sony XM device found.");
        }

        Exception lastError = null;
        String lastExchangeError = null;
        for (int attempt = 0; attempt < READ_ATTEMPTS_PER_SERVICE; attempt++) {
            for (UUID serviceUuid : SERVICE_UUIDS) {
                BluetoothSocket socket = null;
                try {
                    socket = connect(context, device, serviceUuid);
                    ExchangeResult exchange = exchangePayload(socket, NOISE_CONTROL_STATUS, 0x67, 2200L);
                    if (exchange.success) {
                        return NoiseControlResult.ok(NoiseControlState.fromPayload(exchange.payload, fallback));
                    }
                    lastExchangeError = exchange.message;
                } catch (Exception ex) {
                    Log.w(TAG, "Noise control read failed through " + serviceUuid, ex);
                    lastError = ex;
                } finally {
                    closeQuietly(socket);
                }
            }

            if (attempt + 1 < READ_ATTEMPTS_PER_SERVICE) {
                try {
                    Thread.sleep(120L);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return NoiseControlResult.error("Status read interrupted.");
                }
            }
        }

        String message = lastExchangeError != null
                ? lastExchangeError
                : lastError == null ? "Could not connect to headset." : lastError.getMessage();
        return NoiseControlResult.error(message);
    }

    public static synchronized StatusResult readStatus(Context context, NoiseControlState fallback) {
        if (!SonyDeviceRepository.hasConnectPermission(context)) {
            return StatusResult.error("Bluetooth permission is not granted.");
        }
        if (!SonyDeviceRepository.hasConnectedAudioProfile(context)) {
            return StatusResult.error("Headset is not connected.");
        }

        BluetoothDevice device = SonyDeviceRepository.selectedOrFirstSupportedDevice(context);
        if (device == null) {
            return StatusResult.error("No paired supported Sony XM device found.");
        }

        Exception lastError = null;
        for (UUID serviceUuid : SERVICE_UUIDS) {
            BluetoothSocket socket = null;
            try {
                socket = connect(context, device, serviceUuid);
                List<byte[]> payloads = exchangeBatch(socket, FULL_STATUS_COMMANDS, 1300L);
                if (!payloads.isEmpty()) {
                    return StatusResult.ok(HeadsetStatus.fromPayloads(payloads, fallback));
                }
            } catch (Exception ex) {
                Log.w(TAG, "Status read failed through " + serviceUuid, ex);
                lastError = ex;
            } finally {
                closeQuietly(socket);
            }
        }

        return StatusResult.error(lastError == null ? "Could not read headset status." : lastError.getMessage());
    }

    public static synchronized StatusResult sendVoiceGuidance(Context context, SonyCommand command, NoiseControlState fallback) {
        if (!SonyDeviceRepository.hasConnectPermission(context)) {
            return StatusResult.error("Bluetooth permission is not granted.");
        }
        if (!SonyDeviceRepository.hasConnectedAudioProfile(context)) {
            return StatusResult.error("Headset is not connected.");
        }

        BluetoothDevice device = SonyDeviceRepository.selectedOrFirstSupportedDevice(context);
        if (device == null) {
            return StatusResult.error("No paired supported Sony XM device found.");
        }

        Exception lastError = null;
        String lastExchangeError = null;
        for (UUID serviceUuid : SERVICE_UUIDS) {
            BluetoothSocket socket = null;
            try {
                socket = connect(context, device, serviceUuid);
                int type = voiceGuidanceType(command.payload);
                boolean enabled = voiceGuidanceEnabled(command.payload);
                int language = voiceGuidanceLanguage(command.payload);
                boolean tableSet1 = TABLE_SET_1.equals(serviceUuid);
                byte[] writePayload = tableSet1
                        ? voiceGuidanceV1Payload(enabled)
                        : command.payload;
                byte[] readPayload = tableSet1
                        ? new byte[]{0x46, 0x01, 0x01}
                        : new byte[]{0x46, (byte) type};
                int dataType = tableSet1 ? DATA_MDR_NO2 : dataTypeForPayload(writePayload);
                ExchangeResult write = exchangePayload(socket, dataType, writePayload, -2, 1800L, 0);
                if (write.success) {
                    Thread.sleep(450L);
                    ExchangeResult read = exchangePayload(socket, dataType, readPayload, 0x47, 1800L, 1);
                    if (read.success && read.payload != null) {
                        if (!voiceGuidancePayloadMatches(read.payload, enabled, tableSet1)) {
                            lastExchangeError = "Headset kept the previous voice guide setting.";
                            continue;
                        }
                        if (tableSet1) {
                            return StatusResult.ok(HeadsetStatus.voiceGuidanceSnapshot(enabled, type, language));
                        }
                        List<byte[]> payloads = new ArrayList<>();
                        payloads.add(read.payload);
                        return StatusResult.ok(HeadsetStatus.fromPayloads(payloads, fallback));
                    }
                    return StatusResult.ok(HeadsetStatus.voiceGuidanceSnapshot(enabled, type, language));
                }
                lastExchangeError = write.message;
            } catch (Exception ex) {
                Log.w(TAG, "Voice guidance command failed through " + serviceUuid, ex);
                lastError = ex;
            } finally {
                closeQuietly(socket);
            }
        }

        return StatusResult.error(lastExchangeError != null
                ? lastExchangeError
                : lastError == null ? "Could not update voice guide." : lastError.getMessage());
    }

    private static int voiceGuidanceType(byte[] payload) {
        if (payload == null || payload.length < 2 || (payload[0] & 0xFF) != 0x48) {
            return 1;
        }
        int type = payload[1] & 0xFF;
        return type == 0 || type == 1 || type == 2 || type == 3 ? type : 0;
    }

    private static boolean voiceGuidanceEnabled(byte[] payload) {
        return payload != null && payload.length >= 3 && (payload[2] & 0xFF) == 0x00;
    }

    private static int voiceGuidanceLanguage(byte[] payload) {
        return payload != null && payload.length >= 4 ? payload[3] & 0xFF : 1;
    }

    private static boolean voiceGuidancePayloadMatches(byte[] payload, boolean enabled, boolean tableSet1) {
        if (tableSet1
                && payload != null
                && payload.length >= 4
                && (payload[0] & 0xFF) == 0x47
                && (payload[1] & 0xFF) == 0x01
                && (payload[2] & 0xFF) == 0x01) {
            return (payload[3] & 0xFF) == (enabled ? 0x01 : 0x00);
        }
        return payload != null
                && payload.length >= 3
                && (payload[0] & 0xFF) == 0x47
                && ((payload[2] & 0xFF) == (enabled ? 0x00 : 0x01));
    }

    private static byte[] voiceGuidanceV1Payload(boolean enabled) {
        return new byte[]{0x48, 0x01, 0x01, (byte) (enabled ? 0x01 : 0x00)};
    }

    public static synchronized StatusResult readEqualizerState(Context context) {
        if (!SonyDeviceRepository.hasConnectPermission(context)) {
            return StatusResult.error("Bluetooth permission is not granted.");
        }
        if (!SonyDeviceRepository.hasConnectedAudioProfile(context)) {
            return StatusResult.error("Headset is not connected.");
        }

        BluetoothDevice device = SonyDeviceRepository.selectedOrFirstSupportedDevice(context);
        if (device == null) {
            return StatusResult.error("No paired supported Sony XM device found.");
        }

        Exception lastError = null;
        String lastExchangeError = null;
        for (UUID serviceUuid : SERVICE_UUIDS) {
            BluetoothSocket socket = null;
            try {
                socket = connect(context, device, serviceUuid);
                ExchangeResult exchange = exchangePayload(socket, EQUALIZER_STATUS, expectedForPayload(EQUALIZER_STATUS), 1400L);
                if (exchange.success) {
                    List<byte[]> payloads = new ArrayList<>();
                    if (exchange.payload != null) {
                        payloads.add(exchange.payload);
                    }
                    return StatusResult.ok(HeadsetStatus.fromPayloads(payloads, NoiseControlState.defaultState()));
                }
                lastExchangeError = exchange.message;
            } catch (Exception ex) {
                Log.w(TAG, "Equalizer read failed through " + serviceUuid, ex);
                lastError = ex;
            } finally {
                closeQuietly(socket);
            }
        }

        return StatusResult.error(lastExchangeError != null
                ? lastExchangeError
                : lastError == null ? "Could not read equalizer status." : lastError.getMessage());
    }

    @SuppressLint("MissingPermission")
    private static BluetoothSocket connect(Context context, BluetoothDevice device, UUID serviceUuid) throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Missing BLUETOOTH_CONNECT permission.");
        }

        BluetoothSocket socket = device.createRfcommSocketToServiceRecord(serviceUuid);
        ExecutorService connector = Executors.newSingleThreadExecutor();
        Future<Void> future = connector.submit(new Callable<Void>() {
            @Override
            public Void call() throws IOException {
                socket.connect();
                return null;
            }
        });

        try {
            future.get(5200, TimeUnit.MILLISECONDS);
            return socket;
        } catch (TimeoutException ex) {
            closeQuietly(socket);
            throw new IOException("Connection timed out.");
        } catch (ExecutionException ex) {
            closeQuietly(socket);
            Throwable cause = ex.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw new IOException("Connection failed.");
        } finally {
            connector.shutdownNow();
        }
    }

    private static ExchangeResult exchangePayload(BluetoothSocket socket, byte[] payload, int expectedCommand) throws IOException, InterruptedException {
        return exchangePayload(socket, payload, expectedCommand, 1800L);
    }

    private static ExchangeResult exchangePayload(BluetoothSocket socket, byte[] payload, int expectedCommand, long timeoutMs) throws IOException, InterruptedException {
        return exchangePayload(socket, payload, expectedCommand, timeoutMs, COMMAND_SEQUENCE);
    }

    private static ExchangeResult exchangePayload(BluetoothSocket socket, byte[] payload, int expectedCommand, long timeoutMs, int txSequence) throws IOException, InterruptedException {
        return exchangePayload(socket, dataTypeForPayload(payload), payload, expectedCommand, timeoutMs, txSequence);
    }

    private static ExchangeResult exchangePayload(
            BluetoothSocket socket,
            int dataType,
            byte[] payload,
            int expectedCommand,
            long timeoutMs,
            int txSequence) throws IOException, InterruptedException {
        OutputStream output = socket.getOutputStream();
        InputStream input = socket.getInputStream();
        output.write(SonyMdrCodec.buildFrame(dataType, txSequence, payload));
        output.flush();

        SonyMdrCodec.Parser parser = new SonyMdrCodec.Parser();
        byte[] buffer = new byte[1024];
        long deadline = System.currentTimeMillis() + timeoutMs;
        boolean acked = false;
        while (System.currentTimeMillis() < deadline) {
            int available = input.available();
            if (available <= 0) {
                Thread.sleep(25L);
                continue;
            }

            int read = input.read(buffer, 0, Math.min(buffer.length, available));
            if (read <= 0) break;
            for (SonyMdrCodec.Frame frame : parser.add(buffer, read)) {
                if (!frame.valid) continue;
                if (frame.dataType == ACK) {
                    if (frame.sequence == 1 - txSequence) {
                        acked = true;
                        if (expectedCommand == -2) {
                            return ExchangeResult.ok(null);
                        }
                    }
                    continue;
                }
                if (SonyMdrCodec.ackRequired(frame.dataType)) {
                    output.write(SonyMdrCodec.buildFrame(ACK, 1 - frame.sequence, null));
                    output.flush();
                }
                if (expectedCommand >= 0 && isExpectedPayload(frame.payload, expectedCommand)) {
                    return ExchangeResult.ok(frame.payload);
                }
            }
        }
        if (!acked) {
            return ExchangeResult.error("Headset did not acknowledge the command.");
        }
        return expectedCommand == -2
                ? ExchangeResult.ok(null)
                : ExchangeResult.error("Headset did not return status.");
    }

    private static int dataTypeForPayload(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return DATA_MDR;
        }
        int command = payload[0] & 0xFF;
        return command >= 0x40 && command <= 0x49 ? DATA_MDR_NO2 : DATA_MDR;
    }

    private static List<byte[]> exchangeBatch(BluetoothSocket socket, byte[][] commands, long timeoutMs) throws IOException, InterruptedException {
        List<byte[]> payloads = new ArrayList<>();
        if (commands == null) {
            return payloads;
        }

        for (int i = 0; i < commands.length; i++) {
            byte[] payload = commands[i];
            ExchangeResult exchange = exchangePayload(socket, payload, expectedForPayload(payload), timeoutMs, i & 1);
            if (exchange.payload != null) {
                payloads.add(exchange.payload);
            }
            Thread.sleep(35L);
        }
        return payloads;
    }

    private static int expectedForPayload(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return -1;
        }
        int command = payload[0] & 0xFF;
        if (command == 0x22) {
            return 0x23;
        }
        return (command + 1) & 0xFF;
    }

    private static boolean isExpectedPayload(byte[] payload, int expectedCommand) {
        if (payload == null || payload.length == 0) {
            return false;
        }
        int command = payload[0] & 0xFF;
        if (expectedCommand == 0x23) {
            return command == 0x11 || command == 0x13 || command == 0x23 || command == 0x25;
        }
        if (expectedCommand == 0x57) {
            return command == 0x57 || command == 0x59;
        }
        return command == expectedCommand || (expectedCommand == 0x67 && command == 0x69);
    }

    private static void closeQuietly(BluetoothSocket socket) {
        if (socket == null) return;
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    public static final class Result {
        public final boolean success;
        public final String message;

        private Result(boolean success, String message) {
            this.success = success;
            this.message = message;
        }

        public static Result ok() {
            return new Result(true, "");
        }

        public static Result error(String message) {
            return new Result(false, message == null ? "Command failed." : message);
        }
    }

    private static final class ExchangeResult {
        final boolean success;
        final String message;
        final byte[] payload;

        private ExchangeResult(boolean success, String message, byte[] payload) {
            this.success = success;
            this.message = message;
            this.payload = payload;
        }

        static ExchangeResult ok(byte[] payload) {
            return new ExchangeResult(true, "", payload);
        }

        static ExchangeResult error(String message) {
            return new ExchangeResult(false, message == null ? "Command failed." : message, null);
        }
    }

    public static final class NoiseControlResult {
        public final boolean success;
        public final String message;
        public final NoiseControlState state;

        private NoiseControlResult(boolean success, String message, NoiseControlState state) {
            this.success = success;
            this.message = message;
            this.state = state;
        }

        public static NoiseControlResult ok(NoiseControlState state) {
            return new NoiseControlResult(true, "", state);
        }

        public static NoiseControlResult error(String message) {
            return new NoiseControlResult(false, message == null ? "Status read failed." : message, null);
        }
    }

    public static final class StatusResult {
        public final boolean success;
        public final String message;
        public final HeadsetStatus status;

        private StatusResult(boolean success, String message, HeadsetStatus status) {
            this.success = success;
            this.message = message;
            this.status = status;
        }

        public static StatusResult ok(HeadsetStatus status) {
            return new StatusResult(true, "", status);
        }

        public static StatusResult error(String message) {
            return new StatusResult(false, message == null ? "Status read failed." : message, null);
        }
    }
}
