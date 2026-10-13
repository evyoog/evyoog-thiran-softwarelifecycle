package com.vyoog.api.notify;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vyoog.notify.NotifyProperties;
import org.junit.jupiter.api.Test;

/** VYB-0955 (D47, rule 9): what is stored on a failed row and shown to an administrator never carries a secret, and a subject cannot be bent. */
class NotificationDeliveryServiceTest {

    private static NotificationDeliveryService service(String password, String hook) {
        NotifyProperties p = new NotifyProperties("smtp.example.test", 587, "user", password, "vyoog@example.test", true, hook, "");
        return new NotificationDeliveryService(null, p, new ObjectMapper());
    }

    @Test
    void VYB0955_AC18_theChatAddressAndTheMailPasswordAreRemovedFromAnErrorWhereverTheyAppear() {
        var s = service("hunter2-password", "https://example.test/workflows/abc/signatures/SECRETSIG?sig=XYZ");

        String clean = s.scrub("IllegalStateException: could not post to https://example.test/workflows/abc/signatures/SECRETSIG?sig=XYZ with hunter2-password");

        assertThat(clean).doesNotContain("SECRETSIG").doesNotContain("sig=XYZ").doesNotContain("hunter2-password");
        assertThat(clean).contains("[removed]");
    }

    @Test
    void VYB0955_AC18_anAddressInAnotherFormIsStillCutBackToItsHostAndALineBreakIsFlattened() {
        var s = service("pw", "https://example.test/hook");

        String clean = s.scrub("failed\nGET https://other.example.test/some/long/path?token=abc&x=1 returned 400");

        assertThat(clean).doesNotContain("token=abc").doesNotContain("/some/long/path").doesNotContain("\n");
        assertThat(clean).contains("https://other.example.test");
    }

    @Test
    void VYB0955_AC14_aSubjectIsOneLineWithTheProductNameAndIsCutShort() {
        assertThat(NotificationDeliveryService.subjectOf("Hello\r\nBcc: someone@else.test")).isEqualTo("[Vyoog] Hello Bcc: someone@else.test");
        assertThat(NotificationDeliveryService.subjectOf(null)).isEqualTo("[Vyoog] ");
        String longTitle = "x".repeat(400);
        assertThat(NotificationDeliveryService.subjectOf(longTitle).length()).isLessThan(170);
    }
}
