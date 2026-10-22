package io.stridehub.app.scale.core;

import java.util.LinkedHashMap;
import java.util.Map;

/** Session evidence and bounded replay protection for broadcasts without a trustworthy timestamp. */
public final class BroadcastMeasurementGate {
    public enum Decision { LIVE, AUTO_SAVE, REVIEW, DUPLICATE, IGNORE }
    public static final long LIVE_WINDOW_MS = 30_000;
    public static final long REPLAY_WINDOW_MS = 10 * 60_000;
    private final Map<String, Long> saved = new LinkedHashMap<>();
    private String liveAddress;
    private int liveSession = -1;
    private long liveAt;
    private String dismissed;

    public void resetSession() { liveAddress = null; liveSession = -1; liveAt = 0; dismissed = null; }
    public Decision inspect(YunmaiProtocol.Frame frame, String address, long now) {
        if (frame == null || !frame.broadcast || address == null || frame.raw == null) return Decision.IGNORE;
        if (!frame.finalMeasurement) {
            liveAddress = address; liveSession = frame.session; liveAt = now;
            return Decision.LIVE;
        }
        String key = fingerprint(frame, address);
        Long savedAt = saved.get(key);
        if (savedAt != null && now >= savedAt && now - savedAt <= REPLAY_WINDOW_MS) return Decision.DUPLICATE;
        if (key.equals(dismissed)) return Decision.IGNORE;
        boolean witnessed = address.equals(liveAddress) && frame.session == liveSession
                && now >= liveAt && now - liveAt <= LIVE_WINDOW_MS;
        return witnessed ? Decision.AUTO_SAVE : Decision.REVIEW;
    }
    public void dismiss(YunmaiProtocol.Frame frame, String address) {
        dismissed = fingerprint(frame, address); liveAddress = null; liveSession = -1;
    }
    /** Only call after the application has successfully persisted the measurement. */
    public void markSaved(String fingerprint, long when) {
        if (fingerprint == null) return;
        saved.put(fingerprint, when);
        while (saved.size() > 32) saved.remove(saved.keySet().iterator().next());
    }
    public static String fingerprint(YunmaiProtocol.Frame frame, String address) {
        return frame == null || !frame.broadcast || !frame.finalMeasurement || frame.raw == null
                || address == null ? null : address + ":" + YunmaiProtocol.hex(frame.raw);
    }
}
