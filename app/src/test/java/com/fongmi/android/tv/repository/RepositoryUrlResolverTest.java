package com.fongmi.android.tv.repository;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.List;

public class RepositoryUrlResolverTest {

    private static final String DIRECT = "https://raw.githubusercontent.com/tushen6/Tomorrow/master/lmw.json";

    @Test
    public void unwrapsLlkkGithubMirrorBeforeTryingConfiguredProxy() {
        String proxy = "https://gh.llkk.cc/" + DIRECT;

        assertEquals(List.of(DIRECT, proxy), RepositoryUrlResolver.candidates(proxy));
    }

    @Test
    public void keepsOrdinaryRepositoryUrlUnchanged() {
        String url = "https://raw.githubusercontent.com/qist/tvbox/master/fty.json";

        assertEquals(List.of(url), RepositoryUrlResolver.candidates(url));
        assertEquals(List.of(), RepositoryUrlResolver.candidates("  "));
    }
}
