package io.stridehub.app.scale.core;

import org.junit.Test;
import static org.junit.Assert.*;
import static io.stridehub.app.scale.core.BroadcastMeasurementGate.Decision.*;

/** Synthetic advertisements reproduce ordering and packet loss, not physical-radio performance. */
public final class BroadcastMeasurementGateTest {
    private static final String ADDRESS = "11:22:33:44:55:66";
    private static final long NOW = 1_700_000_000_000L;
    private static byte[] packet(int session, int state, int grams) {
        byte[] data = ProtocolTest.bytes("4433221105521104031b5801f4b1");
        data[7] = (byte)session; data[8] = (byte)state;
        data[9] = (byte)(grams >>> 8); data[10] = (byte)grams;
        byte xor = 0; for (int i = 7; i < 13; i++) xor ^= data[i]; data[13] = xor;
        return data;
    }
    private static YunmaiProtocol.Frame frame(int session, int state, int weightHundredths) {
        return YunmaiProtocol.parseAdvertisement(0x5566, packet(session, state, weightHundredths), ADDRESS, true);
    }
    @Test public void emptyPlatformRetainsBroadcastIdentityWithoutProducingMeasurement() {
        byte[] zero = packet(4, 1, 0), idle = packet(4, 0, 0);
        assertTrue(YunmaiProtocol.isBroadcastAdvertisement(0x5566, zero, ADDRESS, true));
        assertTrue(YunmaiProtocol.isBroadcastAdvertisement(0x5566, idle, ADDRESS, true));
        assertNull(YunmaiProtocol.parseAdvertisement(0x5566, zero, ADDRESS, true));
        assertNull(YunmaiProtocol.parseAdvertisement(0x5566, idle, ADDRESS, true));
        assertTrue(YunmaiProtocol.isBroadcastName("YUNMAI-ADV-123"));
        assertFalse(YunmaiProtocol.isBroadcastName("YUNMAI-ISM"));
        assertFalse(YunmaiProtocol.isBroadcastName(null));
        assertFalse(YunmaiProtocol.isBroadcastAdvertisement(0x5567, zero, ADDRESS, true));
        assertFalse(YunmaiProtocol.isBroadcastAdvertisement(0x5566, zero, ADDRESS, false));
        zero[13] ^= 1;
        assertFalse(YunmaiProtocol.isBroadcastAdvertisement(0x5566, zero, ADDRESS, true));
    }
    @Test public void lateFinalRequiresReviewAndIsNotMarkedSavedByInspection() {
        BroadcastMeasurementGate gate = new BroadcastMeasurementGate();
        YunmaiProtocol.Frame stable = frame(4, 3, 7000);
        assertEquals(REVIEW, gate.inspect(stable, ADDRESS, NOW));
        assertEquals(REVIEW, gate.inspect(stable, ADDRESS, NOW + 50));
        gate.resetSession();
        assertEquals(REVIEW, gate.inspect(stable, ADDRESS, NOW + 100));
    }
    @Test public void liveAndFinalMustShareDeviceSessionAndRecentTime() {
        BroadcastMeasurementGate gate = new BroadcastMeasurementGate();
        assertEquals(LIVE, gate.inspect(frame(4, 2, 6990), ADDRESS, NOW));
        assertEquals(AUTO_SAVE, gate.inspect(frame(4, 3, 7000), ADDRESS, NOW + 1000));
        assertEquals(REVIEW, gate.inspect(frame(5, 3, 7000), ADDRESS, NOW + 1000));
        assertEquals(REVIEW, gate.inspect(frame(4, 3, 7000), "other-device", NOW + 1000));
        assertEquals(REVIEW, gate.inspect(frame(4, 3, 7000), ADDRESS, NOW + BroadcastMeasurementGate.LIVE_WINDOW_MS + 1));
        assertEquals(REVIEW, gate.inspect(frame(4, 3, 7000), ADDRESS, NOW - 1));
    }
    @Test public void resetAndDismissDoNotAuthorizeOldStablePackets() {
        BroadcastMeasurementGate gate = new BroadcastMeasurementGate();
        YunmaiProtocol.Frame stable = frame(4, 3, 7000);
        gate.inspect(frame(4, 2, 6990), ADDRESS, NOW);
        gate.resetSession();
        assertEquals(REVIEW, gate.inspect(stable, ADDRESS, NOW + 100));
        gate.dismiss(stable, ADDRESS);
        assertEquals(IGNORE, gate.inspect(stable, ADDRESS, NOW + 200));
        gate.inspect(frame(4, 2, 6990), ADDRESS, NOW + 210);
        assertEquals(IGNORE, gate.inspect(stable, ADDRESS, NOW + 220));
        assertEquals(REVIEW, gate.inspect(frame(5, 3, 7000), ADDRESS, NOW + 230));
        assertEquals(LIVE, gate.inspect(frame(5, 2, 6990), ADDRESS, NOW + 300));
        assertEquals(AUTO_SAVE, gate.inspect(frame(5, 3, 7000), ADDRESS, NOW + 400));
    }
    @Test public void savedBroadcastReplayIsBoundedAndSurvivesSessionRestart() {
        BroadcastMeasurementGate gate = new BroadcastMeasurementGate();
        YunmaiProtocol.Frame stable = frame(255, 3, 7000);
        String fingerprint = BroadcastMeasurementGate.fingerprint(stable, ADDRESS);
        gate.markSaved(fingerprint, NOW);
        gate.resetSession();
        assertEquals(DUPLICATE, gate.inspect(stable, ADDRESS, NOW + 100));
        gate.inspect(frame(255, 2, 7000), ADDRESS, NOW + 110);
        assertEquals(DUPLICATE, gate.inspect(stable, ADDRESS, NOW + 120));
        assertEquals(REVIEW, gate.inspect(frame(0, 3, 7000), ADDRESS, NOW + 130));
        assertEquals(REVIEW, gate.inspect(stable, ADDRESS, NOW + BroadcastMeasurementGate.REPLAY_WINDOW_MS + 1));
        BroadcastMeasurementGate restored = new BroadcastMeasurementGate();
        restored.markSaved(fingerprint, NOW);
        assertEquals(DUPLICATE, restored.inspect(stable, ADDRESS, NOW + 100));
    }
    @Test public void invalidOrNonBroadcastFramesCannotAuthorizeSaving() {
        BroadcastMeasurementGate gate = new BroadcastMeasurementGate();
        assertEquals(IGNORE, gate.inspect(null, ADDRESS, NOW));
        YunmaiProtocol.Frame gatt = ProtocolTest.decode(ProtocolTest.finalFrame(19));
        assertEquals(IGNORE, gate.inspect(gatt, ADDRESS, NOW));
        assertNull(BroadcastMeasurementGate.fingerprint(gatt, ADDRESS));
    }
}
