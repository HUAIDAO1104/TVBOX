package com.fongmi.android.tv.server;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import com.fongmi.android.tv.App;
import java.net.*;
import java.util.*;

/** Advertise the connected LAN, never a stale Wi-Fi, VPN or cellular interface. */
final class LanAddress {
    record Candidate(String address, boolean lan, boolean active, boolean routed) { }

    static String current() {
        List<Candidate> candidates = new ArrayList<>();
        try {
            var manager = (ConnectivityManager) App.get().getSystemService(Context.CONNECTIVITY_SERVICE);
            var active = manager.getActiveNetwork();
            for (var network : manager.getAllNetworks()) {
                var caps = manager.getNetworkCapabilities(network);
                var links = manager.getLinkProperties(network);
                if (caps == null || links == null) continue;
                boolean lan = !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                        && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
                boolean routed = links.getRoutes().stream().anyMatch(route -> route.isDefaultRoute());
                for (var link : links.getLinkAddresses()) if (link.getAddress() instanceof Inet4Address)
                    candidates.add(new Candidate(link.getAddress().getHostAddress(), lan, network.equals(active), routed));
            }
            // A disconnected device must not advertise a leftover interface address.
            return select(candidates);
        } catch (Exception ignored) {
            // Older TV firmware can deny ConnectivityManager enumeration.
            try {
                for (var nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                    if (!nic.isUp() || nic.isLoopback() || nic.isVirtual() || nic.isPointToPoint()) continue;
                    String name = nic.getName();
                    if (!(name.startsWith("wlan") || name.startsWith("eth") || name.startsWith("en"))) continue;
                    for (var address : Collections.list(nic.getInetAddresses())) if (address instanceof Inet4Address)
                        candidates.add(new Candidate(address.getHostAddress(), true, false, false));
                }
            } catch (Exception unavailable) { }
            return select(candidates);
        }
    }

    static String select(List<Candidate> candidates) {
        return candidates.stream().filter(c -> c.lan() && valid(c.address()))
                .sorted(Comparator.comparingInt((Candidate c) -> (c.active() ? 2 : 0) + (c.routed() ? 1 : 0)).reversed()
                        .thenComparing(Candidate::address)).map(Candidate::address).findFirst().orElse("");
    }

    private static boolean valid(String address) {
        if (address == null || !address.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")) return false;
        try {
            var ip = InetAddress.getByName(address);
            return ip instanceof Inet4Address && !ip.isAnyLocalAddress() && !ip.isLoopbackAddress()
                    && !ip.isLinkLocalAddress() && !ip.isMulticastAddress();
        } catch (Exception ignored) { return false; }
    }
}
