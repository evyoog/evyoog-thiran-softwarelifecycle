package com.vyoog.attachments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vyoog.attachments.AttachmentRejectedException.Reason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** VYB-0901 (F06): size limit, extension and content-type allowlist, and a filename that cannot escape its key. */
class AttachmentPolicyTest {

    private final AttachmentPolicy policy = new AttachmentPolicy(1_000);

    @Test
    void VYB0901_AC3_aNormalFileIsAccepted() {
        assertThat(policy.check("spec v2.pdf", "application/pdf", 500)).isEqualTo("spec v2.pdf");
        assertThat(policy.check("notes.txt", "text/plain; charset=utf-8", 1)).isEqualTo("notes.txt");
    }

    @Test
    void VYB0901_AC3_aFileOverTheLimitIsRefusedAsTooLarge() {
        assertThatThrownBy(() -> policy.check("a.pdf", "application/pdf", 1_001))
            .isInstanceOfSatisfying(AttachmentRejectedException.class, e -> assertThat(e.reason()).isEqualTo(Reason.TOO_LARGE));
        assertThat(policy.check("a.pdf", "application/pdf", 1_000)).isEqualTo("a.pdf");
    }

    @Test
    void VYB0901_AC3_anEmptyFileIsRefused() {
        assertThatThrownBy(() -> policy.check("a.pdf", "application/pdf", 0))
            .isInstanceOfSatisfying(AttachmentRejectedException.class, e -> assertThat(e.reason()).isEqualTo(Reason.EMPTY));
    }

    @ParameterizedTest
    @CsvSource({
        "page.html,text/html", "vector.svg,image/svg+xml", "run.exe,application/octet-stream",
        "app.js,application/javascript", "bundle.zip,application/zip", "noext,text/plain",
        "evil.pdf.exe,application/pdf"})
    void VYB0901_AC3_activeOrUnknownTypesAreRefusedByExtension(String name, String type) {
        assertThatThrownBy(() -> policy.check(name, type, 10))
            .isInstanceOfSatisfying(AttachmentRejectedException.class, e -> assertThat(e.reason()).isEqualTo(Reason.TYPE_NOT_ALLOWED));
    }

    @Test
    void VYB0901_AC3_anAllowedExtensionWithADisallowedOrMissingContentTypeIsRefused() {
        assertThatThrownBy(() -> policy.check("a.pdf", "text/html", 10))
            .isInstanceOfSatisfying(AttachmentRejectedException.class, e -> assertThat(e.reason()).isEqualTo(Reason.TYPE_NOT_ALLOWED));
        assertThatThrownBy(() -> policy.check("a.pdf", null, 10))
            .isInstanceOfSatisfying(AttachmentRejectedException.class, e -> assertThat(e.reason()).isEqualTo(Reason.TYPE_NOT_ALLOWED));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "../../etc/passwd.txt|passwd.txt",
        "..\\..\\windows\\notes.txt|notes.txt",
        "/abs/path/notes.txt|notes.txt",
        "..notes.txt|notes.txt",
        ".hidden.txt|hidden.txt",
        "a/../b/c.txt|c.txt",
        "we ird;na$me?.txt|we ird_na_me_.txt",
        "a\u0000b.txt|ab.txt"})
    void VYB0901_AC3_aFilenameKeepsOnlyASafeLastSegment(String raw, String expected) {
        assertThat(AttachmentPolicy.sanitiseFilename(raw)).isEqualTo(expected);
    }

    @Test
    void VYB0901_AC3_aSanitisedNameNeverContainsASeparatorOrDotDot() {
        for (String raw : new String[] {"../x.txt", "a/b/c.txt", "..\\..\\x.txt", "%2e%2e/x.txt", "....//x.txt"}) {
            String s = AttachmentPolicy.sanitiseFilename(raw);
            assertThat(s).doesNotContain("/", "\\", "..");
        }
    }

    @Test
    void VYB0901_AC3_aNameWithNothingUsableIsRefused() {
        for (String raw : new String[] {"", "...", "../", null}) {
            assertThatThrownBy(() -> AttachmentPolicy.sanitiseFilename(raw))
                .isInstanceOfSatisfying(AttachmentRejectedException.class, e -> assertThat(e.reason()).isEqualTo(Reason.BAD_FILENAME));
        }
    }

    @Test
    void VYB0901_AC3_aLongNameIsTruncatedButKeepsItsExtension() {
        String s = AttachmentPolicy.sanitiseFilename("a".repeat(300) + ".pdf");
        assertThat(s).hasSize(AttachmentPolicy.MAX_FILENAME_LENGTH).endsWith(".pdf");
    }
}
