package com.fongmi.android.tv.ui.search;

import android.speech.SpeechRecognizer;
import android.view.KeyEvent;

import java.util.List;

public final class VoiceSearchPolicy {

    public enum ErrorAction {
        CANCELLED,
        PERMISSION_DENIED,
        NO_MATCH,
        FAILURE
    }

    private VoiceSearchPolicy() {
    }

    public static boolean isActivationKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_SEARCH
                || keyCode == KeyEvent.KEYCODE_VOICE_ASSIST
                || keyCode == KeyEvent.KEYCODE_ASSIST;
    }

    public static ErrorAction classifyError(int error) {
        if (error == SpeechRecognizer.ERROR_CLIENT) return ErrorAction.CANCELLED;
        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) return ErrorAction.PERMISSION_DENIED;
        if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) return ErrorAction.NO_MATCH;
        return ErrorAction.FAILURE;
    }

    public static String firstResult(List<String> results) {
        if (results == null) return "";
        for (String result : results) {
            if (result != null && !result.trim().isEmpty()) return result.trim();
        }
        return "";
    }
}
