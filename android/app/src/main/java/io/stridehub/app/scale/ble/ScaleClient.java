package io.stridehub.app.scale.ble;

import android.annotation.SuppressLint;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.SparseArray;
import io.stridehub.app.scale.core.Measurement;
import io.stridehub.app.scale.core.BroadcastMeasurementGate;
import io.stridehub.app.scale.core.Profile;
import io.stridehub.app.scale.core.YunmaiProtocol;
import java.text.SimpleDateFormat;
import java.util.*;

/** Foreground-only BLE session. All state and callbacks are confined to the main thread. */
@SuppressLint("MissingPermission")
public final class ScaleClient {
    public enum State { IDLE, SEARCHING, CONNECTING, READY, MEASURING, REVIEW, SAVING, SAVED, ERROR }
    public interface Listener {
        void onStatus(String message);
        void onDeviceFound(Device device);
        void onLiveWeight(double weightKg);
        void onMeasurement(Measurement measurement);
        void onError(String message);
        default void onStateChanged(State state, String message) { onStatus(message); }
        default void onConfirmationRequired(Measurement measurement, String explanation) { }
    }
    public static final class Device {
        public final String name, address;
        public final int rssi;
        public final boolean broadcast;
        Device(String name, String address, int rssi, boolean broadcast) {
            this.name = name; this.address = address; this.rssi = rssi; this.broadcast = broadcast;
        }
        public String transportType() { return broadcast ? "broadcast" : "gatt"; }
    }
    private static final class RecentBroadcast {
        final YunmaiProtocol.Frame frame;
        final long receivedAt;
        RecentBroadcast(YunmaiProtocol.Frame frame, long receivedAt) { this.frame = frame; this.receivedAt = receivedAt; }
    }
    private static UUID uuid(int value) {
        return UUID.fromString(String.format(Locale.ROOT, "0000%04x-0000-1000-8000-00805f9b34fb", value));
    }
    private static final UUID NOTIFY = uuid(0xffe4), WRITE = uuid(0xffe9), CCC = uuid(0x2902);
    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final BluetoothAdapter adapter;
    private final Map<String, Device> found = new LinkedHashMap<>();
    private final Set<String> unknownSeen = new HashSet<>();
    private final ArrayDeque<String> log = new ArrayDeque<>();
    private final ArrayDeque<byte[]> commands = new ArrayDeque<>();
    private final YunmaiProtocol.Decoder decoder = new YunmaiProtocol.Decoder();
    private final BroadcastMeasurementGate broadcastGate = new BroadcastMeasurementGate();
    private final Map<String, RecentBroadcast> recentBroadcasts = new HashMap<>();
    private final Map<String, Long> deviceUpdatedAt = new HashMap<>();
    private State state = State.IDLE;
    private String status = "称重尚未开始", preferredType = "unknown", pendingBroadcastFingerprint;
    private Measurement pendingMeasurement;
    private YunmaiProtocol.Frame pendingFrame;
    private int connectionRetries;
    private Runnable reconnectTask;
    private BluetoothLeScanner scanner;
    private ScanCallback scanCallback;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic writer;
    private Profile profile;
    private Device selected;
    private String preferred = "", pendingFingerprint, lastSavedFingerprint;
    private boolean busy, scanActive, awaitingWrite, delivered, saved, mtuPending;
    private long sessionStarted;
    private int generation, invalidFrames, scanResults;
    private final Runnable scanTimeout = () -> {
        if (selected == null) fail(found.isEmpty()
                ? "暂未收到体脂秤信号。请确认秤已亮起、手机在附近，且其他 App 未连接。可以继续等待。"
                : "还未选中体脂秤，请选择设备后继续等待。已有记录不受影响。");
    };
    private final Runnable scanHint = () -> {
        if (busy && selected == null) changeState(State.SEARCHING, preferred.isEmpty()
                ? "还在等待体脂秤信号，请直接上秤；本次会持续等待 90 秒"
                : "正在等待已记住的体脂秤，请直接上秤；无需反复点击连接");
    };
    private final Runnable sessionTimeout = () -> fail("本次等待已结束。若秤已休眠，请重新唤醒后再试；已有记录不受影响。");
    private final Runnable operationTimeout = () -> retryConnection("蓝牙通信超时");
    private final Runnable finishTimeout = () -> { if (saved) finishSaved(); };
    private final Runnable mtuTimeout = this::finishMtu;

