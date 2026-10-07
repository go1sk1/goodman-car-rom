package kr.goodman.carbridge.transport;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Parses the HU advertisement; wire channel IDs are never assumed to match service kinds. */
public final class ServiceCatalog {
    public enum Kind { SENSOR, AV_OUTPUT, INPUT, AV_INPUT, BLUETOOTH, OTHER }
    public static final class Channel {
        public final int id, descriptorField;
        public final Kind kind;
        private final byte[] configuration;
        Channel(int id, int field, byte[] configuration) {
            this.id = id; descriptorField = field; this.configuration = configuration;
            kind = field == 2 ? Kind.SENSOR : field == 3 ? Kind.AV_OUTPUT : field == 4 ? Kind.INPUT
                    : field == 5 ? Kind.AV_INPUT : field == 6 ? Kind.BLUETOOTH : Kind.OTHER;
        }
        public byte[] configuration() { return configuration.clone(); }
    }
    public static final class Pcm {
        public final int configIndex, rate, channels;
        Pcm(int index, int rate, int channels) { configIndex = index; this.rate = rate; this.channels = channels; }
    }
    public static final class Video {
        public final int configIndex, width, height, densityDpi, displayId;
        Video(int index, int width, int height, int density, int displayId) {
            configIndex = index; this.width = width; this.height = height; densityDpi = density; this.displayId = displayId;
        }
    }
    private final Map<Integer, Channel> channels;
    private ServiceCatalog(Map<Integer, Channel> channels) { this.channels = Collections.unmodifiableMap(channels); }
    public Map<Integer, Channel> channels() { return channels; }
    public static ServiceCatalog decode(byte[] serviceDiscoveryResponse) throws IOException {
        Map<Integer, Channel> channels = new LinkedHashMap<>();
        for (ProtoFields.Field field : ProtoFields.parse(serviceDiscoveryResponse)) {
            if (field.number != 1) continue;
            if (channels.size() >= 64) throw new IOException("Too many vehicle services");
            List<ProtoFields.Field> descriptor = ProtoFields.parse(field.bytes());
            long id = ProtoFields.single(descriptor, 1, true).integer();
            if (id < 1 || id > 255 || channels.containsKey((int) id)) throw new IOException("Invalid/duplicate vehicle channel");
            ProtoFields.Field configuration = null;
            for (ProtoFields.Field member : descriptor) {
                if (member.number < 2 || member.number > 18) continue;
                if (configuration != null) throw new IOException("Ambiguous vehicle channel kind");
                configuration = member;
            }
            if (configuration == null) continue; // Future service kind, not one of this implementation's channels.
            channels.put((int) id, new Channel((int) id, configuration.number, configuration.bytes()));
        }
        return new ServiceCatalog(channels);
    }
    /** Retains config indices from the advertisement; only uncompressed PCM16 is supported. */
    public static List<Pcm> pcmConfigurations(Channel channel) throws IOException {
        if (channel.kind != Kind.AV_INPUT && channel.kind != Kind.AV_OUTPUT) return Collections.emptyList();
        List<ProtoFields.Field> fields = ProtoFields.parse(channel.configuration);
        if (ProtoFields.integer(fields, 1, 0) != 1) return Collections.emptyList();
        int configField = channel.kind == Kind.AV_INPUT ? 2 : 3, index = 0;
        List<Pcm> result = new ArrayList<>();
        for (ProtoFields.Field field : fields) if (field.number == configField) {
            List<ProtoFields.Field> config = ProtoFields.parse(field.bytes());
            long rate = ProtoFields.integer(config, 1, 0), bits = ProtoFields.integer(config, 2, 0);
            long count = ProtoFields.integer(config, 3, 0);
            if (rate >= 8000 && rate <= 48000 && bits == 16 && (count == 1 || count == 2))
                result.add(new Pcm(index, (int) rate, (int) count));
            if (++index > 64) throw new IOException("Too many audio configurations");
        }
        return Collections.unmodifiableList(result);
    }
    /** Raw advertised audio type; interpretation is version-dependent and not guessed here. */
    public static long audioType(Channel channel) throws IOException {
        if (channel.kind != Kind.AV_OUTPUT) throw new IOException("Not an AV output channel");
        return ProtoFields.integer(ProtoFields.parse(channel.configuration), 2, -1);
    }
    /** Legacy 1.1 profile only: baseline AVC at 30 fps, without unimplemented viewport margins. */
    public static List<Video> legacyVideoConfigurations(Channel channel) throws IOException {
        if (channel.kind != Kind.AV_OUTPUT) return Collections.emptyList();
        List<ProtoFields.Field> fields = ProtoFields.parse(channel.configuration);
        if (ProtoFields.integer(fields, 1, 0) != 3 || ProtoFields.integer(fields, 7, 0) != 0)
            return Collections.emptyList();
        long displayId = ProtoFields.integer(fields, 6, 0);
        if (displayId < 0 || displayId > Integer.MAX_VALUE) throw new IOException("Invalid logical display ID");
        List<Video> result = new ArrayList<>(); int index = 0;
        for (ProtoFields.Field field : fields) if (field.number == 4) {
            List<ProtoFields.Field> config = ProtoFields.parse(field.bytes());
            long resolution = ProtoFields.integer(config, 1, 0), fps = ProtoFields.integer(config, 2, 0);
            long density = ProtoFields.integer(config, 5, 160), codec = ProtoFields.integer(config, 10, 3);
            // aasdk's actual v1.1 enum uses 1=30 fps; some later reference tables disagree.
            if (fps == 1 && codec == 3 && density >= 120 && density <= 640
                    && ProtoFields.integer(config, 3, 0) == 0 && ProtoFields.integer(config, 4, 0) == 0) {
                int width = resolution == 1 ? 800 : resolution == 2 ? 1280 : resolution == 3 ? 1920 : 0;
                int height = resolution == 1 ? 480 : resolution == 2 ? 720 : resolution == 3 ? 1080 : 0;
                if (width != 0) result.add(new Video(index, width, height, (int) density, (int) displayId));
            }
            if (++index > 64) throw new IOException("Too many video configurations");
        }
        return Collections.unmodifiableList(result);
    }
    public static boolean inputMatchesVideo(Channel input, Video video) throws IOException {
        return input.kind == Kind.INPUT && ProtoFields.integer(ProtoFields.parse(input.configuration), 5, 0) == video.displayId;
    }
    public static boolean touchscreenMatchesVideo(Channel input, Video video) throws IOException {
        if (!inputMatchesVideo(input, video)) return false;
        for (ProtoFields.Field field : ProtoFields.parse(input.configuration)) if (field.number == 2) {
            List<ProtoFields.Field> screen = ProtoFields.parse(field.bytes());
            if (ProtoFields.integer(screen, 1, 0) == video.width && ProtoFields.integer(screen, 2, 0) == video.height)
                return true;
        }
        return false;
    }
}
