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
import io.stridehub.app.scale.core.Profile;
import io.stridehub.app.scale.core.YunmaiProtocol;
import java.text.SimpleDateFormat;
import java.util.*;

/** Foreground-only BLE session. All state and callbacks are confined to the main thread. */
@SuppressLint("MissingPermission")
public final class ScaleClient {
    public interface Listener {
        void onStatus(String message);
        void onDeviceFound(Device device);
        void onLiveWeight(double weightKg);
        void onMeasurement(Measurement measurement);
        void onError(String message);
    }
    public static final class Device {
        public final String name, address;
        public final int rssi;
        public final boolean broadcast;
        Device(String name, String address, int rssi, boolean broadcast) {
            this.name = name; this.address = address; this.rssi = rssi; this.broadcast = broadcast;
        }
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
    private BluetoothLeScanner scanner;
    private ScanCallback scanCallback;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic writer;
    private Profile profile;
    private Device selected;
    private String preferred = "", pendingFingerprint, lastSavedFingerprint;
    private boolean busy, scanActive, broadcastArmed, awaitingWrite, delivered, saved, mtuPending;
    private long sessionStarted;
    private int generation, invalidFrames, scanResults;
    private final Runnable scanTimeout = this::onScanTimeout;
    private void onScanTimeout() {
        if (selected != null) return;
        stopScan();
        if (found.isEmpty()) fail("没有发现云麦秤。请踩亮秤、关闭原 App 的蓝牙连接，再重新搜索。");
        else listener.onStatus(preferred.isEmpty() ? "搜索结束，请选择你的秤；若已休眠，请踩亮后重试" : "未发现上次使用的秤，可点击「选择另一台秤」重新搜索");
    }
    private final Runnable sessionTimeout = () -> fail("等待测量超时。请赤脚上秤并保持站立；仍无结果可导出连接诊断。");
    private final Runnable operationTimeout = () -> fail("蓝牙通信超时，请唤醒秤后重试");
    private final Runnable finishTimeout = () -> { if (saved) finishSaved(); };
    private final Runnable mtuTimeout = this::finishMtu;

