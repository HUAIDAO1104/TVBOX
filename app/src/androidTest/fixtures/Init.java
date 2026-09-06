package com.github.catvod.spider;

/** Models native wrappers whose first initialization has not produced their secondary loader. */
public final class Init {
    public static int attempts;
    public static void init(android.content.Context context) { attempts++; }
    public static ClassLoader loader() { return attempts < 2 ? null : Init.class.getClassLoader(); }
}
