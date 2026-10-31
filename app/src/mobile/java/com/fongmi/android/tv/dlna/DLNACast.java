package com.fongmi.android.tv.dlna;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.CastVideo;
import com.fongmi.android.tv.bean.Device;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.server.CastMediaRelay;
import com.fongmi.android.tv.server.CastStartupGate;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.server.process.CastProxy;
import com.fongmi.android.tv.service.CastRelayService;
import com.fongmi.android.tv.utils.Task;

import org.jupnp.controlpoint.ControlPoint;
import org.jupnp.model.action.ActionInvocation;
import org.jupnp.model.message.UpnpResponse;
import org.jupnp.model.meta.RemoteService;
import org.jupnp.support.avtransport.callback.Play;
import org.jupnp.support.avtransport.callback.GetTransportInfo;
import org.jupnp.support.avtransport.callback.GetPositionInfo;
import org.jupnp.support.model.TransportInfo;
import org.jupnp.support.model.TransportState;
import org.jupnp.support.model.PositionInfo;
import org.jupnp.support.avtransport.callback.Seek;
import org.jupnp.support.avtransport.callback.SetAVTransportURI;
import org.jupnp.support.contentdirectory.DIDLParser;
import org.jupnp.support.model.DIDLContent;
import org.jupnp.support.model.ProtocolInfo;
import org.jupnp.support.model.Res;
import org.jupnp.support.model.SeekMode;
import org.jupnp.support.model.item.VideoItem;

import java.util.Locale;

public final class DLNACast {
    private final CastVideo video;
    private final Runnable runnable;
    private final java.util.function.Consumer<String> failure;
    private volatile CastMediaRelay.Session session;
    private volatile boolean cancelled;
    private volatile boolean completed;
    private volatile boolean failed;
    private final CastStartupGate startup = new CastStartupGate();
    private long startTime;
    private boolean finishing;

    public DLNACast(CastVideo video, Runnable runnable, java.util.function.Consumer<String> failure) {
        this.video = video;
        this.runnable = runnable;
        this.failure = failure;
    }
    public DLNACast(CastVideo video, Runnable runnable) { this(video, runnable, Notify::show); }

    public void cast(Device item) {
        startTime = System.nanoTime();
        DLNACastManager mgr = DLNACastManager.get();
        ControlPoint control = mgr.getControlPoint();
        RemoteService service = mgr.findAVTransport(item);
        if (service != null && control != null) {
            try {
                Server.get().start();
                session = CastProxy.relay().open(Server.get().getAddress(), video.url(), video.headers());
                CastRelayService.start(App.get(), video.name());
                Task.execute(() -> {
                    try {
                        CastProxy.relay().prepare(session);
                        if (active()) control.execute(uriAction(control, service));
                    } catch (Exception e) {
                        fail("视频转发准备失败，请更换线路或检查网络后重试");
                    }
                });
            } catch (Exception e) {
                fail("无法启动投屏，请连接与电视相同的 Wi-Fi 后重试");
            }
        } else {
            App.post(() -> failure.accept(com.fongmi.android.tv.utils.ResUtil.getString(R.string.device_offline)));
        }
    }

    /** Closing the sheet after success must not tear down the active media relay. */
    public void cancel() {
        if (completed) return;
        cancelled = true;
        if (session != null) CastProxy.relay().close(session);
    }

    private void fail(String message) {
        if (cancelled || failed || completed) return;
        failed = true;
        if (session != null) CastProxy.relay().close(session);
        App.post(() -> { if (!cancelled) failure.accept(message); });
    }

    private String buildMetaData() {
        try {
            DIDLContent content = new DIDLContent();
            Res resource = new Res(new ProtocolInfo("http-get:*:" + session.mimeType()
                    + ":DLNA.ORG_OP=" + (session.canByteSeek() ? "01" : "00") + ";DLNA.ORG_CI=0"), session.length(), session.url());
            if (video.width() > 0 && video.height() > 0) resource.setResolution(video.width(), video.height());
            VideoItem item = new VideoItem("0", "-1", video.name(), "", resource);
            content.addItem(item);
            return new DIDLParser().generate(content);
        } catch (Exception e) {
            return "";
        }
    }

