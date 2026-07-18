package com.fongmi.android.tv.ui.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.speech.SpeechRecognizer;
import android.view.KeyEvent;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class VoiceSearchPolicyTest {

    @Test
    public void recognizesOnlySearchAndAssistantKeys() {
        assertTrue(VoiceSearchPolicy.isActivationKey(KeyEvent.KEYCODE_SEARCH));
        assertTrue(VoiceSearchPolicy.isActivationKey(KeyEvent.KEYCODE_VOICE_ASSIST));
        assertTrue(VoiceSearchPolicy.isActivationKey(KeyEvent.KEYCODE_ASSIST));
        assertFalse(VoiceSearchPolicy.isActivationKey(KeyEvent.KEYCODE_DPAD_CENTER));
    }

    @Test
    public void selectsFirstNonBlankRecognitionResult() {
        assertEquals("庆余年", VoiceSearchPolicy.firstResult(Arrays.asList(" ", null, " 庆余年 ", "庆余年第二季")));
        assertEquals("", VoiceSearchPolicy.firstResult(List.of("", "  ")));
        assertEquals("", VoiceSearchPolicy.firstResult(null));
    }

    @Test
    public void mapsRecognizerErrorsToUserActions() {
        assertEquals(VoiceSearchPolicy.ErrorAction.CANCELLED, VoiceSearchPolicy.classifyError(SpeechRecognizer.ERROR_CLIENT));
        assertEquals(VoiceSearchPolicy.ErrorAction.PERMISSION_DENIED, VoiceSearchPolicy.classifyError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS));
        assertEquals(VoiceSearchPolicy.ErrorAction.NO_MATCH, VoiceSearchPolicy.classifyError(SpeechRecognizer.ERROR_NO_MATCH));
        assertEquals(VoiceSearchPolicy.ErrorAction.NO_MATCH, VoiceSearchPolicy.classifyError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT));
        assertEquals(VoiceSearchPolicy.ErrorAction.FAILURE, VoiceSearchPolicy.classifyError(SpeechRecognizer.ERROR_NETWORK));
    }
}
