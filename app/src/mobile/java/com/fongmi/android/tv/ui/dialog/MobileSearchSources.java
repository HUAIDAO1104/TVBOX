package com.fongmi.android.tv.ui.dialog;

import androidx.fragment.app.Fragment;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.search.SearchSourcePreference;
import com.fongmi.android.tv.utils.Notify;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.*;

/** One preference owner for search entry and results, scoped to the current subscription. */
public final class MobileSearchSources {
    private MobileSearchSources() { }
    public static String scope() {
        String url = VodConfig.getUrl();
        return url != null && !url.isBlank() ? url.trim() : "active-default:" + VodConfig.get().getHome().getKey();
    }
    public static Set<String> selected() {
        return SearchSourcePreference.resolveSelection(Setting.getSearchSources(scope()), VodConfig.get().getSites(), VodConfig.get().getHome());
    }
    public static void show(Fragment fragment, Runnable changed) {
        List<Site> sites = VodConfig.get().getSites();
        List<String> ids = SearchSourcePreference.choices(sites);
        if (ids.isEmpty()) { Notify.show(R.string.mobile_no_sources); return; }
        Map<String, String> labels = SearchSourcePreference.labels(sites);
        String[] names = ids.stream().map(labels::get).toArray(String[]::new);
        Set<String> selected = new LinkedHashSet<>(selected());
        boolean[] checked = new boolean[ids.size()];
        for (int i = 0; i < ids.size(); i++) checked[i] = selected.contains(ids.get(i));
        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(fragment.requireContext())
            .setTitle(R.string.mobile_sources)
            .setMultiChoiceItems(names, checked, (d, which, value) -> { if (value) selected.add(ids.get(which)); else selected.remove(ids.get(which)); })
            .setNegativeButton(R.string.dialog_negative, null)
            .setNeutralButton(R.string.mobile_select_all, null)
            .setPositiveButton(R.string.mobile_apply_sources, null).create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(-3).setOnClickListener(v -> {
                boolean all = selected.size() != ids.size();
                selected.clear();
                if (all) selected.addAll(ids);
                for (int i = 0; i < ids.size(); i++) dialog.getListView().setItemChecked(i, all);
            });
            dialog.getButton(-1).setOnClickListener(v -> {
                if (selected.isEmpty()) { Notify.show(R.string.mobile_source_required); return; }
                Setting.putSearchSources(scope(), SearchSourcePreference.serialize(selected));
                dialog.dismiss();
                changed.run();
            });
        });
        dialog.show();
    }
}
