package com.fongmi.android.tv.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.text.TextUtils;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.databinding.ActivityCollectBinding;
import com.fongmi.android.tv.databinding.ActivityHistoryBinding;
import com.fongmi.android.tv.databinding.ActivityHomeBinding;
import com.fongmi.android.tv.databinding.ActivitySettingDanmakuBinding;
import com.fongmi.android.tv.databinding.ActivityVideoBinding;
import com.fongmi.android.tv.databinding.AdapterSearchWorkBinding;
import com.fongmi.android.tv.databinding.AdapterEpisodeBinding;
import com.fongmi.android.tv.databinding.DialogRepositoryEditBinding;
import com.fongmi.android.tv.model.SearchProgress;
import com.fongmi.android.tv.playback.vod.DetailFocusPolicy;
import com.fongmi.android.tv.service.PlaybackService;
import com.fongmi.android.tv.ui.activity.VideoActivity;
import com.fongmi.android.tv.ui.adapter.EpisodeAdapter;
import com.fongmi.android.tv.ui.adapter.FlagAdapter;
import com.fongmi.android.tv.ui.search.SearchSource;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

@RunWith(AndroidJUnit4.class)
public class SearchAndPlaybackInstrumentedTest {

    @Test
    public void videoDetailStartsBeforePlaybackServiceBindingCompletes() {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        var context = instrumentation.getTargetContext();
        context.stopService(new Intent(context, PlaybackService.class));

        Intent intent = new Intent(context, VideoActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("start_mode", "DETAIL")
                .putExtra("key", "push")
                .putExtra("id", "https://fixture.invalid/video.mp4")
                .putExtra("name", "播放服务异步绑定回归测试");

        Activity activity = instrumentation.startActivitySync(intent);
        assertNotNull(activity);
        instrumentation.runOnMainSync(activity::finish);
        instrumentation.waitForIdleSync();
    }

    @Test
    public void searchSourceInitializesAndSplitsUnicodeSeparatorsOnAndroid() {
        SearchSource source = SearchSource.builder()
                .repositoryId("fixture-repository")
                .siteKey("fixture-site")
                .vodId("fixture-vod")
                .title("Fixture title")
                .actors("张若昀\u3000李沁\u00A0陈道明")
                .build();

        assertEquals(Set.of("张若昀", "李沁", "陈道明"), source.actorKeys());
    }

    @Test
    public void aggregateDedupRemovesRepeatedIdButKeepsAlternativeId() {
        Vod first = vod("source-a:42", "Fixture movie");
        Vod duplicate = vod("source-a:42", "Fixture movie duplicate");
        Vod alternative = vod("source-b:99", "Fixture movie");

        List<Vod> distinct = List.of(first, duplicate, alternative).stream().distinct().toList();

        assertEquals(2, distinct.size());
        assertTrue(distinct.contains(first));
        assertTrue(distinct.contains(alternative));
    }

    @Test
    public void aggregateDedupUsesNameOnlyWhenIdsAreMissing() {
        Vod first = vod("", "Fixture movie");
        Vod duplicate = vod("", "Fixture movie");
        Vod other = vod("", "Another fixture");

        assertEquals(2, List.of(first, duplicate, other).stream().distinct().count());
    }

    @Test
    public void leanbackDetailAndPlayNowModesHaveDifferentAutoplaySemantics() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            VideoActivity activity = new VideoActivity();
            activity.setIntent(new Intent().putExtra("start_mode", "DETAIL"));
            assertFalse(activity.shouldAutoPlayOnDetail());

            activity.setIntent(new Intent().putExtra("start_mode", "PLAY_NOW"));
            assertTrue(activity.shouldAutoPlayOnDetail());
        });
    }

    @Test
    public void aggregateProgressCountsEmptySourceWithoutDiscardingResults() {
        SearchProgress progress = SearchProgress.started(12, 2)
                .result(Result.list(List.of(new Vod())))
                .result(Result.empty());

        assertFalse(progress.running());
        assertEquals(1, progress.successful());
        assertEquals(1, progress.empty());
        assertEquals(1, progress.resultCount());
    }

    @Test
    public void aggregateResultLayoutKeepsFocusInsideBodyAndUsesThreeByFourPoster() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        var inflater = LayoutInflater.from(context);
        ActivityCollectBinding page = ActivityCollectBinding.inflate(inflater);
        AdapterSearchWorkBinding card = AdapterSearchWorkBinding.inflate(
                inflater, new FrameLayout(context), false);

        assertTrue(page.body.getClipChildren());
        assertTrue(page.body.getClipToPadding());
        assertFalse(page.resultRecycler.getClipToPadding());
        assertTrue(page.resultRecycler.getPaddingTop() > 0);
        card.getRoot().measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        card.getRoot().layout(0, 0, card.getRoot().getMeasuredWidth(), card.getRoot().getMeasuredHeight());
        assertTrue(card.poster.getWidth() > 0);
        assertTrue(Math.abs(card.poster.getWidth() * 4 - card.poster.getHeight() * 3) <= 3);
            });
    }

    @Test
    public void danmakuDetailSettingsAreDiscoverableFromPlayerAndSettingsPage() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
        var targetContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        var context = new ContextThemeWrapper(targetContext, R.style.Theme_App);
        ActivityVideoBinding video = ActivityVideoBinding.inflate(LayoutInflater.from(context));
        ActivitySettingDanmakuBinding settings = ActivitySettingDanmakuBinding.inflate(LayoutInflater.from(context));

        assertNotNull(video.control.action.danmakuSetting);
        assertEquals(targetContext.getString(R.string.danmaku_setting), video.control.action.danmakuSetting.getText().toString());
        assertTrue(settings.danmakuDetail.isFocusable());
        assertTrue(settings.danmakuSearch.isFocusable());
        assertEquals(targetContext.getString(R.string.danmaku_manual_search),
                ((android.widget.TextView) settings.danmakuSearch.getChildAt(0)).getText().toString());
            });
    }

    @Test
    public void homeStageLeavesSpaceForHistoryAndHasFullPageEntry() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
        var targetContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        var context = new ContextThemeWrapper(targetContext, R.style.Theme_App);
        ActivityHomeBinding home = ActivityHomeBinding.inflate(LayoutInflater.from(context));
        ActivityHistoryBinding history = ActivityHistoryBinding.inflate(LayoutInflater.from(context));

        int stageHeight = home.heroStage.getLayoutParams().height;
        int screenHeight = targetContext.getResources().getDisplayMetrics().heightPixels;
        assertTrue(stageHeight > 0 && stageHeight < screenHeight * 0.60f);
        assertTrue(home.historyMore.isFocusable());
        assertNotNull(history.recycler);
        assertTrue(history.clear.isFocusable());
            });
    }

    @Test
    public void repositoryEditorOffersQrAndBuiltInBackupRepository() throws Exception {
        var targetContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        var context = new ContextThemeWrapper(targetContext, R.style.Theme_App);
        DialogRepositoryEditBinding editor = DialogRepositoryEditBinding.inflate(LayoutInflater.from(context));
        assertNotNull(editor.code);
        assertTrue(editor.url.isFocusable());

        try (InputStreamReader reader = new InputStreamReader(
                targetContext.getAssets().open("repositories.json"), StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            var repositories = root.getAsJsonArray("repositories");
            JsonObject primary = repositories.get(0).getAsJsonObject();
            JsonObject backup = repositories.get(1).getAsJsonObject();
            assertTrue(root.get("version").getAsInt() >= 6);
            assertEquals(2, repositories.size());
            assertEquals("default-qist-tvbox", primary.get("stableId").getAsString());
            assertEquals("默认内容仓库", primary.get("name").getAsString());
            assertEquals("https://raw.githubusercontent.com/qist/tvbox/master/fty.json", primary.get("url").getAsString());
            assertEquals("backup-tomorrow-lmw", backup.get("stableId").getAsString());
            assertEquals("https://gh.llkk.cc/https://raw.githubusercontent.com/tushen6/Tomorrow/master/lmw.json", backup.get("url").getAsString());
            assertEquals(15, backup.getAsJsonArray("items").size());
            assertTrue(primary.get("builtIn").getAsBoolean());
            assertTrue(backup.get("builtIn").getAsBoolean());
        }
    }

    @Test
    public void detailEpisodeGridVirtualizesSixHundredItemsAndMarqueesLongNames() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            try {
                var targetContext = instrumentation.getTargetContext();
                var context = new ContextThemeWrapper(targetContext, R.style.Theme_App);
                ActivityVideoBinding binding = ActivityVideoBinding.inflate(LayoutInflater.from(context));
                binding.progressLayout.showContent();
                binding.name.setText("百花杀");
                binding.remark.setText("2026 · 中国大陆 · 古装 · 更新至16集");
                binding.site.setText("来源 · 热播");
                binding.summary.setText("改编自同名小说。克制的层级和留白让播放、来源与选集保持清晰。 ");
                binding.director.setText("导演：示例导演");
                binding.actor.setText("演员：示例演员甲、示例演员乙");
                binding.detailPoster.setImageResource(R.drawable.artwork);
                binding.detailPreview.setImageResource(R.drawable.artwork);
                binding.detailPreviewBackdrop.setImageResource(R.drawable.artwork);
                binding.detailBackdrop.setImageResource(R.drawable.artwork);
                binding.sourceHeader.setVisibility(View.VISIBLE);
                binding.flag.setVisibility(View.VISIBLE);
                binding.episodeTitle.setVisibility(View.VISIBLE);
                binding.episode.setVisibility(View.VISIBLE);

                String fullName = "第16集 · 百花杀特别加长版：锦衣夜行与旧案重启（4K 杜比视界 国语中字）"
                        + " · 网盘原始文件名完整展示，不再与上一行重叠或被强制省略"
                        + " · 这是用于验证超长名称自动换行的附加说明，包含版本、语言、画质和更新状态"
                        + " · 聚焦后应在卡片内部匀速滚动显示";

                Flag first = Flag.create("线路二");
                first.setSelected(first);
                FlagAdapter flagAdapter = new FlagAdapter(item -> { });
                flagAdapter.addAll(List.of(first, Flag.create("线路三"), Flag.create("线路四")));
                binding.flag.setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
                binding.flag.setAdapter(flagAdapter);

                GridLayoutManager layout = new GridLayoutManager(context, 2);
                binding.episode.setLayoutManager(layout);
                binding.episode.setItemAnimator(null);
                EpisodeAdapter episodeAdapter = new EpisodeAdapter(item -> { });
                ArrayList<Episode> episodes = new ArrayList<>();
                for (int i = 1; i <= 600; i++) episodes.add(Episode.create("第" + i + "集", "https://fixture.invalid/" + i));
                episodes.set(15, Episode.create(fullName, "https://fixture.invalid/16"));
                episodes.get(15).setSelected(true);
                episodeAdapter.addAll(episodes);
                binding.episode.setAdapter(episodeAdapter);
                ViewGroup.LayoutParams episodeParams = binding.episode.getLayoutParams();
                episodeParams.height = DetailFocusPolicy.episodeViewportRows(episodes.size(), 2, 6)
                        * (int) (52 * targetContext.getResources().getDisplayMetrics().density);
                binding.episode.setLayoutParams(episodeParams);

                int width = targetContext.getResources().getDisplayMetrics().widthPixels;
                int height = targetContext.getResources().getDisplayMetrics().heightPixels;
                int widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY);
                int heightSpec = View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY);
                binding.getRoot().measure(widthSpec, heightSpec);
                binding.getRoot().layout(0, 0, width, height);

                AdapterEpisodeBinding episodeCard = AdapterEpisodeBinding.inflate(
                        LayoutInflater.from(context), new FrameLayout(context), false);
                assertEquals(2, layout.getSpanCount());
                assertEquals(600, episodeAdapter.getItemCount());
                assertTrue(binding.episode.isNestedScrollingEnabled());
                // The old detail layout expanded to every episode row, which forced RecyclerView
                // to create hundreds of children on the UI thread. A six-row viewport must keep
                // the complete adapter data while only materializing the visible neighborhood.
                assertTrue(binding.episode.getChildCount() < episodeAdapter.getItemCount());
                assertTrue(binding.episode.getLayoutParams().height
                        <= 6 * (int) (52 * targetContext.getResources().getDisplayMetrics().density));
                episodes.get(15).setSelected(false);
                episodes.get(590).setSelected(true);
                episodeAdapter.refreshSelection();
                assertEquals(600, episodeAdapter.getItemCount());
                assertEquals(590, episodeAdapter.getPosition());
                assertEquals(TextUtils.TruncateAt.MARQUEE, episodeCard.text.getEllipsize());
                assertEquals(-1, episodeCard.text.getMarqueeRepeatLimit());
                assertEquals(View.GONE, binding.detailBackdrop.getVisibility());
                assertEquals(View.GONE, binding.detailPreviewGroup.getVisibility());
                assertEquals(View.GONE, binding.row2.getVisibility());
                int previewWidth = binding.video.getLayoutParams().width;
                int previewHeight = binding.video.getLayoutParams().height;
                assertTrue(Math.abs(previewWidth * 9 - previewHeight * 16) <= 16);
                assertTrue(binding.flag.getBottom() <= binding.episodeTitle.getTop());
                assertTrue(binding.episodeTitle.getBottom() <= binding.episode.getTop());

                Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                binding.getRoot().draw(new Canvas(bitmap));
                File capture = new File(targetContext.getCacheDir(), "detail-layout-qa.png");
                try (FileOutputStream output = new FileOutputStream(capture)) {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
                }
                bitmap.recycle();
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });
        if (failure.get() != null) throw new AssertionError(failure.get());
    }

    private static Vod vod(String id, String name) {
        Vod vod = new Vod();
        vod.setId(id);
        vod.setName(name);
        return vod;
    }
}
