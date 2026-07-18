package com.ripthulhu.xmcontrol.tiles.bluetooth;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

final class SonyGattTransport implements SonyMdrTransport {
    private static final String TAG = "SonyGattTransport";
    private static final UUID TANDEM_SERVICE =
            UUID.fromString("5b833e20-6bc7-4802-8e9a-723ceca4bd8f");
    private static final UUID TO_ACCESSORY =
            UUID.fromString("5b833c60-6bc7-4802-8e9a-723ceca4bd8f");
    private static final UUID FROM_ACCESSORY =
            UUID.fromString("5b833c61-6bc7-4802-8e9a-723ceca4bd8f");
    private static final UUID WRITABLE_VALUE_LENGTH =
            UUID.fromString("5b833c91-6bc7-4802-8e9a-723ceca4bd8f");
    private static final UUID CLIENT_CHARACTERISTIC_CONFIG =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final int REQUESTED_MTU = 517;
    private static final int DEFAULT_WRITE_LENGTH = 17;
    private static final long CONNECT_TIMEOUT_MS = 8_000L;
    private static final long MTU_TIMEOUT_MS = 3_000L;
    private static final long SERVICE_TIMEOUT_MS = 5_000L;
    private static final long READ_TIMEOUT_MS = 2_000L;
    private static final long DESCRIPTOR_TIMEOUT_MS = 2_000L;
    private static final long WRITE_TIMEOUT_MS = 1_200L;

    private final Context context;
    private final BluetoothDevice device;
    private final PipedInputStream input;
    private final PipedOutputStream notificationSink;
    private final OutputStream output = new GattOutputStream();
    private final AtomicReference<IOException> terminalFailure = new AtomicReference<>();
    private final CountDownLatch connectionLatch = new CountDownLatch(1);
    private final CountDownLatch mtuLatch = new CountDownLatch(1);
    private final CountDownLatch servicesLatch = new CountDownLatch(1);
    private final CountDownLatch writableLengthLatch = new CountDownLatch(1);
    private final CountDownLatch notificationLatch = new CountDownLatch(1);
    private final Object writeLock = new Object();

