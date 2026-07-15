package com.fongmi.android.tv.ui.home;

import android.graphics.Bitmap;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.text.TextUtils;
import android.view.View;
import android.view.animation.Interpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.transition.Transition;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.ActivityHomeBinding;
import com.fongmi.android.tv.impl.CustomTarget;
import com.fongmi.android.tv.security.PromotionFilter;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.ResUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HomeFeaturedController {

    private static final Pattern SCORE_PATTERN = Pattern.compile("(?:豆瓣|评分)[:：\\s]*([0-9]+(?:\\.[0-9]+)?)");
    private static final int MAX_CAROUSEL_ITEMS = 6;
    private static final long AUTO_INTERVAL_MS = 8000L;
    private static final long INTERACTION_HOLD_MS = 12000L;
    private static final Interpolator HERO_EASING = new FastOutSlowInInterpolator();

    public interface Listener {
        void onFeaturedOpen(Vod item);

        void onFeaturedDetails(Vod item);

        void onFeaturedDetailRequest(Vod item);
    }

    private final Map<String, Vod> detailCache = new HashMap<>();
    private final HomeAtmosphereController atmosphere;
    private final ActivityHomeBinding binding;
    private final Listener listener;
    private final int debounceMs;
    private final List<Vod> carouselItems = new ArrayList<>();
    private final Runnable autoAdvance = this::advance;
    private final Runnable preloadNext = this::preloadNextPoster;
    private CustomTarget<Bitmap> auraTarget;
    private String auraKey = "";
    private Runnable pendingFocus;
    private String pendingDetailKey = "";
    private Vod pendingDetailItem;
    private Vod current;
    private int currentIndex;
    private int renderToken;
    private boolean autoEnabled;

    public HomeFeaturedController(ActivityHomeBinding binding, Listener listener) {
        this.binding = binding;
        this.listener = listener;
        this.debounceMs = binding.getRoot().getResources().getInteger(R.integer.home_featured_debounce_ms);
        this.atmosphere = new HomeAtmosphereController(binding.atmosphere, binding.atmosphereGlow);
        setupPosterAura();
        binding.play.setOnClickListener(view -> play());
        binding.detail.setOnClickListener(view -> details());
        binding.featuredIndicator.setListener(direction -> {
            holdAuto();
            showCarouselItem(currentIndex + direction);
        });
        bindFocus(binding.play);
        bindFocus(binding.detail);
    }

    public void setInitial(List<Vod> items, int preferredIndex) {
        carouselItems.clear();
        if (items == null || items.isEmpty()) {
            current = null;
            binding.featuredIndicator.setState(0, 0);
            binding.heroStage.setVisibility(View.GONE);
            atmosphere.show(null);
            cancelAuto();
            return;
        }
        carouselItems.addAll(items.subList(0, Math.min(MAX_CAROUSEL_ITEMS, items.size())));
        currentIndex = Math.max(0, Math.min(preferredIndex, carouselItems.size() - 1));
        binding.featuredIndicator.setState(carouselItems.size(), currentIndex);
        binding.heroStage.setVisibility(View.VISIBLE);
        showCarouselItem(currentIndex);
        scheduleAuto(AUTO_INTERVAL_MS);
    }

    public void focus(Vod item, int position) {
        if (item == null) return;
        holdAuto();
        if (pendingFocus != null) App.removeCallbacks(pendingFocus);
        pendingFocus = () -> {
            int carouselIndex = indexOf(item);
            if (carouselIndex >= 0) {
                currentIndex = carouselIndex;
                binding.featuredIndicator.setState(carouselItems.size(), currentIndex);
            }
            Vod cached = detailCache.get(key(item));
            transitionTo(cached == null ? item : cached);
            if (cached == null) requestDetail(item);
        };
        App.post(pendingFocus, debounceMs);
    }

    public void onDetail(Result result) {
        if (result == null || result.getList().isEmpty() || pendingDetailKey.isEmpty()) return;
        String detailKey = pendingDetailKey;
        Vod requested = pendingDetailItem;
        Vod detail = result.getVod();
        if (requested != null) {
            if (detail.getId().isEmpty()) detail.setId(requested.getId());
            detail.checkName(requested.getName());
            detail.checkPic(requested.getPic());
            if (detail.getSite() == null) detail.setSite(requested.getSite());
        }
        detailCache.put(detailKey, detail);
        pendingDetailKey = "";
        pendingDetailItem = null;
        if (current != null && detailKey.equals(key(current))) bind(detail);
    }

    private void showCarouselItem(int index) {
        if (carouselItems.isEmpty()) return;
        currentIndex = Math.floorMod(index, carouselItems.size());
        binding.featuredIndicator.setState(carouselItems.size(), currentIndex);
        Vod item = carouselItems.get(currentIndex);
        Vod cached = detailCache.get(key(item));
        transitionTo(cached == null ? item : cached);
        if (cached == null) requestDetail(item);
        App.removeCallbacks(preloadNext);
        App.post(preloadNext, 1200L);
    }

    private void advance() {
        if (!autoEnabled || carouselItems.size() < 2) return;
        showCarouselItem(currentIndex + 1);
        scheduleAuto(AUTO_INTERVAL_MS);
    }

    private void holdAuto() {
        scheduleAuto(INTERACTION_HOLD_MS);
    }

    private void scheduleAuto(long delay) {
        cancelAuto();
        if (autoEnabled && carouselItems.size() > 1) App.post(autoAdvance, delay);
    }

    private void cancelAuto() {
        App.removeCallbacks(autoAdvance);
    }

    public void startAuto() {
        autoEnabled = true;
        scheduleAuto(AUTO_INTERVAL_MS);
    }

    public void stopAuto() {
        autoEnabled = false;
        cancelAuto();
    }

    private void requestDetail(Vod item) {
        if (item == null || item.getId().isEmpty() || detailCache.containsKey(key(item)) || pendingDetailKey.equals(key(item))) return;
        pendingDetailKey = key(item);
        pendingDetailItem = item;
        listener.onFeaturedDetailRequest(item);
    }

    private void transitionTo(Vod item) {
        if (item == null) return;
        if (current == null) {
            bind(item);
            binding.featuredInfo.setAlpha(1f);
            binding.featuredPoster.setAlpha(1f);
            binding.featuredPoster.setScaleX(1f);
            binding.featuredPoster.setScaleY(1f);
            return;
        }
        if (current != null && key(current).equals(key(item))) {
            bind(item);
            return;
        }
        int token = ++renderToken;
        binding.featuredInfo.animate().cancel();
        binding.featuredPoster.animate().cancel();
        binding.featuredPoster.animate()
                .alpha(0.16f)
                .scaleX(0.985f)
                .scaleY(0.985f)
                .setInterpolator(HERO_EASING)
                .setDuration(150)
                .start();
        binding.featuredInfo.animate()
                .alpha(0.12f)
                .setInterpolator(HERO_EASING)
                .setDuration(145)
                .withEndAction(() -> {
            if (token != renderToken) return;
            bind(item);
            binding.featuredInfo.animate()
                    .alpha(1f)
                    .setInterpolator(HERO_EASING)
                    .setDuration(300)
                    .start();
            binding.featuredPoster.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setInterpolator(HERO_EASING)
                    .setDuration(320)
                    .start();
        }).start();
    }

    private void preloadNextPoster() {
        if (carouselItems.size() < 2) return;
        Vod next = carouselItems.get((currentIndex + 1) % carouselItems.size());
        if (next.getPic().isEmpty()) return;
        Glide.with(binding.featuredPoster)
                .load(ImgUtil.getUrl(next.getPic()))
                .centerCrop()
                .preload();
    }

    private void bind(Vod item) {
        current = item;
        binding.featuredName.setText(item.getName());
        binding.featuredMeta.setText(meta(item));
        binding.featuredMeta.setVisibility(binding.featuredMeta.getText().length() == 0 ? View.GONE : View.VISIBLE);
        String score = score(item.getRemarks());
        binding.featuredScore.setText(score.isEmpty() ? "" : binding.getRoot().getResources().getString(R.string.home_score_douban, score));
        binding.featuredScore.setVisibility(score.isEmpty() ? View.GONE : View.VISIBLE);
        String remark = cleanRemark(item.getRemarks());
        binding.featuredRemark.setText(remark);
        binding.featuredRemark.setVisibility(remark.isEmpty() ? View.GONE : View.VISIBLE);
        String summary = cleanSummary(item.getContent());
        binding.featuredSummary.setText(summary);
        binding.featuredSummary.setAlpha(1f);
        binding.featuredSummary.setVisibility(summary.isEmpty() ? View.GONE : View.VISIBLE);
        ImgUtil.loadPoster(item.getName(), item.getPic(), binding.featuredPoster);
        showPosterAura(item);
        atmosphere.show(item);
    }

    private int indexOf(Vod item) {
        String target = key(item);
        for (int index = 0; index < carouselItems.size(); index++) {
            if (target.equals(key(carouselItems.get(index)))) return index;
        }
        return -1;
    }

    private CharSequence meta(Vod item) {
        List<String> values = new ArrayList<>();
        add(values, item.getYear());
        add(values, item.getArea());
        add(values, item.getTypeName().replaceAll("[,，]+", " / "));
        return TextUtils.join(" · ", values);
    }

    private String cleanSummary(String content) {
        return PromotionFilter.sanitizeDisplayText(content);
    }

    private String score(String remarks) {
        if (TextUtils.isEmpty(remarks)) return "";
        Matcher matcher = SCORE_PATTERN.matcher(remarks);
        if (!matcher.find()) return "";
        String value = matcher.group(1);
        try {
            return Double.parseDouble(value) > 0 ? value : "";
        } catch (NumberFormatException ignored) {
            return "";
        }
    }

    private String cleanRemark(String remarks) {
        if (TextUtils.isEmpty(remarks)) return "";
        String clean = SCORE_PATTERN.matcher(remarks).replaceAll("").trim();
        clean = clean.replaceAll("^[·\\s|/]+|[·\\s|/]+$", "");
        return PromotionFilter.sanitizeDisplayText(clean);
    }

    private void add(List<String> values, String value) {
        if (!TextUtils.isEmpty(value)) values.add(value.trim());
    }

    private void bindFocus(View view) {
        view.setOnFocusChangeListener((target, focused) -> {
            if (focused) holdAuto();
            target.setTranslationZ(focused ? 10f : 0f);
            float scale = focused ? 1.025f : 1f;
            target.animate().scaleX(scale).scaleY(scale).setDuration(target.getResources().getInteger(R.integer.tv_focus_animation_duration)).start();
        });
    }

    private void setupPosterAura() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        float blur = ResUtil.dp2px(38);
        binding.featuredPosterAura.setRenderEffect(RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.DECAL));
    }

    private void showPosterAura(Vod item) {
        clearAuraTarget();
        if (item == null || item.getPic().isEmpty()) {
            binding.featuredPosterAura.setVisibility(View.GONE);
            return;
        }
        String requestedKey = key(item);
        auraKey = requestedKey;
        binding.featuredPosterAura.animate().cancel();
        binding.featuredPosterAura.setAlpha(0f);
        binding.featuredPosterAura.setScaleX(1.04f);
        binding.featuredPosterAura.setScaleY(1.04f);
        auraTarget = new CustomTarget<>() {
            @Override
            public void onResourceReady(@NonNull Bitmap resource, @Nullable Transition<? super Bitmap> transition) {
                if (!auraKey.equals(requestedKey)) return;
                binding.featuredPosterAura.setImageBitmap(resource);
                binding.featuredPosterAura.setVisibility(View.VISIBLE);
                float alpha = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? 0.46f : 0.28f;
                binding.featuredPosterAura.animate().alpha(alpha).scaleX(1f).scaleY(1f).setDuration(460).start();
            }

            @Override
            public void onLoadFailed(@Nullable Drawable errorDrawable) {
                if (auraKey.equals(requestedKey)) binding.featuredPosterAura.setVisibility(View.GONE);
            }
        };
        ImgUtil.load(item.getPic(), auraTarget);
    }

    private void clearAuraTarget() {
        auraKey = "";
        if (auraTarget == null) return;
        Glide.with(App.get()).clear(auraTarget);
        auraTarget = null;
    }

    private void reveal(View view) {
        view.animate().cancel();
        view.setAlpha(0.72f);
        view.animate().alpha(1f).setDuration(180).start();
    }

    private void play() {
        if (current != null) listener.onFeaturedOpen(current);
    }

    private void details() {
        if (current != null) listener.onFeaturedDetails(current);
    }

    private String key(Vod item) {
        if (item == null) return "";
        String identity = item.getId().isEmpty() ? item.getName() : item.getId();
        return item.getSiteKey() + ':' + identity;
    }

    public Vod getCurrent() {
        return current;
    }

    public int getCurrentIndex() {
        return currentIndex;
    }

    public void clear() {
        if (pendingFocus != null) App.removeCallbacks(pendingFocus);
        pendingFocus = null;
        detailCache.clear();
        pendingDetailKey = "";
        pendingDetailItem = null;
        carouselItems.clear();
        current = null;
        renderToken++;
        cancelAuto();
        App.removeCallbacks(preloadNext);
        clearAuraTarget();
        binding.featuredIndicator.setState(0, 0);
        binding.heroStage.setVisibility(View.GONE);
        atmosphere.show(null);
    }

    public void destroy() {
        stopAuto();
        App.removeCallbacks(preloadNext);
        if (pendingFocus != null) App.removeCallbacks(pendingFocus);
        Glide.with(App.get()).clear(binding.featuredPoster);
        clearAuraTarget();
        atmosphere.destroy();
    }
}
