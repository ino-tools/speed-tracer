package com.ino.speedtracer;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Base64;
import android.webkit.WebView;
import android.widget.ArrayAdapter;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * ページの navigator.bluetooth(bridge.js がまねている)の中身。
 * SpeedTracer 本体の BLE(Nordic UART 互換)につないで、通知をページへ渡す。
 * GATT の操作は同時に1つしかできないので、順番待ちの列で1つずつ実行する。
 */
@SuppressLint("MissingPermission")
class BleBridge {
    static final int REQ_PERM_BLE = 31;
    static final int REQ_ENABLE_BT = 32;
    static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    final Activity act;
    final WebView web;
    final Handler main = new Handler(Looper.getMainLooper());
    BluetoothAdapter adapter;
    BluetoothGatt gatt;

    // requestDevice の途中で権限や Bluetooth オンを待つ間、保留しておく
    Runnable pendingAfterPerm;
    int pendingCbId;

    // GATT 操作の順番待ち
    final ArrayDeque<Op> ops = new ArrayDeque<>();
    Op running;
    int connectCbId;

    static class Op {
        final Runnable start; final int cbId;
        Op(Runnable start, int cbId) { this.start = start; this.cbId = cbId; }
    }

    BleBridge(Activity act, WebView web) {
        this.act = act; this.web = web;
        BluetoothManager bm = act.getSystemService(BluetoothManager.class);
        adapter = bm == null ? null : bm.getAdapter();
    }

    boolean available() { return adapter != null; }

    // ---------- ページへ返す ----------
    void reply(int cbId, boolean ok, Object val) {
        if (cbId <= 0) return;
        final String v = val == null ? "null" : (val instanceof JSONObject ? val.toString() : JSONObject.quote(String.valueOf(val)));
        main.post(() -> web.evaluateJavascript("window.__stBleCb&&window.__stBleCb(" + cbId + "," + ok + "," + v + ")", null));
    }

    void js(String code) { main.post(() -> web.evaluateJavascript(code, null)); }

    // ---------- 入口 ----------
    void call(String method, String argsJson, int cbId) {
        main.post(() -> {
            try {
                JSONObject a = new JSONObject(argsJson == null ? "{}" : argsJson);
                switch (method) {
                    case "requestDevice": requestDevice(a, cbId); break;
                    case "connect": connect(a.optString("id"), cbId); break;
                    case "hasService": hasService(a.optString("uuid"), cbId); break;
                    case "hasChar": hasChar(a.optString("service"), a.optString("uuid"), cbId); break;
                    case "notify": enqueue(() -> startNotify(a.optString("uuid"), cbId), cbId); break;
                    case "write": enqueue(() -> write(a.optString("uuid"), a.optString("b64"), cbId), cbId); break;
                    case "disconnect": disconnect(); break;
                    default: reply(cbId, false, "未対応の操作: " + method);
                }
            } catch (Exception e) {
                reply(cbId, false, e.getMessage());
            }
        });
    }

