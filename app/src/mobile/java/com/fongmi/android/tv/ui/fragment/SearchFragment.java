package com.fongmi.android.tv.ui.fragment;


import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Word;
import com.fongmi.android.tv.databinding.FragmentSearchBinding;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.adapter.RecordAdapter;
import com.fongmi.android.tv.ui.adapter.WordAdapter;
import com.fongmi.android.tv.ui.base.BaseFragment;
import com.fongmi.android.tv.ui.custom.CustomTextListener;
import com.fongmi.android.tv.ui.dialog.MobileSearchSources;
import android.view.inputmethod.BaseInputConnection;
import com.fongmi.android.tv.utils.Util;
import com.github.catvod.net.OkHttp;
import com.google.android.flexbox.FlexDirection;
import com.google.android.flexbox.FlexboxLayoutManager;
import com.google.common.net.HttpHeaders;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.Map;
import java.util.Optional;

import okhttp3.Call;
import okhttp3.Response;

public class SearchFragment extends BaseFragment implements  WordAdapter.OnClickListener, RecordAdapter.OnClickListener {

    private FragmentSearchBinding mBinding;
    private RecordAdapter mRecordAdapter;
    private WordAdapter mWordAdapter;
    private Call suggestionCall;
    private int suggestionGeneration;
    private boolean submitting;
    private boolean autoSearch = true;
    private String draft;
    @Override public void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        if (state != null) { draft = state.getString("draft"); autoSearch = false; }
    }
    @Override public void onSaveInstanceState(@NonNull Bundle state) {
        super.onSaveInstanceState(state);
        if (mBinding != null) state.putString("draft", mBinding.keyword.getText().toString());
    }
    private final Runnable requestSuggestions = () -> {
        if (mBinding == null || isHidden()) return;
        Editable text = mBinding.keyword.getText();
        if (text != null && BaseInputConnection.getComposingSpanStart(text) >= 0) {
            App.post(requestSuggestionsAgain(), 300);
            return;
        }
        getWord(text == null ? "" : text.toString().trim());
    };
    private Runnable requestSuggestionsAgain() { return requestSuggestions; }

    public static SearchFragment newInstance(String keyword) {
        Bundle args = new Bundle();
        args.putString("keyword", keyword);
        SearchFragment fragment = new SearchFragment();
        fragment.setArguments(args);
        return fragment;
    }

    private String getKeyword() {
        return getArguments().getString("keyword");
    }

    private boolean empty() {
        return mBinding.keyword.getText().toString().trim().isEmpty();
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = FragmentSearchBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView() {
        mBinding.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        mBinding.toolbar.setNavigationContentDescription(R.string.mobile_back);
        mBinding.toolbar.setNavigationOnClickListener(v -> requireActivity().getOnBackPressedDispatcher().onBackPressed());
        setRecyclerView();
        checkKeyword();
        if (autoSearch) search();
        autoSearch = false;
    }

    private void setRecyclerView() {
        mBinding.wordRecycler.setHasFixedSize(false);
        mBinding.wordRecycler.setAdapter(mWordAdapter = new WordAdapter(this));
        mBinding.wordRecycler.setLayoutManager(new FlexboxLayoutManager(getContext(), FlexDirection.ROW));
        mBinding.recordRecycler.setHasFixedSize(false);
        mBinding.recordRecycler.setAdapter(mRecordAdapter = new RecordAdapter(this));
        mBinding.recordRecycler.setLayoutManager(new FlexboxLayoutManager(getContext(), FlexDirection.ROW));
    }

    @Override
    protected void initEvent() {
        mBinding.clearHistory.setOnClickListener(v -> new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext()).setTitle("清空搜索记录？").setMessage("仅清除最近搜索，不影响观看记录和收藏。").setNegativeButton("取消", null).setPositiveButton("清空", (d, w) -> mRecordAdapter.clear()).show());
        mBinding.submit.setEnabled(!empty());
        mBinding.submit.setOnClickListener(v -> search());
        mBinding.sources.setOnClickListener(v -> {
            Util.hideKeyboard(mBinding.keyword);
            MobileSearchSources.show(this, this::updateSources);
        });
        updateSources();
        mBinding.keyword.setOnEditorActionListener((textView, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_SEARCH) return false;
            if (BaseInputConnection.getComposingSpanStart(mBinding.keyword.getText()) < 0) search();
            return true;
        });
        mBinding.keyword.addTextChangedListener(new CustomTextListener() {
            @Override
            public void afterTextChanged(Editable s) {
                cancelSuggestions();
                mBinding.submit.setEnabled(!s.toString().trim().isEmpty());
                App.post(requestSuggestions, s.toString().trim().isEmpty() ? 0 : 300);
            }
        });
        getParentFragmentManager().setFragmentResultListener("result", getViewLifecycleOwner(), (requestKey, bundle) -> {
            if (bundle.getBoolean("edit", false)) Util.showKeyboard(mBinding.keyword);
        });
    }

    private void checkKeyword() {
        boolean visible = requireActivity().getSupportFragmentManager().findFragmentByTag(CollectFragment.class.getSimpleName()) != null;
        if (TextUtils.isEmpty(getKeyword()) && !visible) Util.showKeyboard(mBinding.keyword);
        setKeyword(draft == null ? getKeyword() : draft);
        if (!visible) getWord(mBinding.keyword.getText().toString().trim());
    }

    private void setKeyword(String text) {
        text = text == null ? "" : text;
        mBinding.keyword.setText(text);
        mBinding.keyword.setSelection(text.length());
    }

    private void search() {
        if (empty() || submitting || getParentFragmentManager().isStateSaved()) return;
        submitting = true;
        cancelSuggestions();
        String keyword = mBinding.keyword.getText().toString().trim();
        if (!Setting.isIncognito()) mRecordAdapter.add(keyword);
        requireArguments().putString("keyword", keyword);
        Util.hideKeyboard(mBinding.keyword);
        collect(keyword);
    }

    private void collect(String keyword) {
        FragmentManager fm = requireActivity().getSupportFragmentManager();
        String collectTag = CollectFragment.class.getSimpleName();
        if (fm.findFragmentByTag(collectTag) != null) return;
        String searchTag = SearchFragment.class.getSimpleName();
        FragmentTransaction ft = fm.beginTransaction().setReorderingAllowed(true);
        ft.add(R.id.container, CollectFragment.newInstance(keyword), collectTag);
        Optional.ofNullable(fm.findFragmentByTag(searchTag)).ifPresent(ft::hide);
        ft.setReorderingAllowed(true).addToBackStack(null).commit();
    }

    private void getWord(String text) {
        if (text.isEmpty()) getHot();
        else getSuggest(text);
    }

    private void getHot() {
        mBinding.word.setText(R.string.search_hot);
        mWordAdapter.setItems(Word.objectFrom(Setting.getHot()).getData());
        suggestionCall = OkHttp.newCall("https://api.web.360kan.com/v1/rank?cat=1", Map.of(HttpHeaders.REFERER, "https://www.360kan.com/rank/general"));
        suggestionCall.enqueue(getCallback(true, suggestionGeneration));
    }

    private void getSuggest(String text) {
        mBinding.word.setText(R.string.search_suggest);
        suggestionCall = OkHttp.newCall("https://suggest.video.iqiyi.com/?if=mobile&key=" + URLEncoder.encode(text, java.nio.charset.StandardCharsets.UTF_8));
        suggestionCall.enqueue(getCallback(false, suggestionGeneration));
    }

    private Callback getCallback(boolean hot, int generation) {
        return new Callback() {
            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try (response) {
                    if (!response.isSuccessful() || response.body() == null) return;
                    String result = response.body().string();
                    if (TextUtils.isEmpty(result)) return;
                    App.post(() -> {
                        if (generation == suggestionGeneration && mBinding != null && isAdded() && !isHidden()) setWordAdapter(result, hot);
                    });
                }
            }
        };
    }

    private void setWordAdapter(String result, boolean save) {
        if (!save && mBinding.keyword.getText().toString().trim().isEmpty()) return;
        mWordAdapter.setItems(Word.objectFrom(result).getData());
        if (save) Setting.putHot(result);
    }

    private void cancelSuggestions() {
        suggestionGeneration++;
        App.removeCallbacks(requestSuggestions);
        if (suggestionCall != null) suggestionCall.cancel();
        suggestionCall = null;
    }

    private void updateSources() {
        if (mBinding != null) mBinding.sources.setText("搜索范围 · " + MobileSearchSources.selected().size() + " 个源");
    }

    @Override
    public void onItemClick(String text) {
        setKeyword(text);
        search();
    }

    @Override
    public void onDataChanged(int size) {
        mBinding.recordHeader.setVisibility(size == 0 ? View.GONE : View.VISIBLE);
        mBinding.recordRecycler.setVisibility(size == 0 ? View.GONE : View.VISIBLE);

    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        if (hidden) cancelSuggestions();
        else { submitting = false; updateSources(); }
    }

    @Override
    public void onDestroyView() {
        cancelSuggestions();
        mBinding = null;
        super.onDestroyView();
    }
}
