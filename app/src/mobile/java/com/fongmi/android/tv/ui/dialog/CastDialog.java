package com.fongmi.android.tv.ui.dialog;

import android.app.Activity;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.bean.CastVideo;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Device;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.databinding.DialogCastBinding;
import com.fongmi.android.tv.dlna.DLNACast;
import com.fongmi.android.tv.dlna.DLNACastManager;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.ui.activity.ScanActivity;
import com.fongmi.android.tv.ui.adapter.DeviceAdapter;
import com.fongmi.android.tv.ui.custom.SpaceItemDecoration;
import com.fongmi.android.tv.utils.ScanTask;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Path;
import com.github.catvod.utils.Util;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Response;

public class CastDialog extends MobileSheetDialog implements DeviceAdapter.OnClickListener, ScanTask.Listener, DLNACastManager.DeviceListener, Callback {

    private final FormBody.Builder body;
    private final OkHttpClient client;

    private DialogCastBinding binding;
    private DeviceAdapter adapter;
    private ScanTask scanTask;
    private CastVideo video;
    private String historyJson;
    private boolean fm;
    private Call castCall;
    private final java.util.Map<String, Device> discovered = new java.util.LinkedHashMap<>();
    private String localUuid;
    private boolean connecting;
    private boolean discovering = true;
    private int castGeneration;
    private final Runnable connectionTimeout = () -> {
        if (binding == null || !connecting) return;
        castGeneration++; connecting = false;
        if (castCall != null) castCall.cancel();
        binding.status.setText("连接超时，请重试或选择其他设备");
    };
    private final Runnable discoveryFinished = () -> {
        if (binding != null && discovering) binding.status.setText(discovered.isEmpty() ? "暂未发现设备。请检查同一 Wi-Fi、电视投屏开关，再重新搜索。" : "选择设备开始播放");
    };

    public CastDialog() {
        scanTask = new ScanTask(this);
        body = new FormBody.Builder();
        body.add("device", Device.get().toString());
        body.add("config", Config.vod().toString());
        client = OkHttp.client(Constant.TIMEOUT_SYNC);
    }

    public static CastDialog create() {
        return new CastDialog();
    }

    public CastDialog history(History history) {
        if (history == null) return this;
        String id = history.getVodId();
        String fd = history.getVodId();
        if (fd.startsWith("/")) fd = Server.get().getAddress() + "/file" + fd.replace(Path.rootPath(), "");
        if (fd.startsWith("file")) fd = Server.get().getAddress() + "/" + fd.replace(Path.rootPath(), "").replace("://", "");
        if (fd.contains("127.0.0.1")) fd = fd.replace("127.0.0.1", Util.getIp());
        historyJson = history.toString().replace(id, fd);
        return this;
    }

    public CastDialog video(CastVideo video) {
        this.video = video;
        return this;
    }

    public CastDialog fm(boolean fm) {
        this.fm = fm;
        return this;
    }

    public void show(FragmentActivity activity) {
        if (activity.getSupportFragmentManager().isStateSaved()) return;
        for (Fragment f : activity.getSupportFragmentManager().getFragments()) if (f instanceof CastDialog) return;
        show(activity.getSupportFragmentManager(), null);
    }