    public ScaleClient(Context context, Listener listener) {
        this.context = context.getApplicationContext(); this.listener = listener;
        android.content.SharedPreferences replay = this.context.getSharedPreferences("ble_replay", Context.MODE_PRIVATE);
        lastSavedFingerprint = replay.getString("lastSaved", null);
        long now = System.currentTimeMillis();
        for (Map.Entry<String, ?> entry : replay.getAll().entrySet()) {
            if (entry.getKey().startsWith("broadcast:") && entry.getValue() instanceof Long) {
                long when = (Long) entry.getValue();
                if (now >= when && now - when <= BroadcastMeasurementGate.REPLAY_WINDOW_MS)
                    broadcastGate.markSaved(entry.getKey().substring(10), when);
            }
        }
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter();
    }
    public boolean isBusy() { return busy; }
    public State getState() { return state; }
    public String getStatus() { return status; }
    public Device getSelectedDevice() { return selected; }
    private void changeState(State next, String message) {
        state = next; status = message; listener.onStateChanged(next, message);
    }
    public String diagnostics() {
        return "StrideHub · 本机连接诊断\nAndroid " + Build.VERSION.RELEASE + " / " + Build.MODEL
                + "\n日志包含设备地址和测量报文，仅在主动导出时离开应用。\n\n" + String.join("\n", log);
    }
    private void record(String message) {
        if (log.size() >= 200) log.removeFirst();
        log.addLast(new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date()) + " " + message);
    }
    public void startScan(Profile profile, String preferredAddress) { startScan(profile, preferredAddress, "unknown"); }
    public void startScan(Profile profile, String preferredAddress, String preferredType) {
        cleanup(); found.clear(); unknownSeen.clear(); recentBroadcasts.clear(); deviceUpdatedAt.clear();
        selected = null; scanResults = 0; connectionRetries = 0;
        this.preferredType = "broadcast".equals(preferredType) ? "broadcast" : "gatt".equals(preferredType) ? "gatt" : "unknown";
        if (profile == null || profile.validationError(System.currentTimeMillis()) != null) {
            fail("请先填写有效的身体资料"); return;
        }
        this.profile = profile; preferred = preferredAddress == null ? "" : preferredAddress;
        if (adapter == null) { fail("这台设备不支持蓝牙"); return; }
        try {
            if (!adapter.isEnabled()) { fail("请先打开蓝牙"); return; }
            scanner = adapter.getBluetoothLeScanner();
            if (scanner == null) { fail("蓝牙尚未就绪，请稍后重试"); return; }
            busy = true; sessionStarted = System.currentTimeMillis();
            final int token = generation;
            scanCallback = new ScanCallback() {
                @Override public void onScanResult(int type, ScanResult result) {
                    main.post(() -> { if (token == generation) handleScan(result); });
                }
                @Override public void onBatchScanResults(List<ScanResult> results) {
                    main.post(() -> { if (token == generation) for (ScanResult result : results) handleScan(result); });
                }
                @Override public void onScanFailed(int error) {
                    main.post(() -> { if (token == generation) fail("蓝牙搜索失败（" + error + "），请稍后重试"); });
                }
            };
            scanActive = true;
            scanner.startScan(null, new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback);
            main.postDelayed(scanHint, 20_000);
            main.postDelayed(scanTimeout, 90_000);
            main.postDelayed(sessionTimeout, 120_000);
            record("SCAN start");
            changeState(State.SEARCHING, preferred.isEmpty() ? "正在寻找体脂秤，请直接上秤，发现后选择你的设备" : "已开始等待已记住的体脂秤，请直接上秤");
        } catch (SecurityException e) { fail("缺少附近设备权限，请在系统设置中允许"); }
        catch (IllegalStateException e) { fail("蓝牙状态发生变化，请重新打开蓝牙后重试"); }
    }
    private void handleScan(ScanResult result) {
        if (!busy || delivered || !scanActive || result.getScanRecord() == null) return;
        scanResults++;
        try {
            String address = result.getDevice().getAddress();
            ScanRecord scan = result.getScanRecord();
            String name = scan.getDeviceName();
            if (name == null) name = result.getDevice().getName();
            List<ParcelUuid> services = scan.getServiceUuids();
            boolean hasYunmaiService = services != null && services.contains(new ParcelUuid(uuid(0x1320)));
            YunmaiProtocol.Frame broadcast = null;
            boolean broadcastIdentity = hasYunmaiService || YunmaiProtocol.isBroadcastName(name)
                    || (address.equals(preferred) && "broadcast".equals(preferredType));
            SparseArray<byte[]> manufacturer = scan.getManufacturerSpecificData();
            if (manufacturer != null) for (int i = 0; i < manufacturer.size(); i++) {
                int id = manufacturer.keyAt(i); byte[] payload = manufacturer.valueAt(i);
                broadcastIdentity |= YunmaiProtocol.isBroadcastAdvertisement(id, payload, address, hasYunmaiService);
                YunmaiProtocol.Frame frame = YunmaiProtocol.parseAdvertisement(id, payload, address, hasYunmaiService);
                if (frame != null) broadcast = frame;
            }
            boolean yunmaiServices = services != null && services.contains(new ParcelUuid(uuid(0xffe0)))
                    && services.contains(new ParcelUuid(uuid(0xffe5)));
            Device previous = found.get(address);
            broadcastIdentity |= previous != null && previous.broadcast;
            boolean candidate = broadcastIdentity || YunmaiProtocol.isScaleName(name) || yunmaiServices;
            if (!candidate) {
                if (name != null && name.toUpperCase(Locale.ROOT).startsWith("YUNMAI")
                        && unknownSeen.size() < 20 && unknownSeen.add(address))
                    record("UNSUPPORTED ADV " + name + " " + address + " " + YunmaiProtocol.hex(scan.getBytes()));
                return;
            }
            int product = broadcast == null ? -1 : broadcast.raw[4] & 255;
            String fallbackName = product == 2 || product == 5 ? "好轻 mini2S" : broadcastIdentity ? "云麦广播秤" : "云麦体脂秤";
            Device device = new Device(name == null || name.isEmpty() ? fallbackName : name, address, result.getRssi(), broadcastIdentity);
            long now = System.currentTimeMillis();
            if (broadcast != null) recentBroadcasts.put(address, new RecentBroadcast(broadcast, now));
            found.put(address, device);
            Long updated = deviceUpdatedAt.get(address);
            if (previous == null || previous.broadcast != device.broadcast || updated == null || now - updated >= 2000) {
                deviceUpdatedAt.put(address, now);
                if (previous == null) record("FOUND " + device.name + " " + address + " broadcast=" + device.broadcast);
                listener.onDeviceFound(device);
            }
            if (selected == null && address.equals(preferred)) { connect(device, profile); return; }
            if (selected != null && selected.broadcast && selected.address.equals(address) && broadcast != null)
                handleBroadcast(broadcast, now);
        } catch (SecurityException e) { fail("蓝牙权限已被撤销，请重新授权"); }
    }
    public void connect(Device device, Profile profile) {
        if (!busy || device == null || selected != null) return;
        if (profile == null || profile.validationError(System.currentTimeMillis()) != null) {
            fail("请先填写有效的身体资料"); return;
        }
        Device latest = found.get(device.address);
        this.profile = profile; selected = latest != null && latest.broadcast ? latest : device;
        main.removeCallbacks(scanTimeout); main.removeCallbacks(scanHint);
        main.removeCallbacks(sessionTimeout); main.postDelayed(sessionTimeout, 120_000);
        sessionStarted = System.currentTimeMillis(); invalidFrames = 0; decoder.reset(); broadcastGate.resetSession();
        connectionRetries = 0;
        if (selected.broadcast) {
            changeState(State.READY, "已找到体脂秤，请直接站稳，稳定结果会自动保存");
            record("SELECT broadcast " + selected.address);
            RecentBroadcast cached = recentBroadcasts.get(selected.address);
            if (cached != null && sessionStarted >= cached.receivedAt && sessionStarted - cached.receivedAt <= 5000)
                handleBroadcast(cached.frame, sessionStarted);
            return;
        }
        stopScan(); connectGatt();
    }
    private void handleBroadcast(YunmaiProtocol.Frame frame, long now) {
        if (!busy || delivered || selected == null || pendingMeasurement != null) return;
        BroadcastMeasurementGate.Decision decision = broadcastGate.inspect(frame, selected.address, now);
        if (decision == BroadcastMeasurementGate.Decision.LIVE) {
            if (state != State.MEASURING) changeState(State.MEASURING, "正在称重，请保持站稳，等待稳定结果");
            listener.onLiveWeight(frame.weightKg);
        } else if (decision == BroadcastMeasurementGate.Decision.AUTO_SAVE) {
            record("ADV RX " + YunmaiProtocol.hex(frame.raw)); publish(frame);
        } else if (decision == BroadcastMeasurementGate.Decision.REVIEW) {
            try {
                pendingFrame = frame;
                pendingMeasurement = YunmaiProtocol.toMeasurement(frame, profile, selected.address, now);
                String explanation = "收到稳定读数，但没有接收到这次上秤的过程。请确认这是你的本次测量；记录时间采用本次接收时间。";
                changeState(State.REVIEW, "检测到稳定读数，请确认是否保存");
                listener.onConfirmationRequired(pendingMeasurement, explanation);
                record("ADV awaiting confirmation " + YunmaiProtocol.hex(frame.raw));
            } catch (IllegalArgumentException error) { record("Invalid candidate: " + error.getMessage()); }
        } else if (decision == BroadcastMeasurementGate.Decision.DUPLICATE) {
            String message = "这条结果已保存，正在等待下一次测量";
            if (!message.equals(status)) changeState(State.READY, message);
        }
    }
    public void confirmPendingMeasurement() {
        if (!busy || state != State.REVIEW || pendingMeasurement == null || pendingFrame == null || selected == null) return;
        Measurement accepted = pendingMeasurement;
        pendingBroadcastFingerprint = BroadcastMeasurementGate.fingerprint(pendingFrame, selected.address);
        pendingMeasurement = null; pendingFrame = null; delivered = true;
        main.removeCallbacks(sessionTimeout);
        changeState(State.SAVING, "正在保存这次测量到本机");
        listener.onMeasurement(accepted);
    }
    public void discardPendingMeasurement() {
        if (!busy || state != State.REVIEW || selected == null) return;
        broadcastGate.dismiss(pendingFrame, selected.address);
        pendingMeasurement = null; pendingFrame = null;
        changeState(State.READY, "已忽略这条读数，继续等待新的测量");
    }
    private void connectGatt() {
        if (!busy || selected == null || selected.broadcast) return;
        record("CONNECT " + selected.address + " retry=" + connectionRetries);
        changeState(State.CONNECTING, connectionRetries == 0 ? "正在连接 " + selected.name : "连接中断，正在自动重连（" + connectionRetries + "/2）");
        try {
            gatt = adapter.getRemoteDevice(selected.address).connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
            if (gatt == null) { retryConnection("无法建立蓝牙连接"); return; }
            main.postDelayed(operationTimeout, 15_000);
        } catch (SecurityException e) { fail("没有连接蓝牙的权限"); }
        catch (IllegalArgumentException e) { fail("设备地址无效，请重新搜索"); }
    }
    private void retryConnection(String reason) {
        if (!busy) return;
        if (saved) { finishSaved(); return; }
        if (delivered) return;
        if (selected == null || selected.broadcast || connectionRetries >= 2) {
            fail(reason + "，请确认秤已唤醒后重新等待"); return;
        }
        if (reconnectTask != null) main.removeCallbacks(reconnectTask);
        closeGatt(); connectionRetries++;
        record("RETRY " + connectionRetries + " " + reason);
        changeState(State.CONNECTING, "连接暂时中断，正在自动重试（" + connectionRetries + "/2）");
        final int token = generation;
        reconnectTask = () -> { if (token == generation && busy) connectGatt(); };
        main.postDelayed(reconnectTask, connectionRetries * 1500L);
    }
    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt source, int status, int state) {
            main.post(() -> {
                if (source != gatt || !busy) return;
                record("GATT state=" + state + " status=" + status);
                if (status != BluetoothGatt.GATT_SUCCESS || state == BluetoothProfile.STATE_DISCONNECTED) {
                    if (saved) { finishSaved(); return; }
                    retryConnection("体脂秤连接已断开（" + status + "）"); return;
                }
                if (state == BluetoothProfile.STATE_CONNECTED) {
                    changeState(State.CONNECTING, "正在识别体脂秤");
                    try { if (!source.discoverServices()) retryConnection("无法读取秤的蓝牙服务"); }
                    catch (SecurityException e) { fail("蓝牙权限已被撤销"); }
                }
            });
        }
        @Override public void onServicesDiscovered(BluetoothGatt source, int status) {
            main.post(() -> {
                if (source != gatt || !busy) return;
                if (status != BluetoothGatt.GATT_SUCCESS) { retryConnection("无法读取蓝牙服务（" + status + "）"); return; }
                main.removeCallbacks(operationTimeout);
                mtuPending = true;
                try {
                    if (source.requestMtu(75)) main.postDelayed(mtuTimeout, 3000);
                    else finishMtu();
                } catch (SecurityException e) { fail("蓝牙权限已被撤销"); }
            });
        }
        @Override public void onMtuChanged(BluetoothGatt source, int mtu, int status) {
            main.post(() -> {
                if (source != gatt || !busy) return;
                record("MTU " + mtu + " status=" + status);
                finishMtu();
            });
        }
        @Override public void onDescriptorWrite(BluetoothGatt source, BluetoothGattDescriptor descriptor, int status) {
            main.post(() -> {
                if (source != gatt || !busy) return;
                main.removeCallbacks(operationTimeout);
                if (status != BluetoothGatt.GATT_SUCCESS) { fail("无法订阅秤的测量结果（" + status + "）"); return; }
                queueInitialization();
            });
        }
        @Override public void onCharacteristicWrite(BluetoothGatt source, BluetoothGattCharacteristic characteristic, int status) {
            main.post(() -> {
                if (source != gatt || !busy || !awaitingWrite) return;
                awaitingWrite = false; main.removeCallbacks(operationTimeout);
                if (status != BluetoothGatt.GATT_SUCCESS) { if (saved) finishSaved(); else fail("发送身体资料失败（" + status + "）"); return; }
                main.postDelayed(() -> { if (source == gatt && busy) writeNext(); }, 180);
            });
        }
        @Override public void onCharacteristicChanged(BluetoothGatt source, BluetoothGattCharacteristic characteristic) {
            if (Build.VERSION.SDK_INT < 33) receive(source, characteristic.getUuid(), characteristic.getValue());
        }
        @Override public void onCharacteristicChanged(BluetoothGatt source, BluetoothGattCharacteristic characteristic, byte[] value) {
            receive(source, characteristic.getUuid(), value);
        }
    };
    private void finishMtu() {
        if (!mtuPending || gatt == null || !busy) return;
        mtuPending = false; main.removeCallbacks(mtuTimeout);
        configure(gatt, BluetoothGatt.GATT_SUCCESS);
    }
    private void configure(BluetoothGatt source, int status) {
        if (status != BluetoothGatt.GATT_SUCCESS) { retryConnection("无法读取蓝牙服务（" + status + "）"); return; }
        main.removeCallbacks(operationTimeout);
        BluetoothGattCharacteristic notify = null; writer = null;
        for (BluetoothGattService service : source.getServices()) {
            record("SERVICE " + service.getUuid());
            for (BluetoothGattCharacteristic characteristic : service.getCharacteristics()) {
                record("CHAR " + characteristic.getUuid() + " properties=" + characteristic.getProperties());
                if (characteristic.getUuid().equals(NOTIFY)) notify = characteristic;
                if (characteristic.getUuid().equals(WRITE)) writer = characteristic;
            }
        }
        if (notify == null || writer == null) { fail("该型号使用不同的蓝牙协议，暂未适配。可在设置中导出连接诊断。"); return; }
        int notifyProperties = notify.getProperties();
        if ((notifyProperties & (BluetoothGattCharacteristic.PROPERTY_NOTIFY | BluetoothGattCharacteristic.PROPERTY_INDICATE)) == 0
                || (writer.getProperties() & (BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) == 0) {
            fail("该秤的蓝牙特征不支持目前的通信方式"); return;
        }
        try {
            BluetoothGattDescriptor descriptor = notify.getDescriptor(CCC);
            if (descriptor == null || !source.setCharacteristicNotification(notify, true)) { fail("该秤未提供测量通知通道"); return; }
            byte[] value = (notifyProperties & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                    ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE : BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;
            boolean accepted;
            if (Build.VERSION.SDK_INT >= 33) accepted = source.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS;
            else { descriptor.setValue(value); accepted = source.writeDescriptor(descriptor); }
            if (!accepted) { fail("订阅测量通知失败，请重新测量"); return; }
            main.postDelayed(operationTimeout, 8_000);
        } catch (SecurityException e) { fail("蓝牙权限已被撤销"); }
    }
    private void queueInitialization() {
        commands.clear(); long now = System.currentTimeMillis();
        commands.add(YunmaiProtocol.versionCommand());
        commands.add(YunmaiProtocol.userCommand(profile, now));
        commands.add(YunmaiProtocol.timeQueryCommand());
        commands.add(YunmaiProtocol.timeCommand(now, TimeZone.getDefault().getOffset(now) / 1000));
        writeNext();
    }
    private void writeNext() {
        if (gatt == null || writer == null || !busy || awaitingWrite) return;
        if (commands.isEmpty()) { if (saved) finishSaved(); else if (state != State.MEASURING) changeState(State.READY, "已连接，请站稳等待结果，稳定后会自动保存"); return; }
        byte[] command = commands.removeFirst();
        int type = (writer.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
                ? BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT : BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
        try {
            boolean accepted;
            if (Build.VERSION.SDK_INT >= 33) accepted = gatt.writeCharacteristic(writer, command, type) == BluetoothStatusCodes.SUCCESS;
            else { writer.setWriteType(type); writer.setValue(command); accepted = gatt.writeCharacteristic(writer); }
            record("TX " + YunmaiProtocol.hex(command));
            if (!accepted) { fail("蓝牙正忙，无法发送资料，请重新测量"); return; }
            awaitingWrite = true; main.postDelayed(operationTimeout, 8_000);
        } catch (SecurityException e) { fail("蓝牙权限已被撤销"); }
    }
    private void receive(BluetoothGatt source, UUID characteristic, byte[] value) {
        if (value == null || !characteristic.equals(NOTIFY)) return;
        byte[] copy = value.clone();
        main.post(() -> {
            if (source != gatt || !busy || delivered) return;
            record("RX " + YunmaiProtocol.hex(copy));
            for (YunmaiProtocol.Frame frame : decoder.accept(copy)) {
                if (selected == null || !busy || delivered) break;
                if (frame.finalMeasurement) {
                    if (frame.offline) { record("Ignored offline history frame"); continue; }
                    if (frame.userId != profile.userId) { record("Ignored different profile id=" + frame.userId); continue; }
                    if (frame.deviceTimestamp > 0 && frame.deviceTimestamp < sessionStarted - 120_000) {
                        record("Ignored stored result from an earlier session"); continue;
                    }
                    publish(frame);
                } else {
                    if (state != State.MEASURING) changeState(State.MEASURING, "正在称重，请保持站稳，等待稳定结果");
                    listener.onLiveWeight(frame.weightKg);
                }
            }
            if (delivered) return;
            if (decoder.rejectedCount() > invalidFrames) {
                invalidFrames = decoder.rejectedCount();
                record("Rejected malformed/unsupported frames=" + invalidFrames);
                if (invalidFrames >= 3) changeState(state, "收到尚不支持或不完整的数据，可导出诊断协助适配");
            }
        });
    }
    private void publish(YunmaiProtocol.Frame frame) {
        if (delivered) return;
        try {
            String fingerprint = YunmaiProtocol.replayFingerprint(frame, selected.address);
            if (fingerprint != null && fingerprint.equals(lastSavedFingerprint)) {
                record("Ignored already saved final packet");
                changeState(State.READY, "这条结果已保存，正在等待下一次测量");
                return;
            }
            Measurement measurement = YunmaiProtocol.toMeasurement(frame, profile, selected.address, System.currentTimeMillis());
            pendingFingerprint = fingerprint;
            pendingBroadcastFingerprint = BroadcastMeasurementGate.fingerprint(frame, selected.address);
            record("FINAL kg=" + measurement.weightKg + " fat=" + measurement.bodyFatPercent + " source=" + measurement.source);
            delivered = true;
            main.removeCallbacks(sessionTimeout);
            changeState(State.SAVING, "正在保存这次测量到本机");
            listener.onMeasurement(measurement);
        } catch (IllegalArgumentException e) { record("Invalid measurement: " + e.getMessage()); }
    }
    /** Called by the Activity only after SQLite successfully persists the delivered result. */
    public void confirmSaved() {
        if (!delivered || saved) return;
        saved = true;
        if (pendingFingerprint != null) {
            lastSavedFingerprint = pendingFingerprint;
            context.getSharedPreferences("ble_replay", Context.MODE_PRIVATE).edit().putString("lastSaved", lastSavedFingerprint).apply();
        }
        if (pendingBroadcastFingerprint != null) {
            long now = System.currentTimeMillis();
            broadcastGate.markSaved(pendingBroadcastFingerprint, now);
            android.content.SharedPreferences replay = context.getSharedPreferences("ble_replay", Context.MODE_PRIVATE);
            android.content.SharedPreferences.Editor editor = replay.edit();
            for (Map.Entry<String, ?> entry : replay.getAll().entrySet()) {
                if (entry.getKey().startsWith("broadcast:") && entry.getValue() instanceof Long) {
                    long when = (Long) entry.getValue();
                    if (now < when || now - when > BroadcastMeasurementGate.REPLAY_WINDOW_MS) editor.remove(entry.getKey());
                }
            }
            editor.putLong("broadcast:" + pendingBroadcastFingerprint, now).apply();
        }
        if (selected != null && selected.broadcast) { finishSaved(); return; }
        commands.clear(); commands.add(YunmaiProtocol.acknowledgmentCommand());
        main.removeCallbacks(operationTimeout);
        main.postDelayed(finishTimeout, 1500);
        if (!awaitingWrite) writeNext();
    }
    private void finishSaved() { cleanup(); changeState(State.SAVED, "测量完成，已保存到本机"); }
    public void stop() {
        boolean wasBusy = busy; cleanup();
        if (wasBusy) { record("Session stopped"); changeState(State.IDLE, "测量已暂停"); }
    }
    private void stopScan() {
        if (scanner != null && scanActive && scanCallback != null) {
            try { scanner.stopScan(scanCallback); } catch (SecurityException | IllegalStateException ignored) { }
        }
        if (scanActive) record("SCAN end received=" + scanResults + " supported=" + found.size() + " unsupported=" + unknownSeen.size());
        scanActive = false; main.removeCallbacks(scanTimeout); main.removeCallbacks(scanHint);
    }
    private void closeGatt() {
        main.removeCallbacks(operationTimeout); main.removeCallbacks(mtuTimeout); mtuPending = false;
        BluetoothGatt closing = gatt; gatt = null; writer = null;
        commands.clear(); awaitingWrite = false; decoder.reset();
        if (closing != null) {
            try { closing.disconnect(); closing.close(); } catch (SecurityException ignored) { }
        }
    }
    private void cleanup() {
        generation++; busy = false; stopScan();
        main.removeCallbacks(sessionTimeout); main.removeCallbacks(finishTimeout);
        if (reconnectTask != null) main.removeCallbacks(reconnectTask);
        reconnectTask = null;
        delivered = false; saved = false; pendingFingerprint = null; pendingBroadcastFingerprint = null;
        pendingMeasurement = null; pendingFrame = null; selected = null; broadcastGate.resetSession();
        closeGatt();
    }
    private void fail(String message) {
        if (saved) { record("Acknowledgment incomplete: " + message); finishSaved(); return; }
        record("ERROR " + message); cleanup(); changeState(State.ERROR, message); listener.onError(message);
    }
}
