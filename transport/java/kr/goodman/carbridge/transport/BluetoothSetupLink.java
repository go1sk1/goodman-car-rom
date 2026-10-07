package kr.goodman.carbridge.transport;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Wireless AA setup to a user-selected bonded head unit. This never changes S25+ HFP selection. */
public final class BluetoothSetupLink implements ProjectionLink {
    public static final UUID SERVICE = UUID.fromString("4de17a00-52cb-11e6-bdf4-0800200c9a66");
    private final BluetoothSocket socket;
    public BluetoothSetupLink(BluetoothDevice selectedCar) throws IOException {
        if (selectedCar == null || selectedCar.getBondState() != BluetoothDevice.BOND_BONDED)
            throw new IOException("Select a paired vehicle before starting wireless setup");
        socket = selectedCar.createRfcommSocketToServiceRecord(SERVICE);
        ScheduledExecutorService deadline = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "car-bt-connect-deadline"); thread.setDaemon(true); return thread;
        });
        ScheduledFuture<?> timeout = deadline.schedule(() -> {
            try { socket.close(); } catch (IOException ignored) {}
        }, 15, TimeUnit.SECONDS);
        try { socket.connect(); }
        catch (IOException | RuntimeException error) {
            try { socket.close(); } catch (IOException ignored) {}
            throw error;
        } finally { timeout.cancel(false); deadline.shutdownNow(); }
    }
    @Override public InputStream input() throws IOException { return socket.getInputStream(); }
    @Override public OutputStream output() throws IOException { return socket.getOutputStream(); }
    @Override public void close() throws IOException { socket.close(); }
}