    @Override protected float heightFraction() { return getResources().getConfiguration().screenHeightDp < 480 ? 0.96f : 0.66f; }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return binding = DialogCastBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        if (video == null && getActivity() instanceof Host host) {
            video(host.castVideo()); history(host.castHistory()); fm(true);
        }
        localUuid = Device.get().getUuid();
        scanTask = new ScanTask(this);
        binding.scan.setVisibility(fm ? View.VISIBLE : View.GONE);
        DLNACastManager.get().init(requireActivity());
        DLNACastManager.get().setDeviceListener(this);
        setRecyclerView();
        getDevice();
    }

    @Override
    protected void initEvent() {
        binding.close.setOnClickListener(v -> dismiss());
        binding.scan.setOnClickListener(v -> onScan());
        binding.refresh.setOnClickListener(v -> onRefresh());
    }

    private void setRecyclerView() {
        binding.recycler.setHasFixedSize(false);
        binding.recycler.setItemAnimator(null);
        binding.recycler.setAdapter(adapter = new DeviceAdapter(this));
        binding.recycler.addItemDecoration(new SpaceItemDecoration(1, 16));
    }

    private void renderDevices() {
        if (binding == null) return;
        adapter.setItems(discovered.values().stream().sorted().toList());
        if (discovering && !discovered.isEmpty()) binding.status.setText("选择设备开始播放");
    }

    private String deviceKey(Device device) { return device.getUuid().isEmpty() ? device.getIp() : device.getUuid(); }

    private void addDevice(Device device) {
        if (device.isApp() && device.getUuid().equals(localUuid)) return;
        discovered.put(deviceKey(device), device);
    }

    private void getDevice() {
        for (Device device : Device.getAll()) addDevice(device);
        for (Device device : DLNACastManager.get().getRegistered()) addDevice(device);
        renderDevices();
        if (discovered.isEmpty()) onRefresh();
        else DLNACastManager.get().search();
    }

    private void onScan() {
        launcher.launch(new Intent(requireActivity(), ScanActivity.class));
    }

    private void onRefresh() {
        if (binding == null) return;
        if (castCall != null) castCall.cancel();
        connecting = false; discovering = true; castGeneration++;
        App.removeCallbacks(connectionTimeout);
        binding.status.setText("正在查找附近的设备…");
        App.post(discoveryFinished, 10000);
        discovered.clear();
        for (Device device : DLNACastManager.get().getRegistered()) addDevice(device);
        renderDevices();
        scanTask.stop(); scanTask = new ScanTask(this);
        if (fm) scanTask.start();
        DLNACastManager.get().search();
    }

    private void onCasted() {
        if (binding == null || !(getActivity() instanceof Listener host)) return;
        connecting = false; App.removeCallbacks(connectionTimeout);
        host.onCasted();
        dismiss();
    }

    @Override
    public void onDeviceAdded(Device device) {
        App.post(() -> { if (binding != null) { addDevice(device); renderDevices(); } });
    }

    @Override
    public void onDeviceRemoved(Device device) {
        App.post(() -> { if (binding != null) { discovered.remove(deviceKey(device)); renderDevices(); if (discovered.isEmpty() && discovering) discoveryFinished.run(); } });
    }

    @Override
    public void onFind(Device device) {
        App.post(() -> { if (binding != null) { addDevice(device); renderDevices(); } });
    }

    @Override
    public void onFailure(@NonNull Call call, @NonNull IOException e) {
        App.post(() -> { if (binding != null && castCall == call && !call.isCanceled()) { connecting = false; App.removeCallbacks(connectionTimeout); binding.status.setText("连接失败，请检查电视状态后重试"); } });
    }

    @Override
    public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
        try (Response res = response) {
            if (res.isSuccessful() && res.body() != null && res.body().string().equals("OK")) App.post(() -> { if (castCall == call && !call.isCanceled()) onCasted(); });
            else App.post(() -> { if (binding != null && castCall == call && !call.isCanceled()) { connecting = false; App.removeCallbacks(connectionTimeout); binding.status.setText("设备未响应，请重试或选择其他设备"); } });
        }
    }

    @Override
    public void onItemClick(Device item) {
        if (binding == null) return;
        if (getActivity() instanceof Host host) { video = host.castVideo(); history(host.castHistory()); }
        if (video == null) { binding.status.setText("视频尚未准备好，请返回播放页重试"); return; }
        if (connecting) return;
        connecting = true; discovering = false;
        int generation = ++castGeneration;
        App.post(connectionTimeout, 15000);
        if (castCall != null) castCall.cancel();
        App.removeCallbacks(discoveryFinished);
        binding.status.setText("正在连接 " + item.getName() + "…");
        if (item.isDLNA()) new DLNACast(video, () -> { if (generation == castGeneration) onCasted(); }, message -> {
            if (binding != null && generation == castGeneration) { connecting = false; App.removeCallbacks(connectionTimeout); binding.status.setText("投屏失败，请检查电视状态后重试"); }
        }).cast(item);
        else { castCall = OkHttp.newCall(client, item.getIp().concat("/action?do=cast"), requestBody()); castCall.enqueue(this); }
    }

    private FormBody requestBody() {
        FormBody base = body.build();
        FormBody.Builder request = new FormBody.Builder();
        for (int i = 0; i < base.size(); i++) request.add(base.name(i), base.value(i));
        if (historyJson != null) request.add("history", historyJson);
        return request.build();
    }

    @Override
    public boolean onLongClick(Device item) {
        return false;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        DLNACastManager.get().setDeviceListener(null);
        DLNACastManager.get().release(requireActivity());
        scanTask.stop();
        castGeneration++; connecting = false;
        App.removeCallbacks(discoveryFinished, connectionTimeout);
        if (castCall != null) castCall.cancel();
        binding = null;
    }

    private final ActivityResultLauncher<Intent> launcher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
        if (binding != null && result.getResultCode() == Activity.RESULT_OK && result.getData() != null) scanTask.start(result.getData().getStringExtra("address"));
    });

    public interface Host {
        CastVideo castVideo();
        History castHistory();
    }

    public interface Listener {

        void onCasted();
    }
}
