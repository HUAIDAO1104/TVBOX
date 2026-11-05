package com.fongmi.android.tv.ui.activity;

import android.content.Context;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.MimeTypes;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory;
import androidx.media3.extractor.ts.TsExtractor;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.player.track.LangUtil;
import com.fongmi.android.tv.service.PlaybackService;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.view.WindowManager;
import androidx.media3.common.Player;
import androidx.media3.common.PlaybackException;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.offline.Download;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.danmaku.DanmakuController;
import androidx.viewbinding.ViewBinding;
import com.fongmi.android.tv.databinding.ActivityOfflinePlayerBinding;
import com.fongmi.android.tv.offline.*;
import com.fongmi.android.tv.player.danmaku.DanmakuPlaybackSync;
import com.fongmi.android.tv.player.danmaku.FilteringBiliParser;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.setting.DanmakuSetting;
import com.fongmi.android.tv.ui.base.BaseActivity;
import java.io.IOException;

public final class OfflinePlayerActivity extends BaseActivity {
    private ActivityOfflinePlayerBinding binding;
    private OfflineStore store;
    private ExoPlayer player;
    private DanmakuPlaybackSync sync;
    private String id;
    private boolean showDanmaku = true;
    public static void start(Context context, String id) { context.startActivity(new Intent(context, OfflinePlayerActivity.class).putExtra("id", id)); }
    @Override protected ViewBinding getBinding() { return binding = ActivityOfflinePlayerBinding.inflate(getLayoutInflater()); }
    @Override protected void initView(Bundle state) {
        id = getIntent().getStringExtra("id"); store = OfflineStore.get(this);
        if (state != null) showDanmaku = state.getBoolean("danmaku", true);
        try { OfflineMetadata.requireId(id); } catch (IllegalArgumentException e) { finish(); return; }
        sync = new DanmakuPlaybackSync(binding.player.getDanmakuView());
        binding.player.getDanmakuController().registerParser(FilteringBiliParser.INSTANCE);
        // Local XML only: even a future parser change must never reach the network here.
        binding.player.setDanmakuOkHttpClient(new okhttp3.OkHttpClient.Builder().addInterceptor(chain -> { throw new IOException("Offline only"); }).build());
        binding.player.setDanmakuConfig(DanmakuSetting.getConfig());
        binding.player.setDanmakuEnabled(showDanmaku);
        binding.player.getDanmakuController().setListener(new DanmakuController.Listener() {
            @Override public void onLoadCompleted(Uri uri, int count) { binding.player.setDanmakuSource(uri); if (sync != null) sync.sync(); }
            @Override public void onLoadError(Uri uri, IOException e) { binding.status.setText("本地弹幕无法读取，请到下载管理重新导入"); }
        });
    }
    @Override protected void initEvent() {
        binding.danmaku.setOnClickListener(v -> {
            showDanmaku = !showDanmaku; binding.player.setDanmakuEnabled(showDanmaku);
            binding.danmaku.setText(showDanmaku ? "弹幕：开" : "弹幕：关"); if (sync != null) sync.sync();
        });
        binding.rotate.setOnClickListener(v -> setRequestedOrientation(getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT : ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE));
    }
    @Override protected void onStart() {
        super.onStart();
        if (isFinishing()) return;
        Download download = store.download(id);
        if (download == null || download.state != Download.STATE_COMPLETED) { binding.status.setText("视频尚未下载完成，请返回下载管理"); return; }
        PlaybackService online = Server.get().getService();
        if (online != null && online.player() != null && !online.player().isReleased()) online.player().pause();
        OfflineMetadata meta = OfflineMetadata.decode(download.request.data);
        binding.mobileToolbar.setTitle(meta.title);
        binding.status.setText(meta.episode + " · " + store.extrasStatus(download));
        // Keep the same codec fallbacks, audio output and subtitle preferences as online playback.
        DefaultRenderersFactory renderers = new DefaultRenderersFactory(this) {
            @Override protected AudioSink buildAudioSink(Context context, boolean floatOutput, boolean playbackParams) {
                DefaultAudioSink.Builder sink = new DefaultAudioSink.Builder(context)
                        .setEnableFloatOutput(floatOutput).setEnableAudioOutputPlaybackParameters(playbackParams);
                if (!PlayerSetting.isAudioPassThrough())
                    sink.setAudioOutputProvider(new AudioTrackAudioOutputProvider.Builder(null).build());
                return sink.build();
            }
        }.setEnableDecoderFallback(true)
                .setFfmpegAudioPrefer(PlayerSetting.isAudioPrefer())
                .setFfmpegVideoPrefer(PlayerSetting.isVideoPrefer())
                .setEnableDv7HevcFallback(PlayerSetting.isDv7HevcFallback())
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON);
        DefaultTrackSelector tracks = new DefaultTrackSelector(this);
        DefaultTrackSelector.Parameters.Builder parameters = tracks.buildUponParameters()
                .setPreferredTextLanguages(LangUtil.getPreferredTextLanguages());
        if (PlayerSetting.isPreferAAC()) parameters.setPreferredAudioMimeType(MimeTypes.AUDIO_AAC);
        tracks.setParameters(parameters.build());
        DefaultExtractorsFactory extractors = new DefaultExtractorsFactory()
                .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS)
                .setTsExtractorTimestampSearchBytes(TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES * 10);
        player = new ExoPlayer.Builder(this, renderers).setTrackSelector(tracks)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(store.playbackFactory(id), extractors)).build();
        player.setAudioAttributes(AudioAttributes.DEFAULT, true);
        player.setHandleAudioBecomingNoisy(true);
        player.addListener(new Player.Listener() {
            @Override public void onPlayerError(PlaybackException error) { binding.status.setText("本地视频缺失、损坏或格式不支持，请返回下载管理重新下载或换用其他线路"); }
            @Override public void onPlaybackStateChanged(int state) { if (state == Player.STATE_ENDED) store.position(id, 0); }
        });
        binding.player.setPlayer(player); sync.bind(player);
        binding.player.onResume();
        if (store.danmaku(id).isFile()) binding.player.setDanmakuSource(Uri.fromFile(store.danmaku(id)));
        player.setMediaItem(store.mediaItem(download)); player.seekTo(store.position(id)); player.prepare(); player.play();
        binding.danmaku.setText(showDanmaku ? "弹幕：开" : "弹幕：关");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
    @Override protected void onStop() {
        if (player != null) {
            store.position(id, player.getPlaybackState() == Player.STATE_ENDED ? 0 : player.getCurrentPosition());
            sync.bind(null); binding.player.setPlayer(null); player.release(); player = null;
        }
        binding.player.onPause(); getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        super.onStop();
    }
    @Override protected void onDestroy() { binding.player.setDanmakuSource(null); binding.player.getDanmakuController().setListener(null); binding.player.getDanmakuController().release(); super.onDestroy(); }
    @Override protected void onSaveInstanceState(Bundle state) { state.putBoolean("danmaku", showDanmaku); super.onSaveInstanceState(state); }
}
