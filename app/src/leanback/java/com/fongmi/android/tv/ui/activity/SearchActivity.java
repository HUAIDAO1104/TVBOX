package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Word;
import com.fongmi.android.tv.databinding.ActivitySearchBinding;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.adapter.RecordAdapter;
import com.fongmi.android.tv.ui.adapter.WordAdapter;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.custom.CustomKeyboard;
import com.fongmi.android.tv.ui.custom.CustomTextListener;
import com.fongmi.android.tv.ui.dialog.SiteDialog;
import com.fongmi.android.tv.ui.search.VoiceSearchPolicy;
import com.fongmi.android.tv.utils.KeyUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.Util;
import com.fongmi.android.tv.utils.ZhuToPin;
import com.github.catvod.net.OkHttp;
import com.google.android.flexbox.FlexDirection;
import com.google.android.flexbox.FlexboxLayoutManager;
import com.google.common.net.HttpHeaders;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.List;
import java.util.Map;

import okhttp3.Call;
import okhttp3.Response;

public class SearchActivity extends BaseActivity implements WordAdapter.OnClickListener, RecordAdapter.OnClickListener, CustomKeyboard.Callback {

    private static final String EXTRA_START_VOICE = "start_voice_search";
    private static final String STATE_KEYWORD = "search_v2_keyword";
    private static final String STATE_SCROLL_Y = "search_v2_scroll_y";
    private static final String STATE_FOCUS_ZONE = "search_v2_focus_zone";
    private static final String STATE_FOCUS_POSITION = "search_v2_focus_position";

