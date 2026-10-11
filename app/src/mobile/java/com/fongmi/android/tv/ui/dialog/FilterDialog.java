package com.fongmi.android.tv.ui.dialog;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.bean.Filter;
import com.fongmi.android.tv.databinding.DialogFilterBinding;
import com.fongmi.android.tv.impl.FilterListener;
import com.fongmi.android.tv.ui.adapter.FilterAdapter;

import java.util.List;

public class FilterDialog extends MobileSheetDialog {

    private DialogFilterBinding binding;
    private FilterListener listener;
    private List<Filter> filter;

    public static FilterDialog create() {
        return new FilterDialog();
    }

    public FilterDialog filter(List<Filter> filter) {
        this.filter = filter;
        android.os.Bundle args = new android.os.Bundle();
        args.putParcelableArrayList("filters", new java.util.ArrayList<>(filter));
        setArguments(args);
        return this;
    }

    public void show(Fragment fragment) {
        for (Fragment f : fragment.getChildFragmentManager().getFragments()) if (f instanceof FilterDialog) return;
        show(fragment.getChildFragmentManager(), null);
        this.listener = (FilterListener) fragment;
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return binding = DialogFilterBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        if (filter == null) filter = getArguments() == null ? java.util.List.of() : getArguments().getParcelableArrayList("filters");
        if (filter == null) filter = java.util.List.of();
        if (getParentFragment() instanceof FilterListener host) listener = host;
        binding.close.setOnClickListener(v -> dismiss());
        binding.recycler.setHasFixedSize(true);
        binding.recycler.setAdapter(new FilterAdapter(listener, filter));
    }
}
