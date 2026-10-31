package com.fongmi.android.tv.dlna;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.CastVideo;
import com.fongmi.android.tv.bean.Device;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.server.CastMediaRelay;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.server.process.CastProxy;
import com.fongmi.android.tv.service.CastRelayService;
import com.fongmi.android.tv.utils.Task;

import org.jupnp.controlpoint.ControlPoint;
import org.jupnp.model.action.ActionInvocation;
import org.jupnp.model.message.UpnpResponse;
import org.jupnp.model.meta.RemoteService;
import org.jupnp.support.avtransport.callback.Play;
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
    private boolean completed;

    public DLNACast(CastVideo video, Runnable runnable, java.util.function.Consumer<String> failure) {
        this.video = video;
        this.runnable = runnable;
        this.failure = failure;
    }
    public DLNACast(CastVideo video, Runnable runnable) { this(video, runnable, Notify::show); }

    public void cast(Device item) {
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
                        if (!cancelled) control.execute(uriAction(control, service));
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
        if (cancelled) return;
        if (session != null) CastProxy.relay().close(session);
        App.post(() -> { if (!cancelled) failure.accept(message); });
    }

    private String buildMetaData() {
        try {
            DIDLContent content = new DIDLContent();
            VideoItem item = new VideoItem("0", "-1", video.name(), "", new Res(new ProtocolInfo("http-get:*:" + session.mimeType() + ":*"), null, session.url()));
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
                if (!cancelled) control.execute(playAction(control, service));
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
                if (cancelled) return;
                if (video.position() > 0) control.execute(seekAction(service));
                Task.execute(() -> {
                    try {
                        if (!session.awaitMedia(10_000)) {
                            fail("电视未能读取视频，请检查手机和电视是否在同一 Wi-Fi");
                            return;
                        }
                        App.post(() -> {
                            if (cancelled) return;
                            completed = true;
                            runnable.run();
                        });
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        fail("投屏已中断，请重试");
                    }
                });
            }

            @Override
            public void failure(ActionInvocation i, UpnpResponse r, String msg) {
                fail(msg);
            }
        };
    }

    private Seek seekAction(RemoteService service) {
        return new Seek(service, SeekMode.REL_TIME, formatMs(video.position())) {
            @Override
            public void success(ActionInvocation i) {
            }

            @Override
            public void failure(ActionInvocation i, UpnpResponse r, String m) {
            }
        };
    }

    private String formatMs(long ms) {
        if (ms <= 0) return "00:00:00";
        long s = ms / 1000;
        return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60);
    }
}
