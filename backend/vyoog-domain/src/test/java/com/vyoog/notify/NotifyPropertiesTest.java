package com.vyoog.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** VYB-0955 (D47, rule 9): email and chat are off until set up, a half-set channel stops the application starting, and a secret address must be https. */
class NotifyPropertiesTest {

    private static NotifyProperties of(String host, String username, String password, String from, String hook, String baseUrl) {
        return new NotifyProperties(host, 587, username, password, from, true, hook, baseUrl);
    }

    @Test
    void VYB0955_AC4_withNothingSetBothChannelsAreOffAndMessagesCarryNoLink() {
        NotifyProperties p = of("", "", "", "", "", "");
        assertThat(p.emailEnabled()).isFalse();
        assertThat(p.chatEnabled()).isFalse();
        assertThat(p.absoluteLink("/requirements/1")).isNull();
    }

    @Test
    void VYB0955_AC4_aChannelIsOnOnlyWhenItsOwnValuesAreSetAndALinkJoinsTheAddressWithoutADoubleSlash() {
        NotifyProperties p = of("smtp.example.test", "", "", "vyoog@example.test", "https://example.test/hook?sig=abc", "https://app.example.test/");
        assertThat(p.emailEnabled()).isTrue();
        assertThat(p.chatEnabled()).isTrue();
        assertThat(p.absoluteLink("/requirements/1")).isEqualTo("https://app.example.test/requirements/1");
        assertThat(p.absoluteLink("requirements/1")).as("only an in-app path").isNull();
        assertThat(p.absoluteLink(null)).isNull();
    }

    @Test
    void VYB0955_AC4_aHalfSetEmailChannelRefusesToStartAndSaysWhichValueIsMissing() {
        assertThatThrownBy(() -> of("smtp.example.test", "", "", "", "", "")).isInstanceOf(IllegalStateException.class).hasMessageContaining("MAIL_FROM");
        assertThatThrownBy(() -> of("", "", "", "vyoog@example.test", "", "")).isInstanceOf(IllegalStateException.class).hasMessageContaining("MAIL_HOST");
        assertThatThrownBy(() -> of("smtp.example.test", "user", "", "vyoog@example.test", "", "")).isInstanceOf(IllegalStateException.class).hasMessageContaining("MAIL_PASSWORD");
    }

    @Test
    void VYB0955_AC4_theChatAddressMustBeHttpsExceptOnThisMachineAndTheErrorNeverRepeatsIt() {
        assertThatThrownBy(() -> of("", "", "", "", "http://example.test/hook?sig=SECRET", ""))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("https").hasMessageNotContaining("SECRET");
        assertThatThrownBy(() -> of("", "", "", "", "not a url at all SECRET", ""))
            .isInstanceOf(IllegalStateException.class).hasMessageNotContaining("SECRET");
        assertThatThrownBy(() -> of("", "", "", "", "ftp://example.test/hook", "")).isInstanceOf(IllegalStateException.class);
        assertThat(of("", "", "", "", "http://127.0.0.1:9/hook", "").chatEnabled()).as("plain http is for tests on this machine").isTrue();
        assertThat(of("", "", "", "", "http://localhost:9/hook", "").chatEnabled()).isTrue();
    }
}
