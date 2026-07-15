package com.fongmi.android.tv.ui.home;

import java.util.HashMap;
import java.util.Map;

public class HomeState {

    public static final String HOME_ID = "home";

    public enum Page {
        LOADING,
        HOME,
        CATEGORY,
        EMPTY,
        ERROR,
        SWITCHING_CONFIG
    }

    private final Map<String, Integer> categoryFocus = new HashMap<>();
    private String selectedCategoryId = HOME_ID;
    private Page page = Page.LOADING;
    private int homeScrollY;
    private int featuredIndex;
    private int historyPosition;
    private int recommendPosition;
    private String recommendKey = "";

    public Page getPage() {
        return page;
    }

    public void setPage(Page page) {
        this.page = page;
    }

    public String getSelectedCategoryId() {
        return selectedCategoryId;
    }

    public void setSelectedCategoryId(String selectedCategoryId) {
        this.selectedCategoryId = selectedCategoryId == null || selectedCategoryId.isEmpty() ? HOME_ID : selectedCategoryId;
    }

    public boolean isHome() {
        return HOME_ID.equals(selectedCategoryId);
    }

    public int getHomeScrollY() {
        return homeScrollY;
    }

    public void setHomeScrollY(int homeScrollY) {
        this.homeScrollY = Math.max(0, homeScrollY);
    }

    public int getFeaturedIndex() {
        return featuredIndex;
    }

    public void setFeaturedIndex(int featuredIndex) {
        this.featuredIndex = Math.max(0, featuredIndex);
    }

    public int getHistoryPosition() {
        return historyPosition;
    }

    public void setHistoryPosition(int historyPosition) {
        this.historyPosition = Math.max(0, historyPosition);
    }

    public int getRecommendPosition() {
        return recommendPosition;
    }

    public void setRecommendPosition(int recommendPosition) {
        this.recommendPosition = Math.max(0, recommendPosition);
    }

    public String getRecommendKey() {
        return recommendKey;
    }

    public void setRecommendKey(String recommendKey) {
        this.recommendKey = recommendKey == null ? "" : recommendKey;
    }

    public void putCategoryFocus(String categoryId, int position) {
        categoryFocus.put(categoryId, Math.max(0, position));
    }

    public int getCategoryFocus(String categoryId) {
        return categoryFocus.getOrDefault(categoryId, 0);
    }

    public HashMap<String, Integer> copyCategoryFocus() {
        return new HashMap<>(categoryFocus);
    }

    public void restoreCategoryFocus(Map<String, Integer> positions) {
        categoryFocus.clear();
        if (positions == null) return;
        for (Map.Entry<String, Integer> entry : positions.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) continue;
            putCategoryFocus(entry.getKey(), entry.getValue());
        }
    }
}
