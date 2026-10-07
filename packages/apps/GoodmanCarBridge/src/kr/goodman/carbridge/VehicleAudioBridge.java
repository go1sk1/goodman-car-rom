package kr.goodman.carbridge;

import android.content.Context;
import android.hardware.SensorPrivacyManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioRecordingConfiguration;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiopolicy.AudioMix;
import android.media.audiopolicy.AudioMixingRule;
import android.media.audiopolicy.AudioPolicy;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import kr.goodman.carbridge.core.VehiclePolicy.Category;
import kr.goodman.carbridge.core.VehiclePolicy.Sound;

/** Privileged PCM routing; active only while an actual vehicle transport is connected. */
final class VehicleAudioBridge implements AutoCloseable {
    private final Context context;
    private final AudioManager manager;
    private final SensorPrivacyManager privacy;
    private final HandlerThread thread = new HandlerThread("vehicle-audio");
    private final Handler handler;
    private final ArrayBlockingQueue<byte[]> microphoneQueue = new ArrayBlockingQueue<>(5);
    private final EnumMap<Sound, AudioRecord> outputs = new EnumMap<>(Sound.class);
    private AudioPolicy policy;
    private AudioMix microphoneMix;
    private AudioTrack microphoneTrack;
    private AudioRecord callRecord;
    private AudioTrack callTrack;
    private Sound callSound = Sound.CALL;
    private boolean hfpActive;
    private boolean callAttempted;
    private boolean recordingMicrophoneWanted;
    private boolean callMicrophoneWanted;
    private volatile String callState = "통화 음성: 대기";
    private VehicleTransport transport;
    private volatile Object session;
    private volatile boolean microphoneWanted;
    private volatile int microphoneFrameBytes;
    private volatile String state = "오디오: 차량 연결 대기";
    private boolean callbackRegistered;
    private boolean privacyBlocked = true;
    private long privacyCheckedAt;
    private long microphoneStartedAt;
    private long lastMicrophoneAt;
    private byte[] pendingMicrophone;
    private int pendingOffset;
    private int pendingCallOffset;

