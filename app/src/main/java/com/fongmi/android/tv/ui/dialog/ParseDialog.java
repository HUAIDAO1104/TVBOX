package com.fongmi.android.tv.ui.dialog;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.bean.Parse;
import com.fongmi.android.tv.databinding.DialogParseBinding;
import com.fongmi.android.tv.ui.adapter.ParseAdapter;
import com.fongmi.android.tv.ui.custom.SpaceItemDecoration;

public class ParseDialog extends BaseBottomSheetDialog implements ParseAdapter.OnClickListener {

    private static final String ARG_SITE_KEY = "siteKey";
    private DialogParseBinding binding;
    private ParseAdapter adapter;

    public static ParseDialog create() {
        return new ParseDialog();
    }

    public ParseDialog siteKey(String siteKey) {
        Bundle args = getArguments();
        if (args == null) args = new Bundle();
        args.putString(ARG_SITE_KEY, siteKey == null ? "" : siteKey);
        setArguments(args);
        return this;
    }

    public void show(FragmentActivity activity) {
        for (Fragment f : activity.getSupportFragmentManager().getFragments()) if (f instanceof ParseDialog) return;
        show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return binding = DialogParseBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        Bundle args = getArguments();
        adapter = new ParseAdapter(this, args == null ? "" : args.getString(ARG_SITE_KEY, ""));
        binding.recycler.setAdapter(adapter);
        binding.recycler.setHasFixedSize(true);
        binding.recycler.setItemAnimator(null);
        binding.recycler.addItemDecoration(new SpaceItemDecoration(8));
        binding.recycler.post(() -> binding.recycler.scrollToPosition(adapter.getPosition()));
    }

    @Override
    public void onItemClick(Parse item) {
        ((Listener) requireActivity()).onParse(item);
        dismiss();
    }

    @Override
    public void onStart() {
        super.onStart();
        if (adapter.getItemCount() == 0) dismiss();
    }

    public interface Listener {

        void onParse(Parse item);
    }
}
