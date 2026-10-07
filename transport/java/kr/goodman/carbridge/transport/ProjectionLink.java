package kr.goodman.carbridge.transport;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Owned duplex connection; closing must unblock its reader and writer. */
public interface ProjectionLink extends Closeable {
    InputStream input() throws IOException;
    OutputStream output() throws IOException;
}
