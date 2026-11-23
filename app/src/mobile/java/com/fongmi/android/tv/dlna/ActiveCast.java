package com.fongmi.android.tv.dlna;

import android.app.Activity;
import android.content.Intent;
import android.text.format.DateUtils;

import androidx.appcompat.app.AlertDialog;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Device;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.service.CastRelayService;
import com.fongmi.android.tv.ui.activity.VideoActivity;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.net.OkHttp;
import org.jupnp.controlpoint.ActionCallback;
import org.jupnp.model.action.ActionInvocation;
import org.jupnp.model.message.UpnpResponse;
import org.jupnp.support.avtransport.callback.GetPositionInfo;
import org.jupnp.support.model.PositionInfo;

/** The live transport belongs to the application, independent of the device picker or player UI. */
public final class ActiveCast {
    private static ActiveCast current;
    private final Device device;
    private final History history;
    private final String relayUri;
    private long position;
    private boolean clockReported;
    private String status;

    private ActiveCast(Device device, History history, String relayUri) {
        this.device = device;
        this.history = History.objectFrom(history.toString());
        this.relayUri = relayUri;
        position = Math.max(0, history.getPosition());
        status = device.isDLNA() ? "电视已接收媒体，等待播放进度" : "电视已接收播放请求";
    }

    public static void begin(Device device, History history, String uri) {
        if (device != null && history != null) current = new ActiveCast(device, history, uri);
    }

    public static ActiveCast get() { return current; }
    public String label() { return device.getName() + " · " + history.getVodName() + " " + history.getVodRemarks(); }

    public static void relayEnded() {
        if (current != null && current.device.isDLNA()) current = null;
        DLNACastManager.get().release(App.get());
    }

    public static void show(Activity activity, Runnable chooseDevice) {
        ActiveCast session = current;
        if (session == null) return;
        String[] actions = chooseDevice == null ? new String[]{"暂停", "继续播放", "回到手机播放", "结束投屏"}
                : new String[]{"暂停", "继续播放", "回到手机播放", "结束投屏", "更换接收端"};
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle(session.label())
                .setItems(actions, (d, i) -> {
                    if (current != session) return;
                    if (i <= 1) session.command(i == 0 ? "Pause" : "Play", () -> Notify.show("电视已接收指令"));
                    else if (i == 4) session.stopFor(activity, chooseDevice);
                    else if (i == 3) session.stopFor(activity, () -> {});
                    else session.returnToPhone(activity);
                }).setNegativeButton("关闭", null).show();
        // Message and item lists share the AlertDialog body. Use its title for live transport feedback.
        Runnable update = () -> { if (dialog.isShowing()) dialog.setTitle(session.label() + "\n" + session.status
                + (session.clockReported ? " · " : " · 手机上次 ") + DateUtils.formatElapsedTime(session.position / 1000)); };
        update.run();
        Runnable poll = new Runnable() {
            @Override public void run() {
                if (current != session || !dialog.isShowing()) return;
                session.queryPosition(() -> {
                    update.run();
                    if (dialog.isShowing() && session.device.isDLNA()) App.post(this, 2000);
                });
            }
        };
        dialog.setOnDismissListener(ignored -> App.removeCallbacks(poll));
        poll.run();
    }

    private void queryPosition(Runnable done) {
        var manager = DLNACastManager.get();
        var control = manager.getControlPoint();
        var service = device.isDLNA() ? manager.findAVTransport(device) : null;
        if (service == null || control == null || service.getAction("GetPositionInfo") == null) { done.run(); return; }
        // Some TV firmware never answers the clock query. Always expose a usable last-known position.
        var completed = new java.util.concurrent.atomic.AtomicBoolean();
        Runnable finish = () -> {
            if (completed.compareAndSet(false, true) && current == this) done.run();
        };
        App.post(finish, 1800);
        control.execute(new GetPositionInfo(service) {
            public void received(ActionInvocation<?> call, PositionInfo info) {
                App.post(() -> {
                    if (completed.get() || current != ActiveCast.this) return;
                    String track = info.getTrackURI();
                    if (track != null && !track.isBlank() && !relayUri.equals(track)) {
                        status = "接收端已切换媒体";
                    } else if (info.getRelTime() != null && info.getRelTime().matches("[0-9]+:[0-9]{2}:[0-9]{2}(\\.[0-9]+)?")) {
                        long seconds = info.getTrackElapsedSeconds();
                        if (seconds >= 0) {
                            long elapsed = seconds * 1000L;
                            boolean advancing = clockReported && elapsed > position;
                            status = advancing ? "电视播放进度已推进" : "电视回报进度";
                            position = elapsed;
                            clockReported = true;
                        }
                    }
                    finish.run();
                });
            }
            public void failure(ActionInvocation call, UpnpResponse response, String message) { App.post(finish); }
        });
    }

