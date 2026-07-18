package com.ripthulhu.xmcontrol.tiles.bluetooth;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

interface SonyMdrTransport extends Closeable {
    InputStream input() throws IOException;

    OutputStream output() throws IOException;

    boolean usesTableSet1();

    String description();
}
