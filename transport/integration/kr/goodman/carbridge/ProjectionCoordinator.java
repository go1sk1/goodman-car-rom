package kr.goodman.carbridge;

import android.content.Context;
import android.media.AudioFormat;
import android.os.Handler;
import android.os.Looper;
import java.io.IOException;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.net.ssl.SSLContext;
import kr.goodman.carbridge.core.VehiclePolicy.Sound;
import kr.goodman.carbridge.transport.*;

/** Service-owned session. Connection, capture and routing resources share one lifetime. */
final class ProjectionCoordinator implements AutoCloseable {
    private final Context context;
    private final VehicleRuntime runtime;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Run current;
    private String state = "차량 연결 대기";
    ProjectionCoordinator(Context context, VehicleRuntime runtime) {
        requireMain(); this.context = context; this.runtime = runtime;
    }
    String snapshot() { requireMain(); return state; }
    void showVehicleScreen() {
        requireMain();
        if (current == null) { state = "먼저 차량에 연결하세요"; return; }
        current.requestScreen(false);
    }
    void showMirrorScreen() {
        requireMain();
        if (current == null) { state = "먼저 차량에 연결하세요"; return; }
        current.requestScreen(true);
    }

    /** Takes ownership of an explicitly selected/authorized link and its enrolled trust context. */
    void connect(ProjectionLink link, SSLContext trust) throws IOException {
        requireMain(); disconnect();
        try {
            Run next = new Run(link, trust); current = next;
            state = "차량 연결 협상 중"; next.session.start();
        } catch (IOException | RuntimeException error) {
            try { link.close(); } catch (IOException ignored) {}
            state = "차량 연결 시작 실패"; throw error;
        }
    }
    void disconnect() {
        requireMain();
        Run previous = current; current = null;
        if (previous != null) previous.stop();
        state = "차량 연결 대기";
    }
    @Override public void close() { disconnect(); }
    private interface Receiver { void receive(AaWire.Message message) throws IOException; }

    private final class Run implements AaSession.Listener, VehicleTransport {
        final AaSession session;
        volatile boolean finished;
        volatile Object token;
        volatile Map<Integer, Receiver> receivers = Collections.emptyMap();
        volatile Map<Sound, AudioOutput> outputs = Collections.emptyMap();
        volatile Set<Sound> available = Collections.emptySet();
        volatile MicrophoneChannel microphone;
        volatile AudioFormat microphoneFormat;
        volatile VehicleFocus focus;
        ProjectionVideoBridge video;
        final MirrorExperience experience;
        ProjectionInputBridge input;
        VehicleInputChannel inputChannel;
        VehicleFocus.AudioState audioFocus = VehicleFocus.AudioState.LOSS;
        volatile boolean duck;
        boolean refreshPosted, audioFocusRequested;
        boolean nativeRequested, screenRequestPending;
        final Runnable screenTimeout = () -> {
            if (!finished && current == this && screenRequestPending) {
                screenRequestPending = false;
                state = "차량 화면 전환 응답이 없습니다 · 차량 화면에서도 확인하세요";
            }
        };
        final Runnable refresh = () -> {
            refreshPosted = false;
            if (!finished && current == this && token != null) runtime.refreshTransport(token);
        };

        Run(ProjectionLink link, SSLContext trust) throws IOException {
            session = new AaSession(link, trust, this); experience = new MirrorExperience(context);
        }
        @Override public void services(ServiceCatalog catalog) {
            main.post(() -> {
                if (finished || current != this) return;
                try { configure(catalog); }
                catch (IOException | RuntimeException error) { fail("차량이 제공한 화면·오디오 형식을 연결하지 못했습니다"); }
            });
        }