    VehicleAudioBridge(Context context) {
        this.context = context; manager = context.getSystemService(AudioManager.class);
        privacy = context.getSystemService(SensorPrivacyManager.class);
        thread.start(); handler = new Handler(thread.getLooper());
    }
    String snapshot() { return state + "\n" + callState; }
    void setHfpActive(Object token, boolean active) {
        handler.post(() -> {
            if (session == null || token != session || hfpActive == active) return;
            hfpActive = active; callAttempted = false;
            if (!active) {
                stopCall();
                updateMicrophone(manager.getActiveRecordingConfigurations());
            }
        });
    }
    void configure(Object token, VehicleTransport endpoint, VehicleSettings settings) {
        handler.post(() -> {
            stopRouting();
            if (endpoint == null) return;
            try {
                transport = endpoint;
                callSound = settings.sound(Category.CALL);
                if (endpoint.hasMicrophone()) microphoneFrameBytes = 2 * checkedFormat(endpoint.microphoneFormat()).getChannelCount();
                AudioPolicy.Builder builder = new AudioPolicy.Builder(context).setLooper(thread.getLooper());
                EnumMap<Sound, AudioMix> mixes = new EnumMap<>(Sound.class);
                Set<Sound> supported = EnumSet.noneOf(Sound.class); supported.addAll(endpoint.outputChannels());
                List<String> unavailable = new ArrayList<>();
                for (Sound sound : Sound.values()) {
                    AudioMixingRule.Builder rule = new AudioMixingRule.Builder();
                    boolean selected = false;
                    for (Category category : Category.values()) {
                        // HFP PCM comes from the Bluetooth/audio HAL, not ordinary app playback.
                        if (category == Category.CALL || settings.sound(category) != sound) continue;
                        if (!supported.contains(sound)) { unavailable.add(category.name()); continue; }
                        rule.addRule(new AudioAttributes.Builder().setUsage(category.androidUsage).build(),
                                AudioMixingRule.RULE_MATCH_ATTRIBUTE_USAGE);
                        selected = true;
                    }
                    if (selected) {
                        AudioFormat format = checkedFormat(endpoint.outputFormat(sound));
                        AudioMix mix = new AudioMix.Builder(rule.build()).setFormat(format)
                                .setRouteFlags(AudioMix.ROUTE_FLAG_LOOP_BACK).build();
                        builder.addMix(mix); mixes.put(sound, mix);
                    }
                }
                if (settings.microphone() && endpoint.hasMicrophone()) {
                    AudioFormat format = checkedFormat(endpoint.microphoneFormat());
                    AudioMixingRule.Builder rule = new AudioMixingRule.Builder()
                            .setTargetMixRole(AudioMixingRule.MIX_ROLE_INJECTOR);
                    for (int source : new int[]{MediaRecorder.AudioSource.DEFAULT, MediaRecorder.AudioSource.MIC,
                            MediaRecorder.AudioSource.CAMCORDER, MediaRecorder.AudioSource.VOICE_RECOGNITION,
                            MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.AudioSource.UNPROCESSED,
                            MediaRecorder.AudioSource.VOICE_PERFORMANCE}) {
                        rule.addRule(new AudioAttributes.Builder().setInternalCapturePreset(source).build(),
                                AudioMixingRule.RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET);
                    }
                    microphoneMix = new AudioMix.Builder(rule.build()).setFormat(format)
                            .setRouteFlags(AudioMix.ROUTE_FLAG_LOOP_BACK).build();
                    builder.addMix(microphoneMix);
                    microphoneFrameBytes = 2 * format.getChannelCount();
                }
                if (mixes.isEmpty() && microphoneMix == null && !supported.contains(callSound)) {
                    state = "오디오: 차량이 요청한 출력/마이크를 제공하지 않음";
                    transport = null; return;
                }
                if (!mixes.isEmpty() || microphoneMix != null) {
                    policy = builder.build();
                    if (manager.registerAudioPolicy(policy) != AudioManager.SUCCESS)
                        throw new IllegalStateException("Audio policy registration rejected");
                }
                for (Map.Entry<Sound, AudioMix> entry : mixes.entrySet()) {
                    AudioRecord record = policy.createAudioRecordSink(entry.getValue());
                    if (record == null || record.getState() != AudioRecord.STATE_INITIALIZED) {
                        if (record != null) record.release();
                        throw new IllegalStateException("Audio capture unavailable");
                    }
                    outputs.put(entry.getKey(), record); record.startRecording();
                }
                if (microphoneMix != null) {
                    microphoneTrack = policy.createAudioTrackSource(microphoneMix);
                    if (microphoneTrack == null || microphoneTrack.getState() != AudioTrack.STATE_INITIALIZED)
                        throw new IllegalStateException("Vehicle microphone injection unavailable");
                    manager.registerAudioRecordingCallback(recordingCallback, handler); callbackRegistered = true;
                }
                session = token;
                state = unavailable.isEmpty() ? "오디오 경로 연결됨"
                        : "지원하지 않는 차량 출력: " + unavailable;
                handler.post(pump);
            } catch (RuntimeException failure) { fail(failure.getClass().getSimpleName()); }
        });
    }
    private static AudioFormat checkedFormat(AudioFormat format) {
        if (format == null || format.getEncoding() != AudioFormat.ENCODING_PCM_16BIT
                || format.getSampleRate() < 8000 || format.getSampleRate() > 48000
                || (format.getChannelMask() != AudioFormat.CHANNEL_OUT_MONO
                    && format.getChannelMask() != AudioFormat.CHANNEL_OUT_STEREO))
            throw new IllegalArgumentException("Unsupported negotiated PCM format");
        return format;
    }
    boolean microphone(Object token, byte[] pcm) {
        int frameBytes = microphoneFrameBytes;
        if (token != session || !microphoneWanted || frameBytes == 0 || pcm == null
                || pcm.length == 0 || pcm.length > 19200 || pcm.length % frameBytes != 0) return false;
        return microphoneQueue.offer(pcm.clone());
    }
    private final AudioManager.AudioRecordingCallback recordingCallback = new AudioManager.AudioRecordingCallback() {
        @Override public void onRecordingConfigChanged(List<AudioRecordingConfiguration> configs) {
            updateMicrophone(configs);
        }
    };
    private void updateMicrophone(List<AudioRecordingConfiguration> configs) {
        if (session == null) return;
        privacyBlocked = manager.isMicrophoneMute() || privacy == null
                || privacy.areAnySensorPrivacyTogglesEnabled(SensorPrivacyManager.Sensors.MICROPHONE);
        boolean wanted = false;
        if (!privacyBlocked && microphoneTrack != null) for (AudioRecordingConfiguration config : configs) {
            AudioDeviceInfo device = config.getAudioDevice();
            if (!config.isClientSilenced() && device != null
                    && device.getType() == AudioDeviceInfo.TYPE_REMOTE_SUBMIX
                    && microphoneMix.getRegistration().equals(device.getAddress())) { wanted = true; break; }
        }
        boolean callWanted = !privacyBlocked && callTrack != null && hfpActive;
        if (wanted == recordingMicrophoneWanted && callWanted == callMicrophoneWanted) return;
        recordingMicrophoneWanted = wanted; callMicrophoneWanted = callWanted;
        microphoneWanted = wanted || callWanted;
        microphoneQueue.clear(); pendingMicrophone = null; pendingOffset = pendingCallOffset = 0;
        if (microphoneWanted) {
            microphoneStartedAt = SystemClock.elapsedRealtime(); lastMicrophoneAt = 0;
        }
        if (microphoneTrack != null) {
            if (wanted) microphoneTrack.play(); else { microphoneTrack.pause(); microphoneTrack.flush(); }
        }
        if (callTrack != null) {
            if (callWanted) callTrack.play(); else { callTrack.pause(); callTrack.flush(); }
        }
        transport.requestMicrophone(microphoneWanted);
    }
    private void startCallIfSupported() {
        if (!hfpActive || callAttempted || manager.getMode() != AudioManager.MODE_IN_CALL) return;
        callAttempted = true;
        try {
            if (!transport.hasMicrophone() || !transport.outputChannels().contains(callSound)
                    || !manager.isPstnCallAudioInterceptable())
                throw new UnsupportedOperationException("Call audio HAL/vehicle channel unavailable");
            AudioFormat output = checkedFormat(transport.outputFormat(callSound));
            AudioFormat input = new AudioFormat.Builder(output).setChannelMask(output.getChannelCount() == 1
                    ? AudioFormat.CHANNEL_IN_MONO : AudioFormat.CHANNEL_IN_STEREO).build();
            callRecord = manager.getCallDownlinkExtractionAudioRecord(input);
            callTrack = manager.getCallUplinkInjectionAudioTrack(checkedFormat(transport.microphoneFormat()));
            callRecord.startRecording(); callState = "통화 음성 경로 연결 요청됨 · 양방향 음성 확인 필요";
        } catch (RuntimeException unavailable) {
            stopCall(); callState = "통화 음성: 이 기기의 통화 오디오 경로 지원 확인 필요";
        }
    }
    private void stopCall() {
        if (callRecord != null) { try { callRecord.stop(); } catch (IllegalStateException ignored) {} callRecord.release(); }
        if (callTrack != null) { try { callTrack.stop(); } catch (IllegalStateException ignored) {} callTrack.release(); }
        callRecord = null; callTrack = null; callState = "통화 음성: 대기";
    }
    private final Runnable pump = new Runnable() {
        private final byte[] capture = new byte[19200];
        @Override public void run() {
            if (session == null) return;
            try {
                long now = SystemClock.elapsedRealtime();
                if (now - privacyCheckedAt >= 100) {
                    privacyCheckedAt = now;
                    if (callRecord != null && manager.getMode() != AudioManager.MODE_IN_CALL) stopCall();
                    startCallIfSupported(); updateMicrophone(manager.getActiveRecordingConfigurations());
                }
                for (Map.Entry<Sound, AudioRecord> entry : outputs.entrySet()) {
                    int count = entry.getValue().read(capture, 0, capture.length, AudioRecord.READ_NON_BLOCKING);
                    if (count < 0) throw new IllegalStateException("Audio capture stopped");
                    // A call has priority if another category uses the same vehicle channel.
                    if (count > 0 && !(callRecord != null && entry.getKey() == callSound)
                            && !transport.offerAudio(entry.getKey(), Arrays.copyOf(capture, count), SystemClock.elapsedRealtimeNanos()))
                        throw new IllegalStateException("Vehicle audio queue unavailable");
                }
                if (callRecord != null) {
                    int count = callRecord.read(capture, 0, capture.length, AudioRecord.READ_NON_BLOCKING);
                    if (count < 0) { stopCall(); updateMicrophone(manager.getActiveRecordingConfigurations()); }
                    else if (count > 0 && !transport.offerAudio(callSound, Arrays.copyOf(capture, count), SystemClock.elapsedRealtimeNanos()))
                        throw new IllegalStateException("Vehicle call audio queue unavailable");
                }
                if (microphoneWanted) {
                    if (pendingMicrophone == null) { pendingMicrophone = microphoneQueue.poll(); pendingOffset = pendingCallOffset = 0; }
                    if (pendingMicrophone != null) {
                        if (recordingMicrophoneWanted && pendingOffset < pendingMicrophone.length) {
                            int count = microphoneTrack.write(pendingMicrophone, pendingOffset,
                                    pendingMicrophone.length - pendingOffset, AudioTrack.WRITE_NON_BLOCKING);
                            if (count < 0) throw new IllegalStateException("Microphone injection stopped");
                            if (count > 0) { lastMicrophoneAt = now; pendingOffset += count; }
                        } else pendingOffset = pendingMicrophone.length;
                        if (callMicrophoneWanted && pendingCallOffset < pendingMicrophone.length) {
                            int count = callTrack.write(pendingMicrophone, pendingCallOffset,
                                    pendingMicrophone.length - pendingCallOffset, AudioTrack.WRITE_NON_BLOCKING);
                            if (count < 0) throw new IllegalStateException("Call microphone injection stopped");
                            if (count > 0) { lastMicrophoneAt = now; pendingCallOffset += count; }
                        } else pendingCallOffset = pendingMicrophone.length;
                        if (pendingOffset == pendingMicrophone.length && pendingCallOffset == pendingMicrophone.length) pendingMicrophone = null;
                    }
                    if ((lastMicrophoneAt == 0 && now - microphoneStartedAt > 2000)
                            || (lastMicrophoneAt != 0 && now - lastMicrophoneAt > 750))
                        throw new IllegalStateException("Vehicle microphone stream timed out");
                }
                handler.postDelayed(this, 10);
            } catch (RuntimeException failure) { fail(failure.getClass().getSimpleName()); }
        }
    };
    private void fail(String reason) { stopRouting(); state = "차량 오디오 해제 · 폰 경로 복귀 (" + reason + ")"; }
    private void stopRouting() {
        session = null; microphoneWanted = false; handler.removeCallbacks(pump);
        hfpActive = callAttempted = recordingMicrophoneWanted = callMicrophoneWanted = false;
        stopCall();
        if (transport != null) try { transport.requestMicrophone(false); } catch (RuntimeException ignored) {}
        if (callbackRegistered) manager.unregisterAudioRecordingCallback(recordingCallback);
        callbackRegistered = false;
        for (AudioRecord record : outputs.values()) { try { record.stop(); } catch (IllegalStateException ignored) {} record.release(); }
        outputs.clear();
        if (microphoneTrack != null) { try { microphoneTrack.stop(); } catch (IllegalStateException ignored) {} microphoneTrack.release(); }
        microphoneTrack = null; microphoneMix = null; microphoneQueue.clear(); pendingMicrophone = null;
        if (policy != null) manager.unregisterAudioPolicy(policy);
        policy = null; transport = null; microphoneFrameBytes = 0; state = "오디오: 차량 연결 대기";
    }
    @Override public void close() { handler.post(() -> { stopRouting(); thread.quitSafely(); }); }
}
