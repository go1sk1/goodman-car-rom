package kr.goodman.carbridge;

import android.media.AudioFormat;
import java.util.Set;
import kr.goodman.carbridge.core.VehiclePolicy.Sound;

/** Implemented by an authenticated, negotiated USB/wireless vehicle session. Not exported IPC. */
interface VehicleTransport {
    Set<Sound> outputChannels();
    AudioFormat outputFormat(Sound sound);
    boolean hasMicrophone();
    AudioFormat microphoneFormat();
    /** Nonblocking enqueue; false means backpressure/disconnect and restores local routing. */
    boolean offerAudio(Sound channel, byte[] pcm, long elapsedRealtimeNanos);
    /** Start only on an actual Android recording request; stop when idle or privacy is enabled. */
    void requestMicrophone(boolean active);
}
