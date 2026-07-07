package com.ripthulhu.xmcontrol.tiles.bluetooth;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

final class SonyMdrCodec {
    private static final int START = 0x3E;
    private static final int END = 0x3C;
    private static final int ESC = 0x3D;

    private SonyMdrCodec() {
    }

    static byte[] buildFrame(int dataType, int sequence, byte[] payload) {
        int payloadLength = payload == null ? 0 : payload.length;
        ByteArrayOutputStream inner = new ByteArrayOutputStream(7 + payloadLength);
        inner.write(dataType & 0xFF);
        inner.write(sequence & 0xFF);
        inner.write((payloadLength >> 24) & 0xFF);
        inner.write((payloadLength >> 16) & 0xFF);
        inner.write((payloadLength >> 8) & 0xFF);
        inner.write(payloadLength & 0xFF);
        if (payload != null) {
            inner.write(payload, 0, payload.length);
        }
        byte[] innerBytes = inner.toByteArray();
        inner.write(checksum(innerBytes) & 0xFF);

        ByteArrayOutputStream framed = new ByteArrayOutputStream();
        framed.write(START);
        for (byte value : inner.toByteArray()) {
            int b = value & 0xFF;
            if (b == START || b == END || b == ESC) {
                framed.write(ESC);
                framed.write(b ^ 0x10);
            } else {
                framed.write(b);
            }
        }
        framed.write(END);
        return framed.toByteArray();
    }

    static boolean ackRequired(int dataType) {
        return dataType != 0x01 && dataType != 0x1C && dataType != 0x1D && dataType != 0x1E;
    }

    static int checksum(byte[] bytes) {
        int sum = 0;
        for (byte value : bytes) {
            sum = (sum + (value & 0xFF)) & 0xFF;
        }
        return sum;
    }

    static final class Frame {
        final boolean valid;
        final int dataType;
        final int sequence;
        final byte[] payload;

        Frame(boolean valid, int dataType, int sequence, byte[] payload) {
            this.valid = valid;
            this.dataType = dataType;
            this.sequence = sequence;
            this.payload = payload == null ? new byte[0] : payload;
        }
    }

    static final class Parser {
        private boolean inFrame;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        List<Frame> add(byte[] bytes, int count) {
            List<Frame> frames = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int b = bytes[i] & 0xFF;
                if (!inFrame) {
                    if (b == START) {
                        inFrame = true;
                        buffer.reset();
                    }
                    continue;
                }
                if (b == START) {
                    buffer.reset();
                    inFrame = true;
                    continue;
                }
                if (b == END) {
                    frames.add(parse(buffer.toByteArray()));
                    buffer.reset();
                    inFrame = false;
                    continue;
                }
                buffer.write(b);
            }
            return frames;
        }

        private Frame parse(byte[] escaped) {
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            for (int i = 0; i < escaped.length; i++) {
                int b = escaped[i] & 0xFF;
                if (b == ESC) {
                    if (++i >= escaped.length) return new Frame(false, 0, 0, null);
                    b = (escaped[i] & 0xFF) ^ 0x10;
                }
                raw.write(b);
            }

            byte[] bytes = raw.toByteArray();
            if (bytes.length < 7) return new Frame(false, 0, 0, null);

            byte[] withoutChecksum = new byte[bytes.length - 1];
            System.arraycopy(bytes, 0, withoutChecksum, 0, withoutChecksum.length);
            if (checksum(withoutChecksum) != (bytes[bytes.length - 1] & 0xFF)) {
                return new Frame(false, 0, 0, null);
            }

            int payloadLength = ((bytes[2] & 0xFF) << 24)
                    | ((bytes[3] & 0xFF) << 16)
                    | ((bytes[4] & 0xFF) << 8)
                    | (bytes[5] & 0xFF);
            if (payloadLength != bytes.length - 7) return new Frame(false, 0, 0, null);

            byte[] payload = new byte[payloadLength];
            System.arraycopy(bytes, 6, payload, 0, payloadLength);
            return new Frame(true, bytes[0] & 0xFF, bytes[1] & 0xFF, payload);
        }
    }
}
