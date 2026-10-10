package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.CloudAccount;
import com.fongmi.android.tv.cloud.CloudAccountManager;
import com.fongmi.android.tv.cloud.CloudCapability;
import com.fongmi.android.tv.cloud.CloudLoginRoute;
import com.fongmi.android.tv.cloud.CloudProvider;
import com.fongmi.android.tv.databinding.AdapterCloudAccountBinding;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class CloudAccountAdapter extends RecyclerView.Adapter<CloudAccountAdapter.ViewHolder> {

    private final java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(java.time.ZoneId.systemDefault());

    public interface Listener {
        void onEdit(CloudProvider provider);

        void onRepositoryLogin(CloudLoginRoute route);

        void onValidate(CloudProvider provider);

        void onLogout(CloudProvider provider);
    }

    private final Listener listener;
    private List<Entry> items = initialEntries();

    public CloudAccountAdapter(Listener listener) {
        this.listener = listener;
        setHasStableIds(true);
    }

    public void submitRoutes(List<CloudLoginRoute> routes) {
        Map<String, CloudLoginRoute> routeByProvider = new LinkedHashMap<>();
        for (CloudLoginRoute route : routes) {
            String id = routeProviderId(route);
            routeByProvider.putIfAbsent(id, route);
        }
        List<Entry> next = new ArrayList<>();
        Set<String> added = new HashSet<>();
        Map<String, CloudProvider> known = new LinkedHashMap<>();
        for (CloudProvider provider : CloudProvider.ALL) known.put(provider.id(), provider);
        for (Map.Entry<String, CloudLoginRoute> item : routeByProvider.entrySet()) {
            if (!added.add(item.getKey())) continue;
            CloudLoginRoute route = item.getValue();
            CloudProvider provider = known.get(item.getKey());
            if (provider == null) provider = new CloudProvider(item.getKey(), displayTitle(route.title()), "CREDENTIAL", "Cookie / Token");
            next.add(new Entry(provider, route));
        }
        for (CloudProvider provider : CloudProvider.ALL) if (added.add(provider.id())) next.add(new Entry(provider, null));
        update(Collections.unmodifiableList(next));
    }

    private void update(List<Entry> next) {
        List<Entry> previous = items;
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override
            public int getOldListSize() {
                return previous.size();
            }

            @Override
            public int getNewListSize() {
                return next.size();
            }

            @Override
            public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
                return previous.get(oldItemPosition).provider().id().equals(next.get(newItemPosition).provider().id());
            }

            @Override
            public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
                return previous.get(oldItemPosition).equals(next.get(newItemPosition));
            }
        });
        items = next;
        diff.dispatchUpdatesTo(this);
    }

    private static List<Entry> initialEntries() {
        return CloudProvider.ALL.stream().map(provider -> new Entry(provider, null)).toList();
    }

    private static String routeProviderId(CloudLoginRoute route) {
        return "unknown".equals(route.providerId())
                ? "repository_" + Integer.toHexString(route.stableId().hashCode())
                : route.providerId();
    }

    private static String displayTitle(String title) {
        String value = title.replaceFirst("(?:账号|账户)?设置$", "").trim();
        return value.isEmpty() ? title : value;
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @Override
    public long getItemId(int position) {
        return items.get(position).provider().id().hashCode();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterCloudAccountBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Entry entry = items.get(position);
        CloudProvider provider = entry.provider();
        CloudLoginRoute route = entry.route();
        CloudAccount account = CloudAccountManager.get(provider.id());
        boolean signedIn = account != null;
        holder.binding.name.setText(provider.name());
        holder.binding.type.setText(provider.hint());
        holder.binding.credential.setText(signedIn ? CloudAccountManager.mask(account) : holder.itemView.getContext().getString(
                route == null ? R.string.cloud_not_signed_in : R.string.cloud_repository_credential));
        if (route != null && !signedIn) holder.binding.status.setText(R.string.cloud_repository_managed);
        else holder.binding.status.setText(status(holder, account));
        CloudCapability capability = CloudAccountManager.capability(provider.id());
        holder.binding.support.setText(route == null
                ? holder.itemView.getContext().getString(R.string.cloud_support_and_methods, support(holder, capability), provider.hint())
                : holder.itemView.getContext().getString(R.string.cloud_repository_method, provider.hint()));
        holder.binding.time.setText(time(holder, account));
        holder.binding.validate.setVisibility(signedIn ? View.VISIBLE : View.GONE);
        holder.binding.logout.setVisibility(signedIn ? View.VISIBLE : View.GONE);
        holder.binding.manual.setVisibility(route == null ? View.GONE : View.VISIBLE);
        holder.binding.edit.setText(route == null
                ? (signedIn ? R.string.cloud_update : R.string.cloud_sign_in)
                : R.string.cloud_scan_button);
        holder.binding.info.setOnClickListener(v -> {
            if (route == null) listener.onEdit(provider);
            else listener.onRepositoryLogin(route);
        });
        holder.binding.edit.setOnClickListener(v -> {
            if (route == null) listener.onEdit(provider);
            else listener.onRepositoryLogin(route);
        });
        holder.binding.manual.setOnClickListener(v -> listener.onEdit(provider));
        holder.binding.validate.setOnClickListener(v -> listener.onValidate(provider));
        holder.binding.logout.setOnClickListener(v -> listener.onLogout(provider));
    }

    private record Entry(CloudProvider provider, CloudLoginRoute route) {
    }

    private String status(ViewHolder holder, CloudAccount account) {
        if (account == null) return holder.itemView.getContext().getString(R.string.cloud_status_missing);
        return switch (account.getStatus()) {
            case CloudAccountManager.STATUS_CONFIGURED -> holder.itemView.getContext().getString(R.string.cloud_status_configured);
            case CloudAccountManager.STATUS_VALIDATING -> holder.itemView.getContext().getString(R.string.cloud_status_validating);
            case CloudAccountManager.STATUS_VALID -> holder.itemView.getContext().getString(R.string.cloud_status_valid);
            case CloudAccountManager.STATUS_INVALID -> holder.itemView.getContext().getString(R.string.cloud_status_invalid);
            case CloudAccountManager.STATUS_EXPIRED -> holder.itemView.getContext().getString(R.string.cloud_status_expired);
            case CloudAccountManager.STATUS_UNSUPPORTED -> holder.itemView.getContext().getString(R.string.cloud_status_unsupported);
            case CloudAccountManager.STATUS_DECRYPT_FAILED -> holder.itemView.getContext().getString(R.string.cloud_status_decrypt_failed);
            case CloudAccountManager.STATUS_LOGIN_REQUIRED -> holder.itemView.getContext().getString(R.string.cloud_status_login_required);
            default -> holder.itemView.getContext().getString(R.string.cloud_status_unknown);
        };
    }

    private String support(ViewHolder holder, CloudCapability capability) {
        return switch (capability.support()) {
            case SUPPORTED -> holder.itemView.getContext().getString(R.string.cloud_spider_supported);
            case UNSUPPORTED -> holder.itemView.getContext().getString(R.string.cloud_spider_unsupported);
            case UNKNOWN -> holder.itemView.getContext().getString(R.string.cloud_spider_unknown);
        };
    }

    private String time(ViewHolder holder, CloudAccount account) {
        if (account == null) return holder.itemView.getContext().getString(R.string.cloud_never_verified);
        String updated = account.getUpdatedAt() == 0 ? "—" : formatter.format(java.time.Instant.ofEpochMilli(account.getUpdatedAt()));
        String checked = account.getLastVerifiedAt() == 0
                ? holder.itemView.getContext().getString(R.string.cloud_never_verified)
                : holder.itemView.getContext().getString(R.string.cloud_verified_at, formatter.format(java.time.Instant.ofEpochMilli(account.getLastVerifiedAt())));
        return holder.itemView.getContext().getString(R.string.cloud_updated_at, updated) + " · " + checked;
    }

    static class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterCloudAccountBinding binding;

        ViewHolder(AdapterCloudAccountBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
