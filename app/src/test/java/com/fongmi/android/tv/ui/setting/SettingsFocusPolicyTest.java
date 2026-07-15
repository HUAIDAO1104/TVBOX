package com.fongmi.android.tv.ui.setting;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SettingsFocusPolicyTest {

    @Test
    public void focusedItemInsideSafeViewportDoesNotScroll() {
        assertEquals(0, SettingsFocusPolicy.requiredScrollDelta(0, 540, 100, 148, 24));
    }

    @Test
    public void focusedItemNearTopScrollsIntoSafeArea() {
        assertEquals(-18, SettingsFocusPolicy.requiredScrollDelta(0, 540, 6, 54, 24));
    }

    @Test
    public void focusedItemNearBottomScrollsIntoSafeArea() {
        assertEquals(32, SettingsFocusPolicy.requiredScrollDelta(0, 540, 500, 548, 24));
    }

    @Test
    public void calculationUsesScrolledViewportCoordinates() {
        assertEquals(0, SettingsFocusPolicy.requiredScrollDelta(300, 840, 400, 448, 24));
        assertEquals(32, SettingsFocusPolicy.requiredScrollDelta(300, 840, 800, 848, 24));
    }

    @Test
    public void restoredStateRejectsInvalidValues() {
        assertEquals(0, SettingsFocusPolicy.sanitizeScrollY(-20));
        assertEquals(42, SettingsFocusPolicy.resolveFocusId(-1, 42));
        assertEquals(84, SettingsFocusPolicy.resolveFocusId(84, 42));
    }
}
