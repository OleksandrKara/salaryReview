package com.salonreview.intake;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntakeAutomatedClientTest {

    @Test
    void linkPreviewersAndScriptsAreNotCountedAsOpens() {
        assertTrue(IntakePublicController.isAutomatedClient(null));
        assertTrue(IntakePublicController.isAutomatedClient(""));
        assertTrue(IntakePublicController.isAutomatedClient("facebookexternalhit/1.1"));
        assertTrue(IntakePublicController.isAutomatedClient("Mozilla/5.0 (compatible; Googlebot/2.1)"));
        assertTrue(IntakePublicController.isAutomatedClient("curl/8.0"));
        assertTrue(IntakePublicController.isAutomatedClient("python-requests/2.31"));
    }

    @Test
    void realPhoneBrowsersAreCountedAsOpens() {
        assertFalse(IntakePublicController.isAutomatedClient(
                "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"));
        assertFalse(IntakePublicController.isAutomatedClient(
                "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"));
    }
}
