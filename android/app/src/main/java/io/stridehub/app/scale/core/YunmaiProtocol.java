package io.stridehub.app.scale.core;

import java.util.*;

/** Protocol facts recovered from the supplied APK; see docs/apk-analysis.md for evidence. */
public final class YunmaiProtocol {
    private YunmaiProtocol() { }
    public static final class Frame {
        public double weightKg;
        public int impedance, userId, version, state, session;
        public Double bodyFatPercent;
        public boolean finalMeasurement, offline, broadcast;
        public long deviceTimestamp;
        public byte[] raw;
    }

    /** Notifications are a byte stream: one callback may contain a fragment or several packets. */
    public static final class Decoder {
        private byte[] pending = new byte[0];
        private int rejected;
        public void reset() { pending = new byte[0]; rejected = 0; }
        public int rejectedCount() { return rejected; }
        public List<Frame> accept(byte[] bytes) {
            List<Frame> result = new ArrayList<>();
            if (bytes == null || bytes.length == 0) return result;
            if (bytes.length > 4096 || pending.length + bytes.length > 8192) {
                pending = new byte[0]; rejected++; return result;
            }
            byte[] input = Arrays.copyOf(pending, pending.length + bytes.length);
            System.arraycopy(bytes, 0, input, pending.length, bytes.length);
            int position = 0;
            while (position < input.length) {
                if (u(input[position]) != 0x0d) { position++; continue; }
                if (input.length - position < 3) break;
                int size = u(input[position + 2]);
                if (size < 5 || size > 128) { rejected++; position++; continue; }
                if (input.length - position < size) break;
                byte[] packet = Arrays.copyOfRange(input, position, position + size);
                if (checksum(packet, 1, size - 1) != u(packet[size - 1])) {
                    rejected++; position++; continue;
                }
                int kind = u(packet[3]);
                Frame frame = parsePacket(packet);
                if (frame != null) result.add(frame);
                else if (kind == 1 || kind == 2) rejected++;
                position += size;
            }
            pending = Arrays.copyOfRange(input, position, input.length);
            return result;
        }
    }
    private static Frame parsePacket(byte[] data) {
        int version = u(data[1]), kind = u(data[3]);
        // Beyond the inspected protocol family, don't guess at offsets.
        if (version < 1 || version > 35 || (kind != 1 && kind != 2)) return null;
        Frame frame = new Frame(); frame.version = version; frame.raw = data;
        frame.finalMeasurement = kind == 2;
        if (kind == 1) {
            if (data.length < 11) return null;
            frame.weightKg = be16(data, 8) / 100.0;
        } else {
            int minimum = version == 35 ? 25 : version >= 30 ? 20 : 18;
            if (data.length < minimum) return null;
            frame.offline = u(data[4]) != 0;
            long timestamp = be32(data, 5) * 1000L;
            // Older scales may carry an unset clock. Preserve it only when plausible.
            frame.deviceTimestamp = timestamp >= 946684800000L ? timestamp : 0L;
            frame.userId = (int) be32(data, 9);
            frame.weightKg = be16(data, 13) / 100.0;
            frame.impedance = be16(data, 15);
            if (version == 35) {
                int primary = be16(data, 20);
                frame.impedance = primary > 0 ? primary : be16(data, 22);
            }
            if (!validImpedance(frame.impedance)) frame.impedance = 0;
            if (version >= 30 && frame.impedance > 0) {
                double fat = be16(data, 17) / 100.0;
                if (fat >= 5 && fat <= 75) frame.bodyFatPercent = fat;
            }
        }
        return validWeight(frame.weightKg) ? frame : null;
    }
    public static Frame parseAdvertisement(int manufacturerId, byte[] payload, String address, boolean hasYunmaiService) {
        // These fields are product/type metadata, not a fixed three-byte signature.
        // The supplied APK uses one layout for these known broadcast products.
        if (payload == null || payload.length != 14 || address == null) return null;
        int product = u(payload[4]);
        boolean knownProduct = product == 2 || product == 5 || product == 11;
        // Preserve the previously supported X format when its service is omitted.
        boolean legacyX = product == 11 && u(payload[5]) == 0x74 && u(payload[6]) == 0x17;
        if (!(hasYunmaiService && knownProduct) && !legacyX) return null;
        if (checksum(payload, 7, 13) != u(payload[13])) return null;
        String[] mac = address.split(":");
        if (mac.length != 6) return null;
        try {
            int[] octets = new int[6];
            for (int i=0;i<6;i++) { if (mac[i].length()!=2) return null; octets[i]=Integer.parseInt(mac[i],16); }
            if (manufacturerId != ((octets[4]<<8)|octets[5])) return null;
            for (int i=0;i<4;i++) if (u(payload[i]) != octets[3-i]) return null;
        } catch (NumberFormatException e) { return null; }
        int state = u(payload[8]);
        if (state < 1 || state > 3) return null;
        Frame frame = new Frame(); frame.broadcast = true; frame.raw = payload.clone();
        frame.state = state; frame.session = u(payload[7]); frame.finalMeasurement = state == 3;
        frame.weightKg = be16(payload, 9) / 100.0;
        frame.impedance = be16(payload, 11);
        if (!validImpedance(frame.impedance)) frame.impedance = 0;
        return validWeight(frame.weightKg) ? frame : null;
    }
    public static byte[] userCommand(Profile profile, long now) {
        String error = profile == null ? "请填写身体资料" : profile.validationError(now);
        if (error != null) throw new IllegalArgumentException(error);
        byte[] bytes = new byte[18];
        bytes[0]=13; bytes[1]=18; bytes[2]=16; bytes[3]=3; // Add/select this local user.
        put32(bytes,4,profile.userId); bytes[8]=(byte)profile.heightCm;
        bytes[9]=(byte)(profile.male?1:2); bytes[10]=(byte)profile.ageAt(now);
        bytes[11]=85; bytes[12]=90; // Original protocol's default waist/bust; not stored as real user metrics.
        bytes[15]=1; bytes[16]=3; // kg; ordinary body type, as in original app.
        return finish(bytes);
    }
    public static byte[] timeCommand(long now, int utcOffsetSeconds) {
        if (now < 0 || Math.abs((long)utcOffsetSeconds)>86_400L) throw new IllegalArgumentException("时间参数无效");
        byte[] bytes = new byte[13]; bytes[0]=13;bytes[1]=13;bytes[2]=17;
        put32(bytes,3,now/1000);bytes[7]=20;bytes[8]=(byte)(utcOffsetSeconds<0?0x80:0);
        int offset=Math.abs(utcOffsetSeconds);bytes[9]=(byte)(offset>>>16);bytes[10]=(byte)(offset>>>8);bytes[11]=(byte)offset;
        return finish(bytes);
    }
    public static byte[] versionCommand() { return new byte[]{13,5,19,0,22}; }
    public static byte[] timeQueryCommand() { return new byte[]{13,4,23,19}; }
    public static byte[] acknowledgmentCommand() { return new byte[]{13,4,20,16}; }
    public static boolean isScaleName(String name) {
        if (name == null) return false;
        String upper=name.toUpperCase(Locale.ROOT);
        return upper.startsWith("YUNMAI-SIGNAL") || upper.startsWith("YUNMAI-IS")
                || upper.startsWith("YUNMAI-ADV") || upper.startsWith("YUNMAI-SCALE")
                || upper.startsWith("YUNMAI-TZC") || upper.startsWith("ALIBABA-M1502");
    }
    /** The device timestamp in raw makes a later same-weight measurement a different event. */
    public static String replayFingerprint(Frame frame, String address) {
        if (frame.broadcast || !frame.finalMeasurement || frame.deviceTimestamp <= 0 || frame.raw == null) return null;
        return address + ":" + hex(frame.raw);
    }
    public static Measurement toMeasurement(Frame frame, Profile profile, String address, long now) {
        if (!frame.finalMeasurement) throw new IllegalArgumentException("测量尚未完成");
        Double fat = frame.bodyFatPercent;
        String source = fat == null ? "蓝牙体重" : "秤内体脂";
        if (fat == null && (frame.broadcast || frame.version < 30) && validImpedance(frame.impedance)
                && profile != null && profile.validationError(now) == null) {
            fat = estimateFat(frame.weightKg, frame.impedance, profile, now, frame.broadcast);
            if (fat != null) source = "本机估算";
        }
        return Measurement.create(profile, now, frame.weightKg, fat, frame.impedance, source, address, hex(frame.raw));
    }
    /** Returns an optional estimate without modifying the saved measurement or its original fat. */
    public static Double estimateHistoricalFat(Measurement measurement, Profile profile, long calculationWhen) {
        if (measurement == null || measurement.bodyFatPercent != null || !validWeight(measurement.weightKg)
                || !validImpedance(measurement.impedance) || profile == null || calculationWhen <= 0
                || profile.validationError(calculationWhen) != null
                || !("蓝牙体重".equals(measurement.source) || "本机估算".equals(measurement.source))) return null;
        String hex = measurement.rawPacket;
        if (hex == null || hex.isEmpty() || (hex.length() & 1) != 0 || hex.length() > 256) return null;
        byte[] raw = new byte[hex.length() / 2];
        for (int i = 0; i < raw.length; i++) {
            int high = Character.digit(hex.charAt(i * 2), 16), low = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) return null;
            raw[i] = (byte)((high << 4) | low);
        }
        Frame frame;
        if (raw.length == 14) {
            if (measurement.deviceAddress == null) return null;
            String[] mac = measurement.deviceAddress.split(":");
            if (mac.length != 6) return null;
            try {
                int manufacturerId = (Integer.parseInt(mac[4], 16) << 8) | Integer.parseInt(mac[5], 16);
                frame = parseAdvertisement(manufacturerId, raw, measurement.deviceAddress, true);
            } catch (NumberFormatException e) { return null; }
        } else {
            Decoder decoder = new Decoder(); List<Frame> frames = decoder.accept(raw);
            if (frames.size() != 1 || decoder.rejectedCount() != 0) return null;
            frame = frames.get(0);
            if (frame.version >= 30 || !Arrays.equals(frame.raw, raw)) return null;
        }
        if (frame == null || !frame.finalMeasurement || frame.impedance != measurement.impedance
                || Double.compare(frame.weightKg, measurement.weightKg) != 0) return null;
        return estimateFat(measurement.weightKg, measurement.impedance, profile, calculationWhen, frame.broadcast);
    }
    /** Reconstructed from libconfig.so getFat's default zh/mode0 path; no proprietary code is loaded. */
    private static Double estimateFat(double weight, int impedance, Profile profile, long now, boolean broadcast) {
        float height = profile.heightCm / 100f;
        float correction;
        if (broadcast) {
            float delta = impedance - 500f;
            // The native path uses a float 1/3 exponent, not an exact cube root.
            correction = (float)Math.pow(Math.abs(delta) / 50f, 1f / 3f);
            if (delta < 0f) correction = -correction;
        } else {
            float resistance = (impedance - 100f) / 100f;
            correction = resistance > 1f ? (float)Math.sqrt(resistance) : resistance;
        }
        float base = 1.5f * (float)weight / height / height;
        // Preserve the native fmadd's single float rounding without a newer Android API.
        float value = (float)((double)0.08f * profile.ageAt(now) + base);
        value = value - (profile.male ? 10.8f : 0f) - 7.4f + correction;
        if (!Float.isFinite(value)) return null;
        int tenths = (int)(value * 10f);
        // Native formFat truncates first; e.g. an intermediate 75.09 yields valid 75.0.
        if (tenths < 50 || tenths > 750) return null;
        return tenths / 10.0;
    }
    private static boolean validWeight(double value) { return Double.isFinite(value) && value >= 2 && value <= 300; }
    private static boolean validImpedance(int value) { return value > 0 && value < 65535; }
    private static int u(byte value) { return value & 255; }
    private static int be16(byte[] bytes, int start) { return (u(bytes[start])<<8)|u(bytes[start+1]); }
    private static long be32(byte[] bytes, int start) {
        return ((long)u(bytes[start])<<24)|((long)u(bytes[start+1])<<16)|((long)u(bytes[start+2])<<8)|u(bytes[start+3]);
    }
    private static void put32(byte[] bytes,int start,long value) {
        for(int i=0;i<4;i++)bytes[start+i]=(byte)(value>>>(24-i*8));
    }
    private static int checksum(byte[] bytes,int start,int end) {
        int value=0;for(int i=start;i<end;i++)value^=u(bytes[i]);return value;
    }
    private static byte[] finish(byte[] bytes) { bytes[bytes.length-1]=(byte)checksum(bytes,1,bytes.length-1);return bytes; }
    public static String hex(byte[] bytes) {
        if(bytes==null)return "";StringBuilder out=new StringBuilder(bytes.length*2);
        for(byte b:bytes)out.append(String.format(Locale.ROOT,"%02X",u(b)));return out.toString();
    }
}
