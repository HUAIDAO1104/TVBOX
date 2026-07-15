package com.fongmi.android.tv.security;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ToastPolicyTest {

    @Test
    public void detectsOnlyToastShowFrames() {
        assertTrue(ToastPolicy.containsToastShow(new StackTraceElement[]{
                new StackTraceElement("android.widget.Toast", "show", "Toast.java", 200)
        }));
        assertFalse(ToastPolicy.containsToastShow(new StackTraceElement[]{
                new StackTraceElement("android.widget.Toast", "makeText", "Toast.java", 500),
                new StackTraceElement("com.fongmi.android.tv.utils.Notify", "show", "Notify.java", 55)
        }));
        assertFalse(ToastPolicy.containsToastShow(null));
    }
}
