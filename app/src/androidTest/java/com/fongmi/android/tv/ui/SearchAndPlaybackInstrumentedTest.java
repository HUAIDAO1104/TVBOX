package com.fongmi.android.tv.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Intent;
import android.view.LayoutInflater;
import android.widget.FrameLayout;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.databinding.ActivityCollectBinding;
import com.fongmi.android.tv.databinding.AdapterSearchWorkBinding;
import com.fongmi.android.tv.model.SearchProgress;
import com.fongmi.android.tv.ui.activity.VideoActivity;
import com.fongmi.android.tv.ui.search.SearchSource;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.Set;

@RunWith(AndroidJUnit4.class)
public class SearchAndPlaybackInstrumentedTest {

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
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        var inflater = LayoutInflater.from(context);
        ActivityCollectBinding page = ActivityCollectBinding.inflate(inflater);
        AdapterSearchWorkBinding card = AdapterSearchWorkBinding.inflate(
                inflater, new FrameLayout(context), false);

        assertTrue(page.body.getClipChildren());
        assertTrue(page.body.getClipToPadding());
        assertFalse(page.resultRecycler.getClipToPadding());
        assertTrue(page.resultRecycler.getPaddingTop() > 0);
        assertEquals(card.poster.getLayoutParams().width * 4,
                card.poster.getLayoutParams().height * 3);
    }

    private static Vod vod(String id, String name) {
        Vod vod = new Vod();
        vod.setId(id);
        vod.setName(name);
        return vod;
    }
}
