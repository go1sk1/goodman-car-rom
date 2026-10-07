package kr.goodman.carbridge.transport;

import android.net.Network;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

/** Uses the selected vehicle Wi-Fi network, never the default/cellular route. */
public final class NetworkProjectionLink implements ProjectionLink {
    private volatile Socket socket;
    private final String address;
    private final int port;
    private boolean streamsOpened;
    public NetworkProjectionLink(Network vehicleNetwork, InetAddress negotiatedAddress, int negotiatedPort)
            throws IOException {
        if (vehicleNetwork == null || negotiatedAddress == null || negotiatedPort < 1 || negotiatedPort > 65535
                || negotiatedAddress.isAnyLocalAddress() || negotiatedAddress.isLoopbackAddress()
                || negotiatedAddress.isMulticastAddress()) throw new IOException("Invalid vehicle endpoint");
        address = negotiatedAddress.getHostAddress(); port = negotiatedPort;
        socket = vehicleNetwork.getSocketFactory().createSocket();
        try {
            socket.setTcpNoDelay(true); socket.setSoTimeout(15000);
            socket.connect(new InetSocketAddress(negotiatedAddress, negotiatedPort), 10000);
        } catch (IOException | RuntimeException failure) {
            try { socket.close(); } catch (IOException ignored) {}
            throw failure;
        }
    }
    /** Wireless WPP may require an outer TLS stream before the inner AA-framed TLS session. */
    public synchronized void secure(SSLContext enrolledContext) throws IOException {
        if (streamsOpened || socket instanceof SSLSocket) throw new IOException("TLS upgrade must precede wireless setup I/O");
        Socket raw = socket;
        try {
            SSLSocket secure = (SSLSocket) enrolledContext.getSocketFactory().createSocket(raw, address, port, true);
            socket = secure; secure.setUseClientMode(false); secure.setNeedClientAuth(true);
            secure.setEnabledProtocols(new String[]{"TLSv1.2"}); secure.setSoTimeout(15000);
            secure.startHandshake();
        } catch (IOException | RuntimeException failure) {
            try { socket.close(); } catch (IOException ignored) {}
            try { raw.close(); } catch (IOException ignored) {}
            throw failure;
        }
    }
    @Override public synchronized InputStream input() throws IOException { streamsOpened = true; return socket.getInputStream(); }
    @Override public synchronized OutputStream output() throws IOException { streamsOpened = true; return socket.getOutputStream(); }
    @Override public void close() throws IOException { socket.close(); }
}
