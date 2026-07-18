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
    private static final int DATA_MDR = 0x0C;
    private static final int DATA_MDR_NO2 = 0x0E;
    private static final int ACK = 0x01;
    private static final int ACK_ONLY = -2;
    private static final int COMMAND_SEQUENCE = 0;
    private static final long WRITE_ACK_TIMEOUT_MS = 1300L;
    private static final long WRITE_ACK_RETRY_MS = 650L;
    private static final int WRITE_ACK_RETRIES = 1;
    private static final long WRITE_READBACK_DELAY_MS = 180L;
    private static final long WRITE_READBACK_TIMEOUT_MS = 1800L;
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

        List<BluetoothDevice> devices = SonyDeviceRepository.controlCandidates(context);
        if (devices.isEmpty()) {
            return Result.error("No paired supported Sony XM device found.");
        }

        Exception lastError = null;
        String lastExchangeError = null;
        for (BluetoothDevice device : devices) {
            for (TransportTarget target : transportTargets(context)) {
                SonyMdrTransport transport = null;
                try {
                    transport = connect(context, device, target);
                    ExchangeResult exchange = exchangePayload(
                            transport,
                            command.payload,
                            expectedForPayload(command.payload),
                            writeStatusTimeoutForPayload(command.payload),
                            COMMAND_SEQUENCE,
                            WRITE_ACK_TIMEOUT_MS,
                            WRITE_ACK_RETRIES);
                    if (exchange.success && payloadConfirmsCommand(command, exchange.payload)) {
                        SonyDeviceRepository.rememberControlDevice(context, device);
                        return Result.ok();
                    }

                    if (exchange.success) {
                        lastExchangeError = "Headset returned the previous setting.";
                    } else {
                        lastExchangeError = exchange.message;
                    }

                    if (exchange.success || exchange.acknowledged) {
                        ExchangeResult readback = readBackAfterWrite(transport, command);
                        if (readback.success && payloadConfirmsCommand(command, readback.payload)) {
                            SonyDeviceRepository.rememberControlDevice(context, device);
                            return Result.ok();
                        }
                        lastExchangeError = readback.success
                                ? "Headset kept the previous setting."
                                : readback.message;
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return Result.error("Operation cancelled.");
                } catch (Exception ex) {
                    Log.w(TAG, "Command failed for " + device.getAddress()
                            + " through " + target.description, ex);
                    lastError = ex;
                } finally {
                    closeQuietly(transport);
                }
            }
        }

        return Result.error(lastExchangeError != null
                ? lastExchangeError
                : lastError == null ? "Could not connect to headset." : lastError.getMessage());
    }

    public static synchronized NoiseControlResult readNoiseControlState(Context context, NoiseControlState fallback) {
        if (!SonyDeviceRepository.hasConnectPermission(context)) {
            return NoiseControlResult.error("Bluetooth permission is not granted.");
        }

        List<BluetoothDevice> devices = SonyDeviceRepository.controlCandidates(context);
        if (devices.isEmpty()) {
            return NoiseControlResult.error("No paired supported Sony XM device found.");
        }

        Exception lastError = null;
        String lastExchangeError = null;
        for (BluetoothDevice device : devices) {
            for (int attempt = 0; attempt < READ_ATTEMPTS_PER_SERVICE; attempt++) {
                for (TransportTarget target : transportTargets(context)) {
                    SonyMdrTransport transport = null;
                    try {
                        transport = connect(context, device, target);
                        ExchangeResult exchange = exchangePayload(
                                transport, NOISE_CONTROL_STATUS, 0x67, 2200L);
                        if (exchange.success) {
                            SonyDeviceRepository.rememberControlDevice(context, device);
                            return NoiseControlResult.ok(NoiseControlState.fromPayload(exchange.payload, fallback));
                        }
                        lastExchangeError = exchange.message;
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        return NoiseControlResult.error("Status read cancelled.");
                    } catch (Exception ex) {
                        Log.w(TAG, "Noise control read failed for " + device.getAddress()
                                + " through " + target.description, ex);
                        lastError = ex;
                    } finally {
                        closeQuietly(transport);
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

        List<BluetoothDevice> devices = SonyDeviceRepository.controlCandidates(context);
        if (devices.isEmpty()) {
            return StatusResult.error("No paired supported Sony XM device found.");
        }

        Exception lastError = null;
        for (BluetoothDevice device : devices) {
            List<byte[]> collectedPayloads = new ArrayList<>();
            for (TransportTarget target : transportTargets(context)) {
                SonyMdrTransport transport = null;
                try {
                    transport = connect(context, device, target);
                    BatchResult batch = exchangeBatch(transport, FULL_STATUS_COMMANDS, 1300L);
                    if (!batch.payloads.isEmpty()) {
                        collectedPayloads.addAll(batch.payloads);
                        if (batch.complete) {
                            SonyDeviceRepository.rememberControlDevice(context, device);
                            return StatusResult.ok(
                                    HeadsetStatus.fromPayloads(collectedPayloads, fallback),
                                    true);
                        }
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return StatusResult.error("Status read cancelled.");
                } catch (Exception ex) {
                    Log.w(TAG, "Status read failed for " + device.getAddress()
                            + " through " + target.description, ex);
                    lastError = ex;
                } finally {
                    closeQuietly(transport);
                }
            }

            if (!collectedPayloads.isEmpty()) {
                SonyDeviceRepository.rememberControlDevice(context, device);
                return StatusResult.ok(HeadsetStatus.fromPayloads(collectedPayloads, fallback), false);
            }
        }

        return StatusResult.error(lastError == null ? "Could not read headset status." : lastError.getMessage());
    }

    public static synchronized StatusResult sendVoiceGuidance(Context context, SonyCommand command, NoiseControlState fallback) {
        if (!SonyDeviceRepository.hasConnectPermission(context)) {
            return StatusResult.error("Bluetooth permission is not granted.");
        }

        List<BluetoothDevice> devices = SonyDeviceRepository.controlCandidates(context);
        if (devices.isEmpty()) {
            return StatusResult.error("No paired supported Sony XM device found.");
        }

        Exception lastError = null;
        String lastExchangeError = null;
        for (BluetoothDevice device : devices) {
            for (TransportTarget target : transportTargets(context)) {
                SonyMdrTransport transport = null;
                try {
                    transport = connect(context, device, target);
                    int type = voiceGuidanceType(command.payload);
                    boolean enabled = voiceGuidanceEnabled(command.payload);
                    int language = voiceGuidanceLanguage(command.payload);
                    boolean tableSet1 = transport.usesTableSet1();
                    byte[] writePayload = tableSet1
                            ? voiceGuidanceV1Payload(enabled)
                            : command.payload;
                    byte[] readPayload = tableSet1
                            ? new byte[]{0x46, 0x01, 0x01}
                            : new byte[]{0x46, (byte) type};
                    int dataType = tableSet1 ? DATA_MDR_NO2 : dataTypeForPayload(writePayload);
                    ExchangeResult write = exchangePayload(
                            transport, dataType, writePayload, ACK_ONLY, 1800L, 0);
                    if (write.success) {
                        Thread.sleep(450L);
                        ExchangeResult read = exchangePayload(
                                transport, dataType, readPayload, 0x47, 1800L, 1);
                        if (read.success && read.payload != null) {
                            if (!voiceGuidancePayloadMatches(read.payload, enabled, tableSet1)) {
                                lastExchangeError = "Headset kept the previous voice guide setting.";
                                continue;
                            }
                            SonyDeviceRepository.rememberControlDevice(context, device);
                            if (tableSet1) {
                                return StatusResult.ok(HeadsetStatus.voiceGuidanceSnapshot(enabled, type, language));
                            }
                            List<byte[]> payloads = new ArrayList<>();
                            payloads.add(read.payload);
                            return StatusResult.ok(HeadsetStatus.fromPayloads(payloads, fallback));
                        }
                        lastExchangeError = read.message;
                        continue;
                    }
                    lastExchangeError = write.message;
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return StatusResult.error("Voice guide update cancelled.");
                } catch (Exception ex) {
                    Log.w(TAG, "Voice guidance command failed for " + device.getAddress()
                            + " through " + target.description, ex);
                    lastError = ex;
                } finally {
                    closeQuietly(transport);
                }
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

        List<BluetoothDevice> devices = SonyDeviceRepository.controlCandidates(context);
        if (devices.isEmpty()) {
            return StatusResult.error("No paired supported Sony XM device found.");
        }

        Exception lastError = null;
        String lastExchangeError = null;
        for (BluetoothDevice device : devices) {
            for (TransportTarget target : transportTargets(context)) {
                SonyMdrTransport transport = null;
                try {
                    transport = connect(context, device, target);
                    ExchangeResult exchange = exchangePayload(
                            transport, EQUALIZER_STATUS, expectedForPayload(EQUALIZER_STATUS), 1400L);
                    if (exchange.success) {
                        List<byte[]> payloads = new ArrayList<>();
                        if (exchange.payload != null) {
                            payloads.add(exchange.payload);
                        }
                        SonyDeviceRepository.rememberControlDevice(context, device);
                        return StatusResult.ok(
                                HeadsetStatus.fromPayloads(payloads, NoiseControlState.defaultState()));
                    }
                    lastExchangeError = exchange.message;
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return StatusResult.error("Equalizer read cancelled.");
                } catch (Exception ex) {
                    Log.w(TAG, "Equalizer read failed for " + device.getAddress()
                            + " through " + target.description, ex);
                    lastError = ex;
                } finally {
                    closeQuietly(transport);
                }
            }
        }

        return StatusResult.error(lastExchangeError != null
                ? lastExchangeError
                : lastError == null ? "Could not read equalizer status." : lastError.getMessage());
    }

    private static List<TransportTarget> transportTargets(Context context) {
        boolean preferGatt = SonyDeviceRepository.prefersGattTransport(context);
        boolean allowGatt = preferGatt || SonyDeviceRepository.hasGattCompanion(context);
        List<TransportTarget> targets = new ArrayList<>();
        if (preferGatt) targets.add(TransportTarget.gatt());
        targets.add(TransportTarget.rfcomm(TABLE_SET_2));
        targets.add(TransportTarget.rfcomm(TABLE_SET_1));
        if (!preferGatt && allowGatt) targets.add(TransportTarget.gatt());
        return targets;
    }

    private static SonyMdrTransport connect(
            Context context,
            BluetoothDevice device,
            TransportTarget target) throws Exception {
        if (target.gatt) return SonyGattTransport.connect(context, device);
        return new RfcommTransport(connectRfcomm(context, device, target.serviceUuid), target.serviceUuid);
    }

    @SuppressLint("MissingPermission")
    private static BluetoothSocket connectRfcomm(
            Context context,
            BluetoothDevice device,
            UUID serviceUuid) throws Exception {
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
        } catch (InterruptedException ex) {
            future.cancel(true);
            closeSocketQuietly(socket);
            Thread.currentThread().interrupt();
            throw ex;
        } catch (TimeoutException ex) {
            closeSocketQuietly(socket);
            throw new IOException("Connection timed out.");
        } catch (ExecutionException ex) {
            closeSocketQuietly(socket);
            Throwable cause = ex.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw new IOException("Connection failed.");
        } finally {
            connector.shutdownNow();
        }
    }

    private static ExchangeResult exchangePayload(SonyMdrTransport transport, byte[] payload, int expectedCommand) throws IOException, InterruptedException {
        return exchangePayload(transport, payload, expectedCommand, 1800L);
    }

    private static ExchangeResult exchangePayload(SonyMdrTransport transport, byte[] payload, int expectedCommand, long timeoutMs) throws IOException, InterruptedException {
        return exchangePayload(transport, payload, expectedCommand, timeoutMs, COMMAND_SEQUENCE);
    }

    private static ExchangeResult exchangePayload(SonyMdrTransport transport, byte[] payload, int expectedCommand, long timeoutMs, int txSequence) throws IOException, InterruptedException {
        return exchangePayload(transport, dataTypeForPayload(payload), payload, expectedCommand, timeoutMs, txSequence);
    }

    private static ExchangeResult exchangePayload(
            SonyMdrTransport transport,
            byte[] payload,
            int expectedCommand,
            long timeoutMs,
            int txSequence,
            long ackTimeoutMs,
            int maxAckRetries) throws IOException, InterruptedException {
        return exchangePayload(transport, dataTypeForPayload(payload), payload, expectedCommand, timeoutMs, txSequence, ackTimeoutMs, maxAckRetries);
    }

    private static ExchangeResult exchangePayload(
            SonyMdrTransport transport,
            int dataType,
            byte[] payload,
            int expectedCommand,
            long timeoutMs,
            int txSequence) throws IOException, InterruptedException {
        return exchangePayload(transport, dataType, payload, expectedCommand, timeoutMs, txSequence, timeoutMs, 0);
    }

    private static ExchangeResult exchangePayload(
            SonyMdrTransport transport,
            int dataType,
            byte[] payload,
            int expectedCommand,
            long timeoutMs,
            int txSequence,
            long ackTimeoutMs,
            int maxAckRetries) throws IOException, InterruptedException {
        OutputStream output = transport.output();
        InputStream input = transport.input();
        byte[] frameBytes = SonyMdrCodec.buildFrame(dataType, txSequence, payload);
        output.write(frameBytes);
        output.flush();

        SonyMdrCodec.Parser parser = new SonyMdrCodec.Parser();
        byte[] buffer = new byte[1024];
        long start = System.currentTimeMillis();
        long statusDeadline = start + timeoutMs;
        long ackDeadline = start + Math.min(timeoutMs, ackTimeoutMs);
        long nextAckRetryAt = start + WRITE_ACK_RETRY_MS;
        int ackRetries = 0;
        boolean acked = !SonyMdrCodec.ackRequired(dataType);
        while (true) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Bluetooth exchange cancelled.");
            }
            long now = System.currentTimeMillis();
            if (acked) {
                if (now >= statusDeadline) {
                    break;
                }
            } else if (now >= ackDeadline) {
                break;
            }

            if (!acked && ackRetries < maxAckRetries && now >= nextAckRetryAt) {
                ackRetries++;
                output.write(frameBytes);
                output.flush();
                nextAckRetryAt = now + WRITE_ACK_RETRY_MS;
            }

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
                        if (expectedCommand == ACK_ONLY) {
                            return ExchangeResult.ok(null, true);
                        }
                    }
                    continue;
                }
                if (SonyMdrCodec.ackRequired(frame.dataType)) {
                    output.write(SonyMdrCodec.buildFrame(ACK, 1 - frame.sequence, null));
                    output.flush();
                }
                if (expectedCommand >= 0 && isExpectedPayload(frame.payload, expectedCommand)) {
                    return ExchangeResult.ok(frame.payload, acked);
                }
            }
        }
        if (!acked) {
            return ExchangeResult.error("Headset did not acknowledge the command.", false);
        }
        return expectedCommand == ACK_ONLY
                ? ExchangeResult.ok(null, true)
                : ExchangeResult.error("Headset did not return status.", true);
    }

    private static long writeStatusTimeoutForPayload(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return 3000L;
        }

        int command = payload[0] & 0xFF;
        if (command == 0x68) {
            return 3800L;
        }
        if (command == 0x58) {
            return 3200L;
        }
        return 3000L;
    }

    private static int dataTypeForPayload(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return DATA_MDR;
        }
        int command = payload[0] & 0xFF;
        return command >= 0x40 && command <= 0x49 ? DATA_MDR_NO2 : DATA_MDR;
    }

    private static BatchResult exchangeBatch(
            SonyMdrTransport transport,
            byte[][] commands,
            long timeoutMs) throws IOException, InterruptedException {
        List<byte[]> payloads = new ArrayList<>();
        if (commands == null) {
            return new BatchResult(payloads, false);
        }

        boolean complete = true;
        for (int i = 0; i < commands.length; i++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Status batch cancelled.");
            }
            byte[] payload = commands[i];
            ExchangeResult exchange = exchangePayload(
                    transport, payload, expectedForPayload(payload), timeoutMs, i & 1);
            if (exchange.payload != null) {
                payloads.add(exchange.payload);
            }
            if (!exchange.success && isRequiredStatusCommand(payload)) {
                complete = false;
            }
            Thread.sleep(35L);
        }
        return new BatchResult(payloads, complete);
    }

    private static boolean isRequiredStatusCommand(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return true;
        }
        int command = payload[0] & 0xFF;
        return command != 0x42 && command != 0x46 && command != 0x52 && command != 0x5A;
    }

    private static ExchangeResult readBackAfterWrite(
            SonyMdrTransport transport,
            SonyCommand command) throws IOException, InterruptedException {
        byte[] readbackPayload = readbackPayloadForWrite(command == null ? null : command.payload);
        if (readbackPayload == null) {
            return ExchangeResult.ok(null, true);
        }

        Thread.sleep(WRITE_READBACK_DELAY_MS);
        return exchangePayload(
                transport,
                readbackPayload,
                expectedForPayload(readbackPayload),
                WRITE_READBACK_TIMEOUT_MS,
                1);
    }

    private static byte[] readbackPayloadForWrite(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return null;
        }

        int command = payload[0] & 0xFF;
        if (command == 0x68) {
            return NOISE_CONTROL_STATUS;
        }
        if (command == 0x58) {
            return EQUALIZER_STATUS;
        }
        if (payload.length < 2) {
            return null;
        }
        int setting = payload[1] & 0xFF;
        switch (command) {
            case 0xD8:
                return new byte[]{(byte) 0xD6, (byte) setting};
            case 0xE8:
                return new byte[]{(byte) 0xE6, (byte) setting};
            case 0xF8:
                return new byte[]{(byte) 0xF6, (byte) setting};
            case 0x28:
                return new byte[]{0x26, (byte) setting};
            default:
                return null;
        }
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

    private static boolean payloadConfirmsCommand(SonyCommand command, byte[] payload) {
        if (command == null || command.payload == null || command.payload.length == 0) {
            return true;
        }
        byte[] write = command.payload;
        int writeCommand = write[0] & 0xFF;
        if (writeCommand == 0x68) {
            return noisePayloadConfirms(command.noiseControlState, payload);
        }
        if (writeCommand == 0x58) {
            return equalizerPayloadConfirms(write, payload);
        }
        if (writeCommand == 0xD8) {
            return settingPayloadConfirms(write, payload, 0xD7, 0xD9, 3);
        }
        if (writeCommand == 0xE8) {
            return settingPayloadConfirms(write, payload, 0xE7, 0xE9, 2);
        }
        if (writeCommand == 0xF8) {
            if (write.length > 1 && (write[1] & 0xFF) == 0x0D) {
                return quickAccessPayloadConfirms(write, payload);
            }
            return settingPayloadConfirms(write, payload, 0xF7, 0xF9, 2);
        }
        if (writeCommand == 0x28) {
            return settingPayloadConfirms(write, payload, 0x27, 0x29, 2);
        }
        return true;
    }

    private static boolean noisePayloadConfirms(NoiseControlState expected, byte[] payload) {
        if (expected == null) {
            return true;
        }
        if (payload == null || payload.length < 7) {
            return false;
        }
        NoiseControlState actual = NoiseControlState.fromPayload(payload, NoiseControlState.defaultState());
        if (actual.mode != expected.mode) {
            return false;
        }
        if (expected.mode != NoiseControlState.Mode.AMBIENT) {
            return true;
        }
        return actual.ambientLevel == expected.ambientLevel
                && actual.voiceAmbient == expected.voiceAmbient;
    }

    private static boolean equalizerPayloadConfirms(byte[] write, byte[] payload) {
        if (payload == null || payload.length < 4 || write.length < 4) {
            return false;
        }
        int command = payload[0] & 0xFF;
        if (command != 0x57 && command != 0x59) {
            return false;
        }
        if ((payload[1] & 0xFF) != (write[1] & 0xFF) || (payload[2] & 0xFF) != (write[2] & 0xFF)) {
            return false;
        }
        if ((write[3] & 0xFF) != 0x06) {
            return true;
        }
        if (payload.length < 10 || (payload[3] & 0xFF) != 0x06) {
            return false;
        }
        for (int i = 0; i < 6; i++) {
            if ((payload[i + 4] & 0xFF) != (write[i + 4] & 0xFF)) {
                return false;
            }
        }
        return true;
    }

    private static boolean settingPayloadConfirms(byte[] write, byte[] payload, int readCommand, int writeResponseCommand, int valueIndex) {
        if (payload == null || write.length <= valueIndex || payload.length <= valueIndex || write.length < 2 || payload.length < 2) {
            return false;
        }
        int command = payload[0] & 0xFF;
        if (command != readCommand && command != writeResponseCommand) {
            return false;
        }
        return (payload[1] & 0xFF) == (write[1] & 0xFF)
                && (payload[valueIndex] & 0xFF) == (write[valueIndex] & 0xFF);
    }

    private static boolean quickAccessPayloadConfirms(byte[] write, byte[] payload) {
        if (payload == null || write.length < 5 || payload.length < 5) {
            return false;
        }
        int command = payload[0] & 0xFF;
        if (command != 0xF7 && command != 0xF9) {
            return false;
        }
        if ((payload[1] & 0xFF) != 0x0D || (payload[2] & 0xFF) != (write[2] & 0xFF)) {
            return false;
        }
        int count = Math.min(write[2] & 0xFF, write.length - 3);
        if (payload.length < 3 + count) {
            return false;
        }
        for (int i = 0; i < count; i++) {
            if ((payload[i + 3] & 0xFF) != (write[i + 3] & 0xFF)) {
                return false;
            }
        }
        return true;
    }

    private static void closeQuietly(SonyMdrTransport transport) {
        if (transport == null) return;
        try {
            transport.close();
        } catch (IOException ignored) {
        }
    }

    private static void closeSocketQuietly(BluetoothSocket socket) {
        if (socket == null) return;
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private static final class TransportTarget {
        final boolean gatt;
        final UUID serviceUuid;
        final String description;

        private TransportTarget(boolean gatt, UUID serviceUuid, String description) {
            this.gatt = gatt;
            this.serviceUuid = serviceUuid;
            this.description = description;
        }

        static TransportTarget gatt() {
            return new TransportTarget(true, null, "Tandem GATT");
        }

        static TransportTarget rfcomm(UUID serviceUuid) {
            return new TransportTarget(false, serviceUuid, "RFCOMM " + serviceUuid);
        }
    }

    private static final class RfcommTransport implements SonyMdrTransport {
        private final BluetoothSocket socket;
        private final UUID serviceUuid;

        RfcommTransport(BluetoothSocket socket, UUID serviceUuid) {
            this.socket = socket;
            this.serviceUuid = serviceUuid;
        }

        @Override
        public InputStream input() throws IOException {
            return socket.getInputStream();
        }

        @Override
        public OutputStream output() throws IOException {
            return socket.getOutputStream();
        }

        @Override
        public boolean usesTableSet1() {
            return TABLE_SET_1.equals(serviceUuid);
        }

        @Override
        public String description() {
            return "RFCOMM " + serviceUuid;
        }

        @Override
        public void close() throws IOException {
            socket.close();
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
        final boolean acknowledged;

        private ExchangeResult(boolean success, String message, byte[] payload, boolean acknowledged) {
            this.success = success;
            this.message = message;
            this.payload = payload;
            this.acknowledged = acknowledged;
        }

        static ExchangeResult ok(byte[] payload) {
            return ok(payload, true);
        }

        static ExchangeResult ok(byte[] payload, boolean acknowledged) {
            return new ExchangeResult(true, "", payload, acknowledged);
        }

        static ExchangeResult error(String message) {
            return error(message, false);
        }

        static ExchangeResult error(String message, boolean acknowledged) {
            return new ExchangeResult(false, message == null ? "Command failed." : message, null, acknowledged);
        }
    }

    private static final class BatchResult {
        final List<byte[]> payloads;
        final boolean complete;

        BatchResult(List<byte[]> payloads, boolean complete) {
            this.payloads = payloads;
            this.complete = complete;
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
        public final boolean complete;

        private StatusResult(boolean success, String message, HeadsetStatus status, boolean complete) {
            this.success = success;
            this.message = message;
            this.status = status;
            this.complete = complete;
        }

        public static StatusResult ok(HeadsetStatus status) {
            return ok(status, true);
        }

        public static StatusResult ok(HeadsetStatus status, boolean complete) {
            return new StatusResult(true, "", status, complete);
        }

        public static StatusResult error(String message) {
            return new StatusResult(false, message == null ? "Status read failed." : message, null, false);
        }
    }
}
