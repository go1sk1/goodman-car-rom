package kr.goodman.carbridge.transport;

import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbManager;
import android.os.ParcelFileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Opens the accessory explicitly selected and authorized by the device user. */
public final class UsbAccessoryLink implements ProjectionLink {
    private final ParcelFileDescriptor.AutoCloseInputStream input;
    private final ParcelFileDescriptor.AutoCloseOutputStream output;
    private boolean closed;
    public UsbAccessoryLink(UsbManager manager, UsbAccessory accessory) throws IOException {
        if (accessory == null || !manager.hasPermission(accessory))
            throw new IOException("USB accessory permission is required");
        ParcelFileDescriptor original = manager.openAccessory(accessory);
        if (original == null) throw new IOException("USB accessory is no longer available");
        ParcelFileDescriptor read = null, write = null;
        try {
            read = ParcelFileDescriptor.dup(original.getFileDescriptor());
            write = ParcelFileDescriptor.dup(original.getFileDescriptor());
            input = new ParcelFileDescriptor.AutoCloseInputStream(read);
            output = new ParcelFileDescriptor.AutoCloseOutputStream(write);
        } catch (IOException | RuntimeException failure) {
            if (read != null) try { read.close(); } catch (IOException ignored) {}
            if (write != null) try { write.close(); } catch (IOException ignored) {}
            throw failure;
        } finally {
            try { original.close(); }
            catch (IOException error) {
                if (read != null) try { read.close(); } catch (IOException ignored) {}
                if (write != null) try { write.close(); } catch (IOException ignored) {}
                throw error;
            }
        }
    }
    @Override public InputStream input() { return input; }
    @Override public OutputStream output() { return output; }
    @Override public synchronized void close() throws IOException {
        if (closed) return; closed = true;
        IOException failure = null;
        try { input.close(); } catch (IOException error) { failure = error; }
        try { output.close(); } catch (IOException error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
        if (failure != null) throw failure;
    }
}