    private SetAVTransportURI uriAction(ControlPoint control, RemoteService service) {
        return new SetAVTransportURI(service, session.url(), buildMetaData()) {
            @Override
            public void success(ActionInvocation i) {
                if (active()) control.execute(playAction(control, service));
            }

            @Override
            public void failure(ActionInvocation i, UpnpResponse r, String msg) {
                fail(msg);
            }
        };
    }

    private Play playAction(ControlPoint control, RemoteService service) {
        return new Play(service) {
            @Override
            public void success(ActionInvocation i) {
                if (!active()) return;
                // Play only acknowledges the command. Seek during TRANSITIONING can make
                // receivers download up to the old position before showing their first frame.
                App.post(() -> pollPlayback(control, service));
            }

            @Override
            public void failure(ActionInvocation i, UpnpResponse r, String msg) {
                fail(msg);
            }
        };
    }

    private boolean active() { return !cancelled && !completed && !failed; }

    private void pollPlayback(ControlPoint control, RemoteService service) {
        if (!active() || finishing) return;
        if ((System.nanoTime() - startTime) / 1_000_000L > 40_000) {
            fail(session.hasMedia() ? "电视已收到视频但尚未开始播放，请尝试兼容线路或电视版接收端"
                    : "电视未能读取视频，请检查网络或更换线路");
            return;
        }
        control.execute(new GetTransportInfo(service) {
            @Override public void received(ActionInvocation<?> invocation, TransportInfo info) {
                boolean playing = info.getCurrentTransportState() == TransportState.PLAYING;
                App.post(() -> {
                    if (!active()) return;
                    if (playing && service.getAction("GetPositionInfo") != null) readPosition(control, service);
                    else observed(control, service, playing, -1);
                });
            }
            @Override public void failure(ActionInvocation invocation, UpnpResponse response, String message) {
                App.post(() -> { if (active()) App.post(() -> pollPlayback(control, service), 750); });
            }
        });
    }

    private void readPosition(ControlPoint control, RemoteService service) {
        control.execute(new GetPositionInfo(service) {
            @Override public void received(ActionInvocation<?> invocation, PositionInfo position) {
                long seconds = -1;
                try {
                    if (position.getRelTime() != null && position.getRelTime().matches("[0-9]+:[0-9]{2}:[0-9]{2}(\\.[0-9]+)?"))
                        seconds = position.getTrackElapsedSeconds();
                } catch (RuntimeException ignored) { }
                long elapsed = seconds;
                App.post(() -> observed(control, service, true, elapsed));
            }
            @Override public void failure(ActionInvocation invocation, UpnpResponse response, String message) {
                App.post(() -> observed(control, service, true, -1));
            }
        });
    }

    private void observed(ControlPoint control, RemoteService service, boolean playing, long seconds) {
        if (!active() || finishing) return;
        if (!startup.observe(System.nanoTime() / 1_000_000L, session.hasMedia(), playing, seconds)) {
            App.post(() -> pollPlayback(control, service), 500);
            return;
        }
        finishing = true;
        if (startup.shouldResume(session.canSeek(), video.position()) && service.getAction("Seek") != null) {
            control.execute(new Seek(service, SeekMode.REL_TIME, formatMs(video.position())) {
                @Override public void success(ActionInvocation invocation) { App.post(DLNACast.this::complete); }
                @Override public void failure(ActionInvocation invocation, UpnpResponse response, String message) { App.post(DLNACast.this::complete); }
            });
            // A receiver with a broken Seek implementation must not leave the sheet open forever.
            App.post(this::complete, 3000);
        } else complete();
    }

    private void complete() {
        if (!active()) return;
        completed = true;
        runnable.run();
    }

    private String formatMs(long ms) {
        if (ms <= 0) return "00:00:00";
        long s = ms / 1000;
        return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60);
    }
}