    // ---------- 権限 ----------
    String[] neededPerms() {
        if (Build.VERSION.SDK_INT >= 31)
            return new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT};
        return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }

    boolean hasPerms() {
        for (String p : neededPerms())
            if (act.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        return true;
    }

    void onPermissionResult(int requestCode) {
        if (requestCode != REQ_PERM_BLE || pendingAfterPerm == null) return;
        Runnable r = pendingAfterPerm; pendingAfterPerm = null;
        if (hasPerms()) r.run();
        else reply(pendingCbId, false, "Bluetooth の権限が許可されていません(設定 → アプリ → SpeedTracer → 権限 で許可してください)");
    }

    void onActivityResult(int requestCode, int resultCode) {
        if (requestCode != REQ_ENABLE_BT || pendingAfterPerm == null) return;
        Runnable r = pendingAfterPerm; pendingAfterPerm = null;
        if (adapter != null && adapter.isEnabled()) r.run();
        else reply(pendingCbId, false, "Bluetooth がオフです");
    }

    // ---------- 機器を探して選ぶ ----------
    void requestDevice(JSONObject opts, int cbId) {
        if (adapter == null) { reply(cbId, false, "この端末は Bluetooth に対応していません"); return; }
        if (!hasPerms()) {
            pendingCbId = cbId; pendingAfterPerm = () -> requestDevice(opts, cbId);
            act.requestPermissions(neededPerms(), REQ_PERM_BLE);
            return;
        }
        if (!adapter.isEnabled()) {
            pendingCbId = cbId; pendingAfterPerm = () -> requestDevice(opts, cbId);
            try {
                act.startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_ENABLE_BT);
            } catch (Exception e) {
                pendingAfterPerm = null; reply(cbId, false, "Bluetooth をオンにしてください");
            }
            return;
        }

        // 探す条件(ページの filters の services / name / namePrefix)
        final List<UUID> svc = new ArrayList<>();
        final List<String> names = new ArrayList<>(), prefixes = new ArrayList<>();
        JSONArray fs = opts.optJSONArray("filters");
        if (fs != null) for (int i = 0; i < fs.length(); i++) {
            JSONObject f = fs.optJSONObject(i); if (f == null) continue;
            JSONArray ss = f.optJSONArray("services");
            if (ss != null) for (int j = 0; j < ss.length(); j++) {
                try { svc.add(UUID.fromString(ss.optString(j))); } catch (Exception ignored) { }
            }
            if (f.has("name")) names.add(f.optString("name"));
            if (f.has("namePrefix")) prefixes.add(f.optString("namePrefix"));
        }

        final BluetoothLeScanner sc = adapter.getBluetoothLeScanner();
        if (sc == null) { reply(cbId, false, "Bluetooth の検索を始められません"); return; }

        final List<BluetoothDevice> found = new ArrayList<>();
        final ArrayAdapter<String> list = new ArrayAdapter<>(act, android.R.layout.simple_list_item_1);
        final boolean[] done = {false};

        final ScanCallback cb = new ScanCallback() {
            @Override
            public void onScanResult(int type, ScanResult r) {
                BluetoothDevice d = r.getDevice();
                String n = r.getScanRecord() != null && r.getScanRecord().getDeviceName() != null
                        ? r.getScanRecord().getDeviceName() : d.getName();
                boolean ok = svc.isEmpty() && names.isEmpty() && prefixes.isEmpty();
                if (!ok && r.getScanRecord() != null && r.getScanRecord().getServiceUuids() != null)
                    for (ParcelUuid u : r.getScanRecord().getServiceUuids()) if (svc.contains(u.getUuid())) ok = true;
                if (!ok && n != null) {
                    if (names.contains(n)) ok = true;
                    for (String p : prefixes) if (n.startsWith(p)) ok = true;
                }
                if (!ok) return;
                final String label = (n == null ? "名前なし" : n) + "  (" + d.getAddress() + ")";
                main.post(() -> {
                    for (BluetoothDevice x : found) if (x.getAddress().equals(d.getAddress())) return;
                    found.add(d);
                    list.add(label);
                });
            }
        };

        // 名前で絞らず、サービスで絞らない広告も拾えるよう、絞り込みはこちらで行う
        ScanSettings set = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        try { sc.startScan(new ArrayList<ScanFilter>(), set, cb); }
        catch (Exception e) { reply(cbId, false, "Bluetooth の検索に失敗しました: " + e.getMessage()); return; }

        final AlertDialog dlg = new AlertDialog.Builder(act)
                .setTitle("SpeedTracer を探しています…")
                .setAdapter(list, (d, which) -> {
                    if (done[0]) return; done[0] = true;
                    try { sc.stopScan(cb); } catch (Exception ignored) { }
                    BluetoothDevice dev = found.get(which);
                    try {
                        JSONObject r = new JSONObject();
                        r.put("id", dev.getAddress());
                        r.put("name", dev.getName() == null ? "" : dev.getName());
                        reply(cbId, true, r);
                    } catch (Exception e) { reply(cbId, false, e.getMessage()); }
                })
                .setNegativeButton("キャンセル", (d, w) -> { })
                .setOnDismissListener(d -> {
                    try { sc.stopScan(cb); } catch (Exception ignored) { }
                    if (!done[0]) { done[0] = true; reply(cbId, false, "User cancelled the requestDevice() chooser."); }
                })
                .create();
        dlg.show();
        // 15秒で検索を止める(電池の節約)。見つかった一覧はそのまま選べる
        main.postDelayed(() -> {
            try { sc.stopScan(cb); } catch (Exception ignored) { }
            if (dlg.isShowing()) dlg.setTitle(found.isEmpty() ? "見つかりませんでした(電源と距離を確認)" : "つなぐ SpeedTracer を選んでください");
        }, 15000);
    }

    // ---------- 接続 ----------
    void connect(String address, int cbId) {
        if (!hasPerms()) { reply(cbId, false, "Bluetooth の権限がありません"); return; }
        disconnect();
        BluetoothDevice dev;
        try { dev = adapter.getRemoteDevice(address); }
        catch (Exception e) { reply(cbId, false, "機器が見つかりません"); return; }
        connectCbId = cbId;
        ops.clear(); running = null;
        gatt = dev.connectGatt(act, false, gattCb, BluetoothDevice.TRANSPORT_LE);
        // 10秒でつながらなければ失敗
        main.postDelayed(() -> {
            if (connectCbId == cbId) { connectCbId = 0; reply(cbId, false, "接続できませんでした(SpeedTracer の電源を確認)"); disconnect(); }
        }, 10000);
    }

    void disconnect() {
        if (gatt != null) {
            try { gatt.disconnect(); gatt.close(); } catch (Exception ignored) { }
            gatt = null;
        }
        ops.clear(); running = null;
    }

    void close() { disconnect(); }

    BluetoothGattService findService(String uuid) {
        if (gatt == null) return null;
        try { return gatt.getService(UUID.fromString(uuid)); } catch (Exception e) { return null; }
    }

    BluetoothGattCharacteristic findChar(String uuid) {
        if (gatt == null) return null;
        UUID u;
        try { u = UUID.fromString(uuid); } catch (Exception e) { return null; }
        for (BluetoothGattService s : gatt.getServices()) {
            BluetoothGattCharacteristic c = s.getCharacteristic(u);
            if (c != null) return c;
        }
        return null;
    }

    void hasService(String uuid, int cbId) {
        if (findService(uuid) != null) reply(cbId, true, null);
        else reply(cbId, false, "サービスが見つかりません: " + uuid);
    }

    void hasChar(String service, String uuid, int cbId) {
        BluetoothGattService s = findService(service);
        if (s != null && s.getCharacteristic(UUID.fromString(uuid)) != null) reply(cbId, true, null);
        else reply(cbId, false, "キャラクタリスティックが見つかりません: " + uuid);
    }

    // ---------- 順番待ち ----------
    void enqueue(Runnable start, int cbId) {
        ops.add(new Op(start, cbId));
        next();
    }

    void next() {
        if (running != null || ops.isEmpty()) return;
        running = ops.poll();
        try { running.start.run(); }
        catch (Exception e) { finish(false, e.getMessage()); }
    }

    void finish(boolean ok, String msg) {
        Op op = running; running = null;
        if (op != null) reply(op.cbId, ok, ok ? null : msg);
        main.post(this::next);
    }

    void startNotify(String uuid, int cbId) {
        BluetoothGattCharacteristic c = findChar(uuid);
        if (gatt == null || c == null) { finish(false, "通知を始められません"); return; }
        gatt.setCharacteristicNotification(c, true);
        BluetoothGattDescriptor d = c.getDescriptor(CCCD);
        if (d == null) { finish(true, null); return; }
        byte[] v = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
        boolean ok;
        if (Build.VERSION.SDK_INT >= 33) ok = gatt.writeDescriptor(d, v) == 0;
        else { d.setValue(v); ok = gatt.writeDescriptor(d); }
        if (!ok) finish(false, "通知の設定に失敗しました");
    }

    void write(String uuid, String b64, int cbId) {
        BluetoothGattCharacteristic c = findChar(uuid);
        if (gatt == null || c == null) { finish(false, "送信先が見つかりません"); return; }
        byte[] data = Base64.decode(b64, Base64.DEFAULT);
        int type = (c.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
                ? BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT : BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
        boolean ok;
        if (Build.VERSION.SDK_INT >= 33) ok = gatt.writeCharacteristic(c, data, type) == 0;
        else { c.setWriteType(type); c.setValue(data); ok = gatt.writeCharacteristic(c); }
        if (!ok) finish(false, "送信に失敗しました");
        else if (type == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) finish(true, null);
    }

    // ---------- GATT の知らせ ----------
    final BluetoothGattCallback gattCb = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int state) {
            main.post(() -> {
                if (g != gatt) return;
                if (state == BluetoothProfile.STATE_CONNECTED) {
                    if (!g.requestMtu(247)) g.discoverServices(); // 長い行を1回で受け取れるよう MTU を広げる
                } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                    int cb = connectCbId; connectCbId = 0;
                    if (cb != 0) reply(cb, false, "接続が切れました");
                    try { g.close(); } catch (Exception ignored) { }
                    gatt = null; ops.clear(); running = null;
                    js("window.__stBleDisconnected&&window.__stBleDisconnected()");
                }
            });
        }

        @Override
        public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
            main.post(() -> { if (g == gatt) g.discoverServices(); });
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            main.post(() -> {
                int cb = connectCbId; connectCbId = 0;
                if (cb == 0) return;
                if (status == BluetoothGatt.GATT_SUCCESS) reply(cb, true, null);
                else { reply(cb, false, "サービスを読めませんでした"); disconnect(); }
            });
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            main.post(() -> finish(status == BluetoothGatt.GATT_SUCCESS, "通知の設定に失敗しました"));
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            main.post(() -> finish(status == BluetoothGatt.GATT_SUCCESS, "送信に失敗しました"));
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
            deliver(c.getUuid(), value);
        }

        @Override
        @SuppressWarnings("deprecation")
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            if (Build.VERSION.SDK_INT < 33) deliver(c.getUuid(), c.getValue());
        }
    };

    void deliver(UUID uuid, byte[] value) {
        if (value == null) return;
        String b64 = Base64.encodeToString(value, Base64.NO_WRAP);
        js("window.__stBleNotify&&window.__stBleNotify('" + uuid.toString().toLowerCase() + "','" + b64 + "')");
    }
}
