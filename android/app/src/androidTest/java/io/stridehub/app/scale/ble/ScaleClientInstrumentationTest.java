package io.stridehub.app.scale.ble;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.Parcel;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import io.stridehub.app.scale.core.Measurement;
import io.stridehub.app.scale.core.Profile;
import io.stridehub.app.scale.core.YunmaiProtocol;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Exercises Android callbacks and parsed ScanRecords without starting a real scan or connection. */
@SuppressWarnings("deprecation")
public final class ScaleClientInstrumentationTest extends InstrumentationTestCase {
    private static final String ADDRESS = "11:22:33:44:55:66";
    private ScaleClient client;
    private Probe probe;
    private Context context;
    private static final class Probe implements ScaleClient.Listener {
        final List<String> events = new ArrayList<>();
        int saved, candidates;
        Measurement candidate, result;
        ScaleClient.Device device;
        public void onStatus(String message) { }
        public void onDeviceFound(ScaleClient.Device value) { device = value; }
        public void onLiveWeight(double weight) { }
        public void onMeasurement(Measurement value) { saved++; result = value; }
        public void onError(String message) { events.add("error"); }
        public void onStateChanged(ScaleClient.State state, String message) { events.add(state.name()); }
        public void onConfirmationRequired(Measurement value, String explanation) {
            candidates++; candidate = value; events.add("candidate");
        }
    }
    @Override protected void setUp() throws Exception {
        super.setUp();
        context = new ContextWrapper(getInstrumentation().getTargetContext()) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return super.getSharedPreferences("ble_replay_instrumentation_only", mode);
            }
        };
        context.getSharedPreferences("ble_replay", Context.MODE_PRIVATE).edit().clear().commit();
        run(() -> { probe = new Probe(); client = new ScaleClient(context, probe); });
    }
    @Override protected void tearDown() throws Exception {
        if (client != null) run(() -> client.stop());
        context.getSharedPreferences("ble_replay", Context.MODE_PRIVATE).edit().clear().commit();
        super.tearDown();
    }
    private void prepare(boolean selected) {
        set("busy", true); set("scanActive", true);
        set("profile", new Profile(175, "1990-01-01", true, 42));
        set("preferred", ADDRESS); set("preferredType", "broadcast");
        set("state", selected ? ScaleClient.State.READY : ScaleClient.State.SEARCHING);
        if (selected) set("selected", new ScaleClient.Device("Test scale", ADDRESS, -48, true));
    }
    public void testLateStableResultRequiresConfirmationAndDeliversOneSnapshot() {
        run(() -> {
            prepare(true);
            broadcast(frame(4, 3, 7000));
            assertEquals(ScaleClient.State.REVIEW, client.getState());
            assertEquals(0, probe.saved); assertEquals(1, probe.candidates);
            assertTrue(probe.events.indexOf("REVIEW") < probe.events.indexOf("candidate"));
            Measurement snapshot = probe.candidate;
            broadcast(frame(5, 3, 7100));
            assertEquals("Pending confirmation must not silently change its value", 1, probe.candidates);
            client.confirmPendingMeasurement(); client.confirmPendingMeasurement();
            assertEquals(1, probe.saved); assertSame(snapshot, probe.result);
            assertEquals(70.0, probe.result.weightKg, 0.001);
            client.confirmSaved();
            assertEquals(ScaleClient.State.SAVED, client.getState()); assertFalse(client.isBusy());
        });
    }
    public void testStopCancelsCandidateAndCannotSaveLater() {
        run(() -> {
            prepare(true); broadcast(frame(4, 3, 7000)); client.stop();
            client.confirmPendingMeasurement();
            assertEquals(0, probe.saved); assertNull(read("pendingMeasurement"));
            assertEquals(ScaleClient.State.IDLE, client.getState()); assertFalse(client.isBusy());
        });
    }
    public void testDismissedResultDoesNotReturnAndNewSessionCanSave() {
        run(() -> {
            prepare(true); broadcast(frame(4, 3, 7000)); client.discardPendingMeasurement();
            broadcast(frame(4, 2, 7000)); broadcast(frame(4, 3, 7000));
            assertEquals(0, probe.saved); assertEquals(1, probe.candidates);
            broadcast(frame(5, 2, 7100)); broadcast(frame(5, 3, 7100));
            assertEquals(1, probe.saved); assertEquals(71.0, probe.result.weightKg, 0.001);
        });
    }
    public void testTwentySecondHintKeepsWaitingAndNinetySecondTimeoutStops() {
        run(() -> {
            prepare(false);
            ((Runnable)read("scanHint")).run();
            assertTrue(client.isBusy()); assertTrue((Boolean)read("scanActive"));
            assertEquals(ScaleClient.State.SEARCHING, client.getState());
            ((Runnable)read("scanTimeout")).run();
            assertFalse(client.isBusy()); assertFalse((Boolean)read("scanActive"));
            assertEquals(ScaleClient.State.ERROR, client.getState());
        });
    }
    public void testConnectionRetriesAreBoundedAndStopInvalidatesQueuedRetry() {
        run(() -> {
            prepare(true); set("selected", new ScaleClient.Device("Test GATT scale", ADDRESS, -48, false));
            invoke("retryConnection", new Class<?>[]{String.class}, "Synthetic disconnection");
            Runnable pending = (Runnable)read("reconnectTask");
            assertEquals(1, read("connectionRetries")); assertEquals(ScaleClient.State.CONNECTING, client.getState());
            client.stop(); pending.run();
            assertNull(read("gatt")); assertFalse(client.isBusy());
            prepare(true); set("selected", new ScaleClient.Device("Test GATT scale", ADDRESS, -48, false));
            set("connectionRetries", 0);
            invoke("retryConnection", new Class<?>[]{String.class}, "Synthetic disconnection");
            invoke("retryConnection", new Class<?>[]{String.class}, "Synthetic disconnection");
            assertEquals(2, read("connectionRetries")); assertTrue(client.isBusy());
            invoke("retryConnection", new Class<?>[]{String.class}, "Synthetic disconnection");
            assertEquals(ScaleClient.State.ERROR, client.getState()); assertFalse(client.isBusy());
        });
    }
    public void testEmptyScaleScanRecordUsesBroadcastAndCachedFinalCanBeSelected() {
        run(() -> {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            assertNotNull("This ScanRecord case needs the Android Bluetooth service, not a physical peripheral", adapter);
            BluetoothDevice device = adapter.getRemoteDevice(ADDRESS);
            prepare(false);
            invoke("handleScan", new Class<?>[]{ScanResult.class}, scan(device, packet(4, 0, 0)));
            assertNotNull(probe.device); assertTrue(probe.device.broadcast);
            assertEquals(ScaleClient.State.READY, client.getState());
            assertTrue((Boolean)read("scanActive")); assertNull(read("gatt")); assertEquals(0, probe.saved);
            client.stop(); prepare(false); set("preferred", "");
            invoke("handleScan", new Class<?>[]{ScanResult.class}, scan(device, packet(5, 3, 7100)));
            assertEquals(0, probe.candidates);
            client.connect(probe.device, new Profile(175, "1990-01-01", true, 42));
            assertEquals(ScaleClient.State.REVIEW, client.getState()); assertEquals(1, probe.candidates);
            assertEquals(71.0, probe.candidate.weightKg, 0.001);
        });
    }
    public void testOnlySuccessfulPersistenceProtectsAgainstReplayInANewClient() {
        run(() -> {
            prepare(true); broadcast(frame(4, 3, 7000)); client.confirmPendingMeasurement();
            client.stop();
            probe = new Probe(); client = new ScaleClient(context, probe); prepare(true);
            broadcast(frame(4, 3, 7000));
            assertEquals("A failed save must remain available for retry", 1, probe.candidates);
            client.confirmPendingMeasurement(); client.confirmSaved();
            probe = new Probe(); client = new ScaleClient(context, probe); prepare(true);
            broadcast(frame(4, 3, 7000));
            assertEquals(0, probe.candidates); assertEquals(0, probe.saved);
            assertEquals(ScaleClient.State.READY, client.getState());
        });
    }
    private void run(Runnable action) {
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        getInstrumentation().runOnMainSync(() -> { try { action.run(); } catch (Throwable error) { failure.set(error); } });
        if (failure.get() != null) throw new AssertionError(failure.get());
    }
    private void broadcast(YunmaiProtocol.Frame frame) {
        invoke("handleBroadcast", new Class<?>[]{YunmaiProtocol.Frame.class, long.class}, frame, System.currentTimeMillis());
    }
    private static YunmaiProtocol.Frame frame(int session, int state, int hundredths) {
        return YunmaiProtocol.parseAdvertisement(0x5566, packet(session, state, hundredths), ADDRESS, true);
    }
    private static byte[] packet(int session, int state, int hundredths) {
        byte[] data = new byte[]{0x44,0x33,0x22,0x11,5,0x52,0x11,(byte)session,(byte)state,
                (byte)(hundredths >>> 8),(byte)hundredths,1,(byte)0xf4,0};
        byte xor = 0; for (int i = 7; i < 13; i++) xor ^= data[i]; data[13] = xor; return data;
    }
    private static ScanResult scan(BluetoothDevice device, byte[] payload) {
        // Include a local name so the receive path never queries a real device name/service.
        byte[] name = new byte[]{'Y','U','N','M','A','I','-','A','D','V'};
        byte[] advertisement = new byte[4 + name.length + 2 + payload.length + 4];
        int i = 0;
        advertisement[i++] = 3; advertisement[i++] = 3; advertisement[i++] = 0x20; advertisement[i++] = 0x13;
        advertisement[i++] = (byte)(name.length + 1); advertisement[i++] = 9;
        System.arraycopy(name, 0, advertisement, i, name.length); i += name.length;
        advertisement[i++] = (byte)(payload.length + 3); advertisement[i++] = (byte)0xff;
        advertisement[i++] = 0x66; advertisement[i++] = 0x55;
        System.arraycopy(payload, 0, advertisement, i, payload.length);
        Parcel source = Parcel.obtain(), filled = Parcel.obtain();
        try {
            new ScanResult(device, null, -48, SystemClock.elapsedRealtimeNanos()).writeToParcel(source, 0);
            source.setDataPosition(0);
            if (source.readInt() != 0) BluetoothDevice.CREATOR.createFromParcel(source);
            int recordOffset = source.dataPosition();
            if (source.readInt() != 0) throw new AssertionError("Expected empty ScanRecord fixture");
            int rest = source.dataPosition();
            filled.appendFrom(source, 0, recordOffset); filled.writeInt(1); filled.writeByteArray(advertisement);
            filled.appendFrom(source, rest, source.dataSize() - rest); filled.setDataPosition(0);
            ScanResult result = ScanResult.CREATOR.createFromParcel(filled);
            assertNotNull(result.getScanRecord()); return result;
        } finally { source.recycle(); filled.recycle(); }
    }
    private Object read(String name) {
        try { Field field = ScaleClient.class.getDeclaredField(name); field.setAccessible(true); return field.get(client); }
        catch (Exception error) { throw new AssertionError(error); }
    }
    private void set(String name, Object value) {
        try { Field field = ScaleClient.class.getDeclaredField(name); field.setAccessible(true); field.set(client, value); }
        catch (Exception error) { throw new AssertionError(error); }
    }
    private void invoke(String name, Class<?>[] types, Object... args) {
        try { Method method = ScaleClient.class.getDeclaredMethod(name, types); method.setAccessible(true); method.invoke(client, args); }
        catch (Exception error) { throw new AssertionError(error); }
    }
}
