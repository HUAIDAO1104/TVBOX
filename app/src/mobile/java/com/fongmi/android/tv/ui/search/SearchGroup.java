package com.fongmi.android.tv.ui.search;

import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.impl.Diffable;
import java.text.Normalizer;
import java.util.*;

/** Only exact normalized titles with the same year/type/area are grouped. Season and edition words remain significant. */
public final class SearchGroup implements Diffable<SearchGroup> {
    public final String id;
    public final List<Vod> sources;
    private SearchGroup(String id, List<Vod> sources) { this.id=id; this.sources=List.copyOf(sources); }
    public Vod first() { return sources.get(0); }
    static String normalize(String s) { return Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Z}]+", ""); }
    public static List<SearchGroup> of(List<Vod> items, boolean aggregate) {
        Map<String,List<Vod>> groups=new LinkedHashMap<>(); Set<String> seen=new HashSet<>();
        for(Vod v:items) {
            String unique=v.getSiteKey()+"\u0000"+(v.getId().isEmpty()?v.getName():v.getId());
            if(!seen.add(unique))continue;
            String key=!aggregate||v.isFolder()||v.getName().isBlank()?unique:
                    normalize(v.getName())+"\u0000"+normalize(v.getYear())+"\u0000"+normalize(v.getTypeName())+"\u0000"+normalize(v.getArea());
            groups.computeIfAbsent(key,k->new ArrayList<>()).add(v);
        }
        List<SearchGroup> result=new ArrayList<>();groups.forEach((key,list)->result.add(new SearchGroup(key,list)));return result;
    }
    @Override public boolean isSameItem(SearchGroup other) { return id.equals(other.id); }
    @Override public boolean isSameContent(SearchGroup other) {
        if(sources.size()!=other.sources.size())return false;
        for(int i=0;i<sources.size();i++)if(!sources.get(i).isSameItem(other.sources.get(i))||!sources.get(i).isSameContent(other.sources.get(i)))return false;
        return true;
    }
}