        private void configure(ServiceCatalog catalog) throws IOException {
            ServiceCatalog.Channel videoChannel = null;
            ServiceCatalog.Video selected = null;
            // Prefer 720p or below for this first AVC implementation, preserving advertised indices.
            for (ServiceCatalog.Channel channel : catalog.channels().values())
                for (ServiceCatalog.Video candidate : ServiceCatalog.legacyVideoConfigurations(channel)) {
                    if (candidate.width > 1280) continue;
                    if (selected == null || candidate.width > selected.width) { selected = candidate; videoChannel = channel; }
                }
            if (selected == null) throw new IOException("No supported negotiated video configuration");
            final ServiceCatalog.Video format = selected;
            Map<Integer, Receiver> dispatch = new LinkedHashMap<>();
            EnumMap<Sound, AudioOutput> audio = new EnumMap<>(Sound.class);
            video = new ProjectionVideoBridge(context, session, videoChannel, format, 1,
                    reason -> fail(reason), () -> {
                        if (finished || nativeRequested) return;
                        try { focus.requestVideo(true); }
                        catch (IOException error) { fail("차량 화면 사용 요청 실패"); }
                    });
            dispatch.put(videoChannel.id, video::receive);
            focus = new VehicleFocus(session, videoChannel.id, format.displayId, new VehicleFocus.Listener() {
                @Override public void video(boolean visible, boolean inputAllowed) {
                    main.post(() -> {
                        if (finished || current != Run.this) return;
                        if (nativeRequested && visible) return; // Do not resume on a late projection grant.
                        video.setVisible(visible);
                        experience.setVisible(visible);
                        if (input != null) input.setTouchFocus(visible && inputAllowed);
                        if (visible != nativeRequested) {
                            screenRequestPending = false; main.removeCallbacks(screenTimeout);
                        }
                        if (nativeRequested && !visible) {
                            state = "차량 기본 화면 전환 응답 받음 · 통화 연결 유지"; return;
                        }
                        state = visible ? "차량 화면 전송 요청됨 · 실제 화면 확인 필요" : "차량 화면 사용 대기";
                    });
                }
                @Override public void audio(VehicleFocus.AudioState next) {
                    main.post(() -> {
                        if (finished || current != Run.this) return;
                        audioFocus = next; updateAvailability();
                    });
                }
            });

            for (ServiceCatalog.Channel channel : catalog.channels().values()) {
                if (channel.kind == ServiceCatalog.Kind.AV_OUTPUT && channel.id != videoChannel.id) {
                    Sound sound = legacyAudioType(ServiceCatalog.audioType(channel));
                    List<ServiceCatalog.Pcm> formats = ServiceCatalog.pcmConfigurations(channel);
                    if (sound == null || formats.isEmpty() || audio.containsKey(sound)) continue;
                    ServiceCatalog.Pcm pcm = formats.get(0);
                    AudioOutput output = new AudioOutput(channel, pcm, channel.id + 1);
                    audio.put(sound, output); dispatch.put(channel.id, output.channel::receive);
                } else if (channel.kind == ServiceCatalog.Kind.AV_INPUT && microphone == null) {
                    List<ServiceCatalog.Pcm> formats = ServiceCatalog.pcmConfigurations(channel);
                    if (formats.size() != 1) continue;
                    microphoneFormat = pcmFormat(formats.get(0));
                    microphone = new MicrophoneChannel(session, channel,
                            MicrophoneChannel.ResponseLayout.LEGACY_SESSION_FIRST, new MicrophoneChannel.Listener() {
                        @Override public boolean pcm(byte[] pcm, long timestamp) {
                            Object active = token;
                            return !finished && active != null && runtime.onMicrophone(active, pcm);
                        }
                        @Override public void ready() { main.post(() -> capabilitiesChanged()); }
                        @Override public void unavailable(String reason) { main.post(() -> {
                            if (finished) return;
                            state = "차량 마이크 사용 중단 · 폰 경로 확인 필요"; capabilitiesChanged();
                        }); }
                    });
                    dispatch.put(channel.id, microphone::receive);
                }
            }
            outputs = Collections.unmodifiableMap(audio);
            token = runtime.openSession(this);
            for (ServiceCatalog.Channel channel : catalog.channels().values()) {
                if (input != null || channel.kind != ServiceCatalog.Kind.INPUT
                        || !ServiceCatalog.inputMatchesVideo(channel, format)) continue;
                boolean touchSupported = ServiceCatalog.touchscreenMatchesVideo(channel, format);
                input = new ProjectionInputBridge(context, runtime, token, format.width, format.height,
                        legacyKeys(), reason -> { if (!finished) state = reason; });
                ProjectionInputBridge bridge = input;
                inputChannel = new VehicleInputChannel(session, channel, new VehicleInputChannel.Listener() {
                    @Override public void buttons(List<ButtonDecoder.Button> buttons) { bridge.buttons(buttons); }
                    @Override public void inputEvent(byte[] event) throws IOException {
                        if (touchSupported) bridge.inputEvent(event);
                    }
                    @Override public void unavailable(String reason) { bridge.unavailable(reason); }
                });
                dispatch.put(channel.id, inputChannel::receive);
            }
            // Publish fully constructed dispatch and endpoints before opening any channel.
            receivers = Collections.unmodifiableMap(dispatch);
            video.open();
            for (AudioOutput output : audio.values()) output.channel.open();
            if (microphone != null) microphone.open();
            if (inputChannel != null) inputChannel.open();
            state = "차량 채널 연결 중 · GPS는 폰 사용";
        }