    private void command(String action, Runnable success) {
        command(action, success, () -> Notify.show("电视未确认指令，可使用电视遥控器"));
    }

    private void command(String action, Runnable success, Runnable failure) {
        if (current != this) return;
        if ("接收端已切换媒体".equals(status)) { failure.run(); return; }
        if (!device.isDLNA()) {
            Task.execute(() -> {
                String url = device.getIp() + "/action?do=control&type=" + action.toLowerCase(java.util.Locale.ROOT);
                try (var response = OkHttp.newCall(OkHttp.client(Constant.TIMEOUT_SYNC), url).execute()) {
                    if (!response.isSuccessful()) throw new java.io.IOException();
                    App.post(() -> { if (current == this) success.run(); });
                } catch (Exception e) { App.post(failure); }
            });
            return;
        }
        var manager = DLNACastManager.get();
        var service = manager.findAVTransport(device);
        var control = manager.getControlPoint();
        if (service == null || control == null || service.getAction(action) == null) {
            failure.run();
            return;
        }
        var invocation = new ActionInvocation<>(service.getAction(action));
        invocation.setInput("InstanceID", 0);
        if (action.equals("Play")) invocation.setInput("Speed", "1");
        control.execute(new ActionCallback(invocation) {
            public void success(ActionInvocation call) { App.post(() -> { if (current == ActiveCast.this) success.run(); }); }
            public void failure(ActionInvocation call, UpnpResponse response, String message) {
                App.post(failure);
            }
        });
    }

    private void stopFor(Activity activity, Runnable next) {
        var answered = new java.util.concurrent.atomic.AtomicBoolean();
        Runnable proceed = () -> {
            if (current != this) return;
            end();
            if (!activity.isFinishing() && !activity.isDestroyed()) next.run();
        };
        Runnable offline = () -> {
            if (!answered.compareAndSet(false, true) || current != this || activity.isDestroyed() || activity.isFinishing()) return;
            new AlertDialog.Builder(activity).setTitle("电视未确认停止播放")
                    .setMessage("仍可在手机上继续。如电视仍在播放，可用遥控器停止。")
                    .setNegativeButton("取消", null).setPositiveButton("继续", (d, w) -> proceed.run()).show();
        };
        command("Stop", () -> { if (answered.compareAndSet(false, true)) proceed.run(); }, offline);
        App.post(offline, 3500);
    }

    private void returnToPhone(Activity activity) {
        if (VodConfig.getCid() != history.getCid()) { Notify.show("请先切回投屏时使用的点播订阅"); return; }
        queryPosition(() -> {
            if (activity.isDestroyed() || activity.isFinishing()) return;
            // Persist resume before VideoActivity reads the same key on its normal detail route.
            if (!device.isDLNA()) {
                History synced = History.find(history.getKey());
                if (synced != null && synced.getCreateTime() > history.getCreateTime()
                        && synced.getVodRemarks().equals(history.getVodRemarks())) position = synced.getPosition();
            }
            stopFor(activity, () -> {
                history.setPosition(position);
                history.setCreateTime(Math.max(System.currentTimeMillis(), history.getCreateTime() + 1));
                if (!com.fongmi.android.tv.setting.Setting.isIncognito()) history.save();
                if (activity instanceof VideoActivity video && history.getKey().equals(video.getHistoryKey()))
                    video.resumeCast(history);
                else VideoActivity.cast(activity, history);
            });
        });
    }

    private void end() {
        if (current != this) return;
        current = null;
        if (device.isDLNA()) {
            com.fongmi.android.tv.server.process.CastProxy.relay().stop();
            App.get().stopService(new Intent(App.get(), CastRelayService.class));
            DLNACastManager.get().release(App.get());
        }
    }
}