    private ActivitySearchBinding mBinding;
    private RecordAdapter mRecordAdapter;
    private WordAdapter mWordAdapter;
    private Bundle mSavedState;
    private boolean mFirstResume;
    private boolean mStartVoiceRequested;
    private boolean mSearchLaunching;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, SearchActivity.class));
    }

    public static void start(Activity activity, String keyword) {
        Intent intent = new Intent(activity, SearchActivity.class);
        intent.putExtra("keyword", keyword);
        activity.startActivity(intent);
    }

    public static void startVoice(Activity activity) {
        Intent intent = new Intent(activity, SearchActivity.class);
        intent.putExtra(EXTRA_START_VOICE, true);
        activity.startActivity(intent);
    }

    private String getKeyword() {
        String keyword = getIntent().getStringExtra("keyword");
        return keyword != null ? keyword : "";
    }

    private boolean empty() {
        return mBinding.keyword.getText().toString().trim().isEmpty();
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivitySearchBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        mSavedState = savedInstanceState;
        mFirstResume = true;
        mStartVoiceRequested = savedInstanceState == null && getIntent().getBooleanExtra(EXTRA_START_VOICE, false);
        CustomKeyboard.init(this, mBinding);
        setRecyclerView();
        checkKeyword(savedInstanceState);
        if (savedInstanceState == null && !getKeyword().isBlank()) onSearch();
    }

    @Override
    protected void initEvent() {
        mBinding.keyword.setOnEditorActionListener((textView, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) onSearch();
            return true;
        });
        mBinding.keyword.addTextChangedListener(new CustomTextListener() {
            @Override
            public void afterTextChanged(Editable s) {
                getWord(s.toString());
            }
        });
        mBinding.voiceAction.setOnClickListener(v -> startVoiceSearch());
        mBinding.mic.setListener(this, new CustomTextListener() {
            @Override
            public void onResults(String result) {
                if (result.isEmpty()) return;
                setKeyword(result);
                onSearch();
            }

            @Override
            public void onCancelled() {
                restoreVoiceFocus();
            }

            @Override
            public void onPermissionDenied() {
                Notify.show(R.string.search_v2_voice_permission_denied);
                restoreVoiceFocus();
            }

            @Override
            public void onNoMatch() {
                Notify.show(R.string.search_v2_voice_no_match);
                restoreVoiceFocus();
            }

            @Override
            public void onFailure(int error) {
                Notify.show(R.string.search_v2_voice_failed);
                restoreVoiceFocus();
            }

            @Override
            public void onUnavailable() {
                Notify.show(R.string.search_v2_voice_unavailable);
                restoreVoiceFocus();
            }
        });
    }

    private void startVoiceSearch() {
        mBinding.voiceAction.requestFocus();
        mBinding.mic.start();
    }

    private void restoreVoiceFocus() {
        mBinding.voiceAction.post(() -> {
            if (!isFinishing() && !isDestroyed() && mBinding.voiceAction.isFocusable()) {
                mBinding.voiceAction.requestFocus();
            }
        });
    }

    private void setRecyclerView() {
        mBinding.wordRecycler.setItemAnimator(null);
        mBinding.wordRecycler.setHasFixedSize(false);
        mBinding.wordRecycler.setLayoutManager(new FlexboxLayoutManager(this, FlexDirection.ROW));
        mBinding.wordRecycler.setAdapter(mWordAdapter = new WordAdapter(this));
        mBinding.recordRecycler.setHasFixedSize(false);
        mBinding.recordRecycler.setLayoutManager(new FlexboxLayoutManager(this, FlexDirection.ROW));
        mBinding.recordRecycler.setAdapter(mRecordAdapter = new RecordAdapter(this));
    }

    private void checkKeyword(Bundle state) {
        String keyword = state == null ? getKeyword() : state.getString(STATE_KEYWORD, getKeyword());
        if (keyword.isBlank()) keyword = getLastKeyword();
        setKeyword(keyword);
        getWord(keyword);
        if (state != null) mBinding.scroll.post(() -> mBinding.scroll.scrollTo(0, state.getInt(STATE_SCROLL_Y)));
    }

    private String getLastKeyword() {
        try {
            if (Setting.getKeyword().isEmpty()) return "";
            List<String> items = App.gson().fromJson(Setting.getKeyword(),
                    com.google.gson.reflect.TypeToken.getParameterized(List.class, String.class).getType());
            return items == null || items.isEmpty() ? "" : items.get(0);
        } catch (Exception ignored) {
            return "";
        }
    }

    private void setKeyword(String text) {
        mBinding.keyword.setText(text);
        mBinding.keyword.setSelection(text.length());
    }

    private void getWord(String text) {
        if (text.isEmpty()) getHot();
        else getSuggest(text);
    }

    private void getHot() {
        mBinding.word.setText(R.string.search_hot);
        mWordAdapter.setItems(Word.objectFrom(Setting.getHot()).getData());
        OkHttp.newCall("https://api.web.360kan.com/v1/rank?cat=1", Map.of(HttpHeaders.REFERER, "https://www.360kan.com/rank/general")).enqueue(getCallback(true, ""));
    }

    private void getSuggest(String text) {
        mBinding.word.setText(R.string.search_suggest);
        OkHttp.newCall("https://suggest.video.iqiyi.com/?if=mobile&key=" + URLEncoder.encode(ZhuToPin.get(text))).enqueue(getCallback(false, text));
    }

    private Callback getCallback(boolean hot, String requestText) {
        return new Callback() {
            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                String result = response.body().string();
                if (TextUtils.isEmpty(result)) return;
                App.post(() -> setAdapter(result, hot, requestText));
            }
        };
    }

    private void setAdapter(String result, boolean save, String requestText) {
        String current = mBinding.keyword.getText().toString().trim();
        if (save && !current.isEmpty()) return;
        if (!save && !current.equals(requestText.trim())) return;
        if (save) Setting.putHot(result);
        mWordAdapter.setItems(Word.objectFrom(result).getData());
    }

    @Override
    public void onItemClick(String text) {
        setKeyword(text);
        onSearch();
    }

    @Override
    public void onDataChanged(int size) {
        mBinding.recordLayout.setVisibility(size == 0 ? View.GONE : View.VISIBLE);
        if (size == 0) focusFirst(mBinding.wordRecycler);
    }

    @Override
    public void onSearch() {
        // A single remote confirmation can be delivered both as an IME action and as the focused
        // keyboard item's click. Without a gate this starts two CollectActivity instances a few
        // milliseconds apart, which in turn runs two complete Spider queues and can corrupt shared
        // native/Jar state. Re-enable submission only after this screen is actually resumed.
        if (empty() || mSearchLaunching) return;
        mSearchLaunching = true;
        String keyword = mBinding.keyword.getText().toString().trim();
        App.post(() -> mRecordAdapter.add(keyword), 250);
        Util.hideKeyboard(mBinding.keyword);
        CollectActivity.start(this, keyword);
    }

    @Override
    public void showDialog() {
        SiteDialog.create().search().show(this);
    }

    @Override
    public void onRemote() {
        PushActivity.start(this, 1);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && KeyUtil.isBackKey(event) && mBinding.mic.isListening()) {
            mBinding.mic.stop();
            restoreVoiceFocus();
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN
                && event.getRepeatCount() == 0
                && VoiceSearchPolicy.isActivationKey(event.getKeyCode())) {
            startVoiceSearch();
            return true;
        }
        if (KeyUtil.isMenuKey(event)) showDialog();
        if (KeyUtil.isActionDown(event) && findFocus(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    private boolean findFocus(KeyEvent event) {
        View current = getCurrentFocus();
        if (current == mBinding.keyword) return handleKeywordKey(event);
        View inKeyboard = mBinding.keyboard.findContainingItemView(current);
        View inWord = mBinding.wordRecycler.findContainingItemView(current);
        View inRecord = mBinding.recordRecycler.findContainingItemView(current);
        if (inKeyboard != null) return handleKeyboardKey(event, inKeyboard);
        if (inRecord != null) return handleRecordKey(event, inRecord);
        if (inWord != null) return handleWordKey(event, inWord);
        return false;
    }

    private View findNearestInLastRow(RecyclerView rv, int targetLeft) {
        if (rv.getChildCount() == 0) return null;
        int lastTop = rv.getChildAt(rv.getChildCount() - 1).getTop();
        View nearest = null;
        int minDist = Integer.MAX_VALUE;
        for (int i = 0; i < rv.getChildCount(); i++) {
            View child = rv.getChildAt(i);
            if (child.getTop() == lastTop) {
                int dist = Math.abs(child.getLeft() - targetLeft);
                if (dist < minDist) {
                    minDist = dist;
                    nearest = child;
                }
            }
        }
        return nearest;
    }

    private boolean isFirstRow(RecyclerView rv, View item) {
        View first = rv.getChildAt(0);
        return first != null && item.getTop() == first.getTop();
    }

    private boolean isLastRow(RecyclerView rv, View item) {
        View last = rv.getChildAt(rv.getChildCount() - 1);
        return last != null && item.getTop() == last.getTop();
    }

    private boolean isFirstInRow(RecyclerView rv, View focused) {
        int top = focused.getTop();
        int left = focused.getLeft();
        for (int i = 0; i < rv.getChildCount(); i++) {
            View child = rv.getChildAt(i);
            if (child.getTop() == top && child.getLeft() < left) return false;
        }
        return true;
    }

    private boolean isLastInRow(RecyclerView rv, View focused) {
        int top = focused.getTop();
        int right = focused.getRight();
        for (int i = 0; i < rv.getChildCount(); i++) {
            View child = rv.getChildAt(i);
            if (child.getTop() == top && child.getRight() > right) return false;
        }
        return true;
    }

    private boolean handleKeywordKey(KeyEvent event) {
        if (!KeyUtil.isRightKey(event)) return false;
        if (mBinding.keyword.getSelectionEnd() < mBinding.keyword.getText().length()) return false;
        boolean hasRecord = mBinding.recordLayout.getVisibility() == View.VISIBLE;
        return focusFirst(hasRecord ? mBinding.recordRecycler : mBinding.wordRecycler);
    }

    private boolean handleKeyboardKey(KeyEvent event, View item) {
        if (KeyUtil.isUpKey(event) && isKeyboardFirstRow(item)) {
            mBinding.keyword.requestFocus();
            return true;
        }
        if (KeyUtil.isLeftKey(event) && isFirstInRow(mBinding.keyboard, item)) return true;
        return KeyUtil.isDownKey(event) && isKeyboardLastRow(item);
    }

    private boolean isKeyboardFirstRow(View item) {
        return mBinding.keyboard.getChildAdapterPosition(item) < 7;
    }

    private boolean isKeyboardLastRow(View item) {
        if (mBinding.keyboard.getAdapter() == null) return true;
        int count = mBinding.keyboard.getAdapter().getItemCount();
        int lastRowSize = count % 7 == 0 ? 7 : count % 7;
        return mBinding.keyboard.getChildAdapterPosition(item) >= count - lastRowSize;
    }

    private boolean handleWordKey(KeyEvent event, View item) {
        if (KeyUtil.isRightKey(event)) return isLastInRow(mBinding.wordRecycler, item);
        if (KeyUtil.isDownKey(event)) return isLastRow(mBinding.wordRecycler, item);
        if (KeyUtil.isUpKey(event) && isFirstRow(mBinding.wordRecycler, item)) {
            if (mBinding.recordLayout.getVisibility() == View.VISIBLE) {
                View child = findNearestInLastRow(mBinding.recordRecycler, item.getLeft());
                if (child != null) {
                    mBinding.scroll.smoothScrollTo(0, 0);
                    child.requestFocus();
                    return true;
                }
            }
            return true;
        }
        return false;
    }

    private boolean handleRecordKey(KeyEvent event, View item) {
        if (KeyUtil.isRightKey(event)) return isLastInRow(mBinding.recordRecycler, item);
        if (KeyUtil.isUpKey(event)) return isFirstRow(mBinding.recordRecycler, item);
        if (KeyUtil.isDownKey(event) && isLastRow(mBinding.recordRecycler, item)) return focusFirst(mBinding.wordRecycler);
        return false;
    }

    private boolean focusFirst(RecyclerView rv) {
        View child = rv.getChildAt(0);
        if (child == null) return false;
        child.requestFocus();
        return true;
    }

    @Override
    protected void onPause() {
        super.onPause();
        mBinding.voiceAction.setFocusable(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mSearchLaunching = false;
        mBinding.voiceAction.setFocusable(true);
        if (!mFirstResume) return;
        mFirstResume = false;
        mBinding.getRoot().post(this::restoreFocus);
    }

    private void restoreFocus() {
        if (mStartVoiceRequested) {
            mStartVoiceRequested = false;
            startVoiceSearch();
            return;
        }
        if (mSavedState == null) {
            mBinding.keyword.requestFocus();
            return;
        }
        String zone = mSavedState.getString(STATE_FOCUS_ZONE, "keyword");
        int position = mSavedState.getInt(STATE_FOCUS_POSITION, 0);
        if ("mic".equals(zone)) mBinding.voiceAction.requestFocus();
        else if ("keyboard".equals(zone)) focusPosition(mBinding.keyboard, position);
        else if ("record".equals(zone)) focusPosition(mBinding.recordRecycler, position);
        else if ("word".equals(zone)) focusPosition(mBinding.wordRecycler, position);
        else mBinding.keyword.requestFocus();
        mSavedState = null;
    }

    private void focusPosition(RecyclerView recyclerView, int position) {
        if (recyclerView.getAdapter() == null || recyclerView.getAdapter().getItemCount() == 0) {
            mBinding.keyword.requestFocus();
            return;
        }
        int safePosition = Math.clamp(position, 0, recyclerView.getAdapter().getItemCount() - 1);
        recyclerView.scrollToPosition(safePosition);
        recyclerView.post(() -> {
            RecyclerView.ViewHolder holder = recyclerView.findViewHolderForAdapterPosition(safePosition);
            if (holder != null) holder.itemView.requestFocus();
            else mBinding.keyword.requestFocus();
        });
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_KEYWORD, mBinding.keyword.getText().toString());
        outState.putInt(STATE_SCROLL_Y, mBinding.scroll.getScrollY());
        View focus = getCurrentFocus();
        if (focus == mBinding.voiceAction) outState.putString(STATE_FOCUS_ZONE, "mic");
        else if (focus == mBinding.keyword) outState.putString(STATE_FOCUS_ZONE, "keyword");
        else if (saveRecyclerFocus(outState, mBinding.keyboard, focus, "keyboard")) return;
        else if (saveRecyclerFocus(outState, mBinding.recordRecycler, focus, "record")) return;
        else saveRecyclerFocus(outState, mBinding.wordRecycler, focus, "word");
    }

    private boolean saveRecyclerFocus(Bundle state, RecyclerView recyclerView, View focus, String zone) {
        if (focus == null) return false;
        View item = recyclerView.findContainingItemView(focus);
        if (item == null) return false;
        state.putString(STATE_FOCUS_ZONE, zone);
        state.putInt(STATE_FOCUS_POSITION, recyclerView.getChildAdapterPosition(item));
        return true;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mBinding.mic.destroy();
    }
}