        private final class AudioOutput {
            final AudioFormat format;
            final AvOutput channel;
            AudioOutput(ServiceCatalog.Channel descriptor, ServiceCatalog.Pcm pcm, int streamId) {
                format = pcmFormat(pcm);
                channel = new AvOutput(session, descriptor, 1, pcm.configIndex, streamId, new AvOutput.Listener() {
                    @Override public void active() { main.post(() -> {
                        if (finished) return;
                        if (!audioFocusRequested) {
                            audioFocusRequested = true;
                            try { focus.requestAudio(VehicleFocus.AudioRequest.GAIN); }
                            catch (IOException error) { fail("차량 오디오 사용 요청 실패"); return; }
                        }
                        updateAvailability();
                    }); }
                    @Override public void unavailable(String reason) { main.post(() -> {
                        if (finished) return;
                        state = "차량 오디오 채널 사용 불가"; updateAvailability();
                    }); }
                });
            }
        }
        private void updateAvailability() {
            if (finished) return;
            EnumSet<Sound> next = EnumSet.noneOf(Sound.class);
            duck = audioFocus == VehicleFocus.AudioState.MAY_DUCK;
            for (Map.Entry<Sound, AudioOutput> entry : outputs.entrySet()) {
                boolean grant = audioFocus == VehicleFocus.AudioState.GAIN
                        || audioFocus == VehicleFocus.AudioState.TRANSIENT || duck
                        || (audioFocus == VehicleFocus.AudioState.MEDIA_ONLY && entry.getKey() == Sound.MEDIA)
                        || (audioFocus == VehicleFocus.AudioState.GUIDANCE_ONLY && entry.getKey() == Sound.GUIDANCE);
                boolean active = grant && entry.getValue().channel.state() == AvOutput.State.ACTIVE;
                entry.getValue().channel.setFocused(active);
                if (active) next.add(entry.getKey());
            }
            experience.setAudioReady(next.contains(Sound.MEDIA));
            if (!next.equals(available)) { available = Collections.unmodifiableSet(next); capabilitiesChanged(); }
        }
        private void capabilitiesChanged() {
            if (finished || refreshPosted || token == null) return;
            refreshPosted = true; main.postDelayed(refresh, 100);
        }
        private void requestScreen(boolean projected) {
            requireMain();
            if (finished || focus == null || video == null || session.state() != AaSession.State.READY) {
                state = "차량 화면 연결이 준비되지 않았습니다"; return;
            }
            nativeRequested = !projected;
            main.removeCallbacks(screenTimeout); screenRequestPending = true;
            if (!projected) {
                if (input != null) input.setTouchFocus(false);
                video.setVisible(false);
                experience.setVisible(false);
            }
            try {
                // Only video focus changes. Keep the USB session, HFP and negotiated audio alive.
                focus.requestVideo(projected);
                state = projected ? "미러링 화면 복귀 요청 중" : "ccNC 기본 화면 전환 요청 중";
                main.postDelayed(screenTimeout, 3000);
            } catch (IOException error) {
                screenRequestPending = false;
                state = "차량 화면 전환 요청을 보내지 못했습니다";
            }
        }
        @Override public void message(AaWire.Message message) throws IOException {
            if (finished) return;
            VehicleFocus currentFocus = focus;
            if (currentFocus != null) currentFocus.receive(message);
            Receiver receiver = receivers.get(message.channel);
            if (receiver != null) receiver.receive(message);
        }
        @Override public void closed(IOException reason) {
            main.post(() -> {
                if (finished || current != this) return;
                fail(reason == null ? "차량 연결이 종료됐습니다" : "차량 연결 오류 · 케이블과 차량 연결 상태를 확인하세요");
            });
        }
        private void fail(String message) {
            requireMain(); if (finished) return;
            stop(); if (current == this) { current = null; state = message; }
        }
        void stop() {
            requireMain(); if (finished) return;
            finished = true; main.removeCallbacks(refresh); main.removeCallbacks(screenTimeout);
            experience.close();
            available = Collections.emptySet(); receivers = Collections.emptyMap();
            // Cancel injected input before invalidating the runtime token.
            if (input != null) input.close();
            if (inputChannel != null) inputChannel.close();
            if (video != null) video.close();
            for (AudioOutput output : outputs.values()) output.channel.close();
            if (microphone != null) microphone.close();
            if (focus != null) focus.close();
            if (token != null) runtime.disconnect(token);
            token = null; session.close();
        }
        @Override public Set<Sound> outputChannels() { return available; }
        @Override public AudioFormat outputFormat(Sound sound) {
            AudioOutput output = outputs.get(sound); return output == null ? null : output.format;
        }
        @Override public boolean hasMicrophone() {
            MicrophoneChannel mic = microphone; return !finished && mic != null && mic.ready();
        }
        @Override public AudioFormat microphoneFormat() { return microphoneFormat; }
        @Override public boolean offerAudio(Sound sound, byte[] pcm, long elapsedRealtimeNanos) {
            AudioOutput output = outputs.get(sound);
            if (finished || !available.contains(sound) || output == null || pcm == null
                    || pcm.length == 0 || pcm.length % (2 * output.format.getChannelCount()) != 0) return false;
            if (duck) {
                pcm = pcm.clone();
                for (int i = 0; i < pcm.length; i += 2) {
                    short sample = (short) ((pcm[i] & 255) | (pcm[i + 1] << 8));
                    sample = (short) (sample / 5); pcm[i] = (byte) sample; pcm[i + 1] = (byte) (sample >> 8);
                }
            }
            return output.channel.offer(pcm, elapsedRealtimeNanos / 1000);
        }
        @Override public void requestMicrophone(boolean active) {
            MicrophoneChannel mic = microphone;
            if (finished || mic == null) return;
            try { mic.request(active); }
            catch (IOException error) { main.post(() -> fail("차량 마이크 제어 실패")); }
        }
    }

    private static AudioFormat pcmFormat(ServiceCatalog.Pcm pcm) {
        return new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(pcm.rate).setChannelMask(pcm.channels == 1
                        ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO).build();
    }
    private static Sound legacyAudioType(long type) {
        // aasdk AudioType enum: SPEECH=1, SYSTEM=2, MEDIA=3, ALARM=4. ALARM is NOT a call channel.
        return type == 1 ? Sound.GUIDANCE : type == 2 ? Sound.SYSTEM : type == 3 ? Sound.MEDIA : null;
    }
    private static Map<Integer, VehicleRuntime.Key> legacyKeys() {
        Map<Integer, VehicleRuntime.Key> keys = new HashMap<>();
        keys.put(5, VehicleRuntime.Key.CALL); keys.put(79, VehicleRuntime.Key.CALL);
        keys.put(6, VehicleRuntime.Key.END_CALL);
        keys.put(219, VehicleRuntime.Key.VOICE); keys.put(231, VehicleRuntime.Key.VOICE);
        return keys;
    }
    private static void requireMain() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Projection control requires main thread");
    }
}