    public ScaleClient(Context context, Listener listener) {
        this.context = context.getApplicationContext(); this.listener = listener;
        lastSavedFingerprint = this.context.getSharedPreferences("ble_replay", Context.MODE_PRIVATE).getString("lastSaved", null);
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter();
    }
    public boolean isBusy() { return busy; }
    public String diagnostics() {
        return "TinyYumi 0.2.0 · 本机连接诊断\nAndroid " + Build.VERSION.RELEASE + " / " + Build.MODEL
                + "\n日志包含设备地址和测量报文，仅在主动导出时离开应用。\n\n" + String.join("\n", log);
    }
    private void record(String message) {
        if (log.size() >= 200) log.removeFirst();
        log.addLast(new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date()) + " " + message);
    }
    public void startScan(Profile profile, String preferredAddress) {
        cleanup(); found.clear(); unknownSeen.clear(); selected = null; scanResults = 0;
        if (profile == null || profile.validationError(System.currentTimeMillis()) != null) {
            listener.onError("请先填写有效的身体资料"); return;
        }
        this.profile = profile; preferred = preferredAddress == null ? "" : preferredAddress;
        if (adapter == null) { listener.onError("这台设备不支持蓝牙"); return; }
        try {
            if (!adapter.isEnabled()) { listener.onError("请先打开蓝牙"); return; }
            scanner = adapter.getBluetoothLeScanner();
            if (scanner == null) { listener.onError("蓝牙尚未就绪，请稍后重试"); return; }
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
            main.postDelayed(scanTimeout, 20_000);
            main.postDelayed(sessionTimeout, 120_000);
            record("SCAN start");
            listener.onStatus(preferred.isEmpty() ? "正在寻找体脂秤，请轻踩唤醒" : "正在寻找上次使用的秤，请轻踩唤醒");
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
            SparseArray<byte[]> manufacturer = scan.getManufacturerSpecificData();
            if (manufacturer != null) for (int i = 0; i < manufacturer.size(); i++) {
                broadcast = YunmaiProtocol.parseAdvertisement(manufacturer.keyAt(i), manufacturer.valueAt(i), address, hasYunmaiService);
                if (broadcast != null) break;
            }
            boolean yunmaiServices = services != null && services.contains(new ParcelUuid(uuid(0xffe0)))
                    && services.contains(new ParcelUuid(uuid(0xffe5)));
            boolean candidate = broadcast != null || YunmaiProtocol.isScaleName(name) || yunmaiServices;
            if (!candidate) {
                boolean maybeScale = (name != null && name.toUpperCase(Locale.ROOT).startsWith("YUNMAI"))
                        || hasYunmaiService;
                if (maybeScale && unknownSeen.size() < 20 && unknownSeen.add(address))
                    record("UNSUPPORTED ADV " + name + " " + address + " " + YunmaiProtocol.hex(scan.getBytes()));
                return;
            }
            int product = broadcast == null ? -1 : broadcast.raw[4] & 255;
            String fallbackName = product == 2 || product == 5 ? "好轻 mini2S" : "云麦广播秤";
            Device device = new Device(name == null || name.isEmpty() ? fallbackName : name, address, result.getRssi(), broadcast != null);
            if (!found.containsKey(address)) {
                found.put(address, device); record("FOUND " + device.name + " " + address + " broadcast=" + device.broadcast);
                listener.onDeviceFound(device);
            } else if (device.broadcast && !found.get(address).broadcast) {
                found.put(address, device); listener.onDeviceFound(device);
            }
            if (selected == null && address.equals(preferred)) connect(device, profile);
            if (selected != null && selected.broadcast && selected.address.equals(address) && broadcast != null) {
                if (!broadcast.finalMeasurement) {
                    broadcastArmed = true;
                    listener.onLiveWeight(broadcast.weightKg);
                } else if (broadcastArmed) {
                    record("ADV RX " + YunmaiProtocol.hex(broadcast.raw));
                    publish(broadcast);
                }
            }
        } catch (SecurityException e) { fail("蓝牙权限已被撤销，请重新授权"); }
    }
    public void connect(Device device, Profile profile) {
        if (!busy || device == null || selected != null) return;
        this.profile = profile; selected = device;
        main.removeCallbacks(scanTimeout);
        main.removeCallbacks(sessionTimeout); main.postDelayed(sessionTimeout, 120_000);
        sessionStarted = System.currentTimeMillis(); broadcastArmed = false; invalidFrames = 0; decoder.reset();
        if (device.broadcast) {
            // Selection after a final advertisement must not save a stale measurement from someone else.
            listener.onStatus("已找到广播秤，请先下秤，再赤脚上秤测量");
            record("SELECT broadcast " + device.address);
            if (!scanActive) {
                try { scanActive = true; scanner.startScan(null, new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback); }
                catch (Exception e) { fail("无法继续接收广播，请重新搜索"); }
            }
            return;
        }
        stopScan(); record("CONNECT " + device.address); listener.onStatus("正在连接 " + device.name);
        try {
            gatt = adapter.getRemoteDevice(device.address).connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
            main.postDelayed(operationTimeout, 15_000);
        } catch (SecurityException e) { fail("没有连接蓝牙的权限"); }
        catch (IllegalArgumentException e) { fail("设备地址无效，请重新搜索"); }
    }
    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt source, int status, int state) {
            main.post(() -> {
                if (source != gatt || !busy) return;
                record("GATT state=" + state + " status=" + status);
                if (status != BluetoothGatt.GATT_SUCCESS || state == BluetoothProfile.STATE_DISCONNECTED) {
                    if (saved) { finishSaved(); return; }
                    fail("体脂秤连接已断开（" + status + "），请重新唤醒后测量"); return;
                }
                if (state == BluetoothProfile.STATE_CONNECTED) {
                    listener.onStatus("正在识别体脂秤");
                    try { if (!source.discoverServices()) fail("无法读取秤的蓝牙服务"); }
                    catch (SecurityException e) { fail("蓝牙权限已被撤销"); }
                }
            });
        }
        @Override public void onServicesDiscovered(BluetoothGatt source, int status) {
            main.post(() -> {
                if (source != gatt || !busy) return;
                if (status != BluetoothGatt.GATT_SUCCESS) { fail("无法读取蓝牙服务（" + status + "）"); return; }
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
        if (status != BluetoothGatt.GATT_SUCCESS) { fail("无法读取蓝牙服务（" + status + "）"); return; }
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
        if (gatt == null || !busy || awaitingWrite) return;
        if (commands.isEmpty()) { if (saved) finishSaved(); else listener.onStatus("已连接，请赤脚上秤，等待体脂测量完成"); return; }
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
                } else listener.onLiveWeight(frame.weightKg);
            }
            if (delivered) return;
            if (decoder.rejectedCount() > invalidFrames) {
                invalidFrames = decoder.rejectedCount();
                record("Rejected malformed/unsupported frames=" + invalidFrames);
                if (invalidFrames >= 3) listener.onStatus("收到尚不支持或不完整的数据，可导出诊断协助适配");
            }
        });
    }
    private void publish(YunmaiProtocol.Frame frame) {
        if (delivered) return;
        try {
            String fingerprint = YunmaiProtocol.replayFingerprint(frame, selected.address);
            if (fingerprint != null && fingerprint.equals(lastSavedFingerprint)) {
                record("Ignored already saved final packet");
                listener.onStatus("这条结果已保存，请先下秤，再重新测量");
                return;
            }
            Measurement measurement = YunmaiProtocol.toMeasurement(frame, profile, selected.address, System.currentTimeMillis());
            pendingFingerprint = fingerprint;
            record("FINAL kg=" + measurement.weightKg + " fat=" + measurement.bodyFatPercent + " source=" + measurement.source);
            delivered = true;
            main.removeCallbacks(sessionTimeout);
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
        if (selected != null && selected.broadcast) { finishSaved(); return; }
        commands.clear(); commands.add(YunmaiProtocol.acknowledgmentCommand());
        main.removeCallbacks(operationTimeout);
        main.postDelayed(finishTimeout, 1500);
        if (!awaitingWrite) writeNext();
    }
    private void finishSaved() { cleanup(); listener.onStatus("测量完成，已保存到本机"); }
    public void stop() {
        boolean wasBusy = busy; cleanup();
        if (wasBusy) { record("Session stopped"); listener.onStatus("测量已停止"); }
    }
    private void stopScan() {
        if (scanner != null && scanActive && scanCallback != null) {
            try { scanner.stopScan(scanCallback); } catch (SecurityException | IllegalStateException ignored) { }
        }
        if (scanActive) record("SCAN end received=" + scanResults + " supported=" + found.size() + " unsupported=" + unknownSeen.size());
        scanActive = false; main.removeCallbacks(scanTimeout);
    }
    private void cleanup() {
        generation++; busy = false; stopScan();
        main.removeCallbacks(sessionTimeout); main.removeCallbacks(operationTimeout); main.removeCallbacks(finishTimeout); main.removeCallbacks(mtuTimeout); mtuPending = false;
        delivered = false; saved = false; pendingFingerprint = null;
        BluetoothGatt closing = gatt; gatt = null; writer = null; selected = null;
        commands.clear(); awaitingWrite = false; decoder.reset();
        if (closing != null) {
            try { closing.disconnect(); closing.close(); } catch (SecurityException ignored) { }
        }
    }
    private void fail(String message) {
        if (saved) { record("Acknowledgment incomplete: " + message); finishSaved(); return; }
        record("ERROR " + message); cleanup(); listener.onError(message);
    }
}