    private volatile BluetoothGatt gatt;
    private volatile BluetoothGattCharacteristic toAccessory;
    private volatile BluetoothGattCharacteristic fromAccessory;
    private volatile CountDownLatch writeLatch;
    private volatile int connectionStatus = Integer.MIN_VALUE;
    private volatile int connectionState = BluetoothProfile.STATE_DISCONNECTED;
    private volatile int mtuStatus = Integer.MIN_VALUE;
    private volatile int discoveredMtu = DEFAULT_WRITE_LENGTH + 3;
    private volatile int servicesStatus = Integer.MIN_VALUE;
    private volatile int writableLengthStatus = Integer.MIN_VALUE;
    private volatile byte[] writableLengthValue;
    private volatile int notificationStatus = Integer.MIN_VALUE;
    private volatile int writeStatus = Integer.MIN_VALUE;
    private volatile int maxWriteLength = DEFAULT_WRITE_LENGTH;
    private volatile boolean closed;

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt callbackGatt, int status, int newState) {
            gatt = callbackGatt;
            connectionStatus = status;
            connectionState = newState;
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                connectionLatch.countDown();
                return;
            }

            if (!closed) {
                fail(new IOException("GATT disconnected (status " + status + ", state " + newState + ")."));
            }
            connectionLatch.countDown();
        }

        @Override
        public void onMtuChanged(BluetoothGatt callbackGatt, int mtu, int status) {
            discoveredMtu = mtu;
            mtuStatus = status;
            mtuLatch.countDown();
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt callbackGatt, int status) {
            servicesStatus = status;
            servicesLatch.countDown();
        }

        @Override
        public void onCharacteristicRead(
                BluetoothGatt callbackGatt,
                BluetoothGattCharacteristic characteristic,
                byte[] value,
                int status) {
            handleCharacteristicRead(characteristic, value, status);
        }

        @Override
        @SuppressWarnings("deprecation")
        public void onCharacteristicRead(
                BluetoothGatt callbackGatt,
                BluetoothGattCharacteristic characteristic,
                int status) {
            handleCharacteristicRead(characteristic, characteristic.getValue(), status);
        }

        @Override
        public void onDescriptorWrite(
                BluetoothGatt callbackGatt,
                BluetoothGattDescriptor descriptor,
                int status) {
            BluetoothGattCharacteristic characteristic = descriptor.getCharacteristic();
            if (CLIENT_CHARACTERISTIC_CONFIG.equals(descriptor.getUuid())
                    && characteristic != null
                    && FROM_ACCESSORY.equals(characteristic.getUuid())) {
                notificationStatus = status;
                notificationLatch.countDown();
            }
        }

        @Override
        public void onCharacteristicChanged(
                BluetoothGatt callbackGatt,
                BluetoothGattCharacteristic characteristic,
                byte[] value) {
            handleNotification(characteristic, value);
        }

        @Override
        @SuppressWarnings("deprecation")
        public void onCharacteristicChanged(
                BluetoothGatt callbackGatt,
                BluetoothGattCharacteristic characteristic) {
            handleNotification(characteristic, characteristic.getValue());
        }

        @Override
        public void onCharacteristicWrite(
                BluetoothGatt callbackGatt,
                BluetoothGattCharacteristic characteristic,
                int status) {
            if (!TO_ACCESSORY.equals(characteristic.getUuid())) return;
            writeStatus = status;
            CountDownLatch pendingWrite = writeLatch;
            if (pendingWrite != null) pendingWrite.countDown();
        }
    };

    private SonyGattTransport(Context context, BluetoothDevice device) throws IOException {
        this.context = context.getApplicationContext();
        this.device = device;
        input = new PipedInputStream(32 * 1024);
        notificationSink = new PipedOutputStream(input);
    }

    static SonyGattTransport connect(Context context, BluetoothDevice device)
            throws IOException, InterruptedException {
        SonyGattTransport transport = new SonyGattTransport(context, device);
        boolean opened = false;
        try {
            transport.open();
            opened = true;
            return transport;
        } finally {
            if (!opened) transport.close();
        }
    }

    @SuppressLint("MissingPermission")
    private void open() throws IOException, InterruptedException {
        requireConnectPermission();
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
        if (gatt == null) throw new IOException("Could not start Sony GATT connection.");

        await(connectionLatch, CONNECT_TIMEOUT_MS, "GATT connection timed out.");
        checkFailure();
        if (connectionStatus != BluetoothGatt.GATT_SUCCESS
                || connectionState != BluetoothProfile.STATE_CONNECTED) {
            throw new IOException("GATT connection failed (status " + connectionStatus + ").");
        }

        // Sony's client lets the encrypted LE link settle before MTU negotiation.
        Thread.sleep(1_000L);
        BluetoothGatt activeGatt = requireGatt();
        if (activeGatt.requestMtu(REQUESTED_MTU)) {
            await(mtuLatch, MTU_TIMEOUT_MS, "GATT MTU negotiation timed out.");
            checkFailure();
            if (mtuStatus != BluetoothGatt.GATT_SUCCESS) {
                throw new IOException("GATT MTU negotiation failed (status " + mtuStatus + ").");
            }
            maxWriteLength = Math.max(DEFAULT_WRITE_LENGTH, discoveredMtu - 3);
        }

        if (!activeGatt.discoverServices()) {
            throw new IOException("Could not discover Sony GATT services.");
        }
        await(servicesLatch, SERVICE_TIMEOUT_MS, "Sony GATT service discovery timed out.");
        checkFailure();
        if (servicesStatus != BluetoothGatt.GATT_SUCCESS) {
            throw new IOException("Sony GATT service discovery failed (status " + servicesStatus + ").");
        }

        BluetoothGattService tandem = activeGatt.getService(TANDEM_SERVICE);
        if (tandem == null) throw new IOException("Sony Tandem GATT service is unavailable.");
        toAccessory = tandem.getCharacteristic(TO_ACCESSORY);
        fromAccessory = tandem.getCharacteristic(FROM_ACCESSORY);
        if (toAccessory == null || fromAccessory == null) {
            throw new IOException("Sony Tandem GATT characteristics are unavailable.");
        }

        readWritableLength(activeGatt, tandem);
        enableNotifications(activeGatt);
        Log.i(TAG, "Connected to " + device.getAddress()
                + " over GATT (MTU " + discoveredMtu
                + ", write length " + maxWriteLength + ")");
    }

    @SuppressLint("MissingPermission")
    private void readWritableLength(BluetoothGatt activeGatt, BluetoothGattService tandem)
            throws IOException, InterruptedException {
        BluetoothGattCharacteristic writable = tandem.getCharacteristic(WRITABLE_VALUE_LENGTH);
        if (writable == null) {
            maxWriteLength = Math.max(DEFAULT_WRITE_LENGTH, discoveredMtu - 3);
            return;
        }
        if (!activeGatt.readCharacteristic(writable)) {
            throw new IOException("Could not read Sony GATT write length.");
        }
        await(writableLengthLatch, READ_TIMEOUT_MS, "Sony GATT write-length read timed out.");
        checkFailure();
        if (writableLengthStatus != BluetoothGatt.GATT_SUCCESS) {
            throw new IOException("Sony GATT write-length read failed (status "
                    + writableLengthStatus + ").");
        }
        byte[] value = writableLengthValue;
        if (value == null || value.length != 2) {
            throw new IOException("Sony GATT returned an invalid write length.");
        }
        int reportedLength = ((value[0] & 0xFF) << 8) | (value[1] & 0xFF);
        if (reportedLength + 3 < 20 || reportedLength + 3 > 512) {
            throw new IOException("Sony GATT returned an unsupported write length: "
                    + reportedLength + ".");
        }
        maxWriteLength = reportedLength;
    }

    @SuppressLint("MissingPermission")
    private void enableNotifications(BluetoothGatt activeGatt)
            throws IOException, InterruptedException {
        BluetoothGattCharacteristic notifications = fromAccessory;
        if (!activeGatt.setCharacteristicNotification(notifications, true)) {
            throw new IOException("Could not enable Sony GATT notifications.");
        }
        BluetoothGattDescriptor descriptor =
                notifications.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG);
        if (descriptor == null) {
            throw new IOException("Sony GATT notification descriptor is unavailable.");
        }

        byte[] enableValue = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
        boolean started;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            started = activeGatt.writeDescriptor(descriptor, enableValue)
                    == BluetoothStatusCodes.SUCCESS;
        } else {
            descriptor.setValue(enableValue);
            started = activeGatt.writeDescriptor(descriptor);
        }
        if (!started) throw new IOException("Could not configure Sony GATT notifications.");

        await(notificationLatch, DESCRIPTOR_TIMEOUT_MS,
                "Sony GATT notification setup timed out.");
        checkFailure();
        if (notificationStatus != BluetoothGatt.GATT_SUCCESS) {
            throw new IOException("Sony GATT notification setup failed (status "
                    + notificationStatus + ").");
        }
    }

    private void handleCharacteristicRead(
            BluetoothGattCharacteristic characteristic,
            byte[] value,
            int status) {
        if (!WRITABLE_VALUE_LENGTH.equals(characteristic.getUuid())) return;
        writableLengthStatus = status;
        writableLengthValue = value == null ? null : Arrays.copyOf(value, value.length);
        writableLengthLatch.countDown();
    }

    private void handleNotification(BluetoothGattCharacteristic characteristic, byte[] value) {
        if (!FROM_ACCESSORY.equals(characteristic.getUuid()) || value == null || value.length == 0) {
            return;
        }
        try {
            synchronized (notificationSink) {
                notificationSink.write(value);
                notificationSink.flush();
            }
        } catch (IOException ex) {
            if (!closed) fail(new IOException("Sony GATT notification stream failed.", ex));
        }
    }

    @SuppressLint("MissingPermission")
    private void write(byte[] value, int offset, int length)
            throws IOException, InterruptedException {
        if (value == null) throw new NullPointerException("value");
        if (offset < 0 || length < 0 || offset > value.length - length) {
            throw new IndexOutOfBoundsException();
        }

        int remaining = length;
        int cursor = offset;
        while (remaining > 0) {
            int chunkLength = Math.min(remaining, maxWriteLength);
            writeChunk(Arrays.copyOfRange(value, cursor, cursor + chunkLength));
            cursor += chunkLength;
            remaining -= chunkLength;
        }
    }

    @SuppressLint("MissingPermission")
    private void writeChunk(byte[] chunk) throws IOException, InterruptedException {
        synchronized (writeLock) {
            checkFailure();
            if (closed) throw new IOException("Sony GATT transport is closed.");
            BluetoothGatt activeGatt = requireGatt();
            BluetoothGattCharacteristic characteristic = toAccessory;
            if (characteristic == null) throw new IOException("Sony GATT is not ready.");

            CountDownLatch pendingWrite = new CountDownLatch(1);
            writeStatus = Integer.MIN_VALUE;
            writeLatch = pendingWrite;
            boolean started;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                started = activeGatt.writeCharacteristic(
                        characteristic,
                        chunk,
                        BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                        == BluetoothStatusCodes.SUCCESS;
            } else {
                characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                characteristic.setValue(chunk);
                started = activeGatt.writeCharacteristic(characteristic);
            }
            if (!started) {
                writeLatch = null;
                throw new IOException("Sony GATT write could not be started.");
            }

            await(pendingWrite, WRITE_TIMEOUT_MS, "Sony GATT write timed out.");
            writeLatch = null;
            checkFailure();
            if (writeStatus != BluetoothGatt.GATT_SUCCESS) {
                throw new IOException("Sony GATT write failed (status " + writeStatus + ").");
            }
        }
    }

    private void await(CountDownLatch latch, long timeoutMs, String timeoutMessage)
            throws IOException, InterruptedException {
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            throw new IOException(timeoutMessage);
        }
    }

    private void checkFailure() throws IOException {
        IOException failure = terminalFailure.get();
        if (failure != null) throw failure;
    }

    private BluetoothGatt requireGatt() throws IOException {
        BluetoothGatt activeGatt = gatt;
        if (activeGatt == null) throw new IOException("Sony GATT connection is unavailable.");
        return activeGatt;
    }

    private void fail(IOException failure) {
        if (!terminalFailure.compareAndSet(null, failure)) return;
        connectionLatch.countDown();
        mtuLatch.countDown();
        servicesLatch.countDown();
        writableLengthLatch.countDown();
        notificationLatch.countDown();
        CountDownLatch pendingWrite = writeLatch;
        if (pendingWrite != null) pendingWrite.countDown();
        try {
            notificationSink.close();
        } catch (IOException ignored) {
        }
    }

    private void requireConnectPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Missing BLUETOOTH_CONNECT permission.");
        }
    }

    @Override
    public InputStream input() {
        return input;
    }

    @Override
    public OutputStream output() {
        return output;
    }

    @Override
    public boolean usesTableSet1() {
        return false;
    }

    @Override
    public String description() {
        return "Tandem GATT";
    }

    @Override
    @SuppressLint("MissingPermission")
    public void close() {
        if (closed) return;
        closed = true;
        BluetoothGatt activeGatt = gatt;
        gatt = null;
        if (activeGatt != null) {
            try {
                activeGatt.disconnect();
            } catch (RuntimeException ignored) {
            }
            activeGatt.close();
        }
        try {
            notificationSink.close();
        } catch (IOException ignored) {
        }
        try {
            input.close();
        } catch (IOException ignored) {
        }
    }

    private final class GattOutputStream extends OutputStream {
        @Override
        public void write(int value) throws IOException {
            write(new byte[]{(byte) value}, 0, 1);
        }

        @Override
        public void write(byte[] value, int offset, int length) throws IOException {
            try {
                SonyGattTransport.this.write(value, offset, length);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IOException("Sony GATT write interrupted.", ex);
            }
        }
    }
}
