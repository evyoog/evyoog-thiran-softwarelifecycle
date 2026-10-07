package com.vyoog.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** VYB-0937 (F27): what {@link Redactor} takes out, what it replaces, what it leaves alone, and what it puts back. */
class RedactorTest {

    private final Redactor redactor = new Redactor();
    private static final Set<DataClass> ALL_ON = EnumSet.noneOf(DataClass.class);

    private Redaction run(String text) {
        return redactor.redact(text, ALL_ON, List.of());
    }

    // ------------------------------------------------------------------ secrets

    @Test
    void VYB0937_AC1_everyKindOfSecretIsRemovedAndNothingOfItSurvives() {
        String awsKey = "AKIAIOSFODNN7EXAMPLE";
        String openAi = "sk-proj-abcdefghijklmnopqrstuvwxyz0123456789";
        String github = "ghp_" + "a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6q7R8";
        String slack = "xoxb-123456789012-abcdefghijkl";
        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dBjftJeZ4CVPmB92K27uhbUJU1p1r_wW1gFWFOEjXk";
        String bearer = "Bearer abcdef0123456789abcdef0123456789";
        String stripe = "sk_live_" + "4eC39HqLyjWDarjtT1zdp7dc";
        String google = "AIza" + "SyA-1234567890abcdefghijklmnopqrstuv";
        String pem = "-----BEGIN RSA PRIVATE KEY-----\nMIIEowIBAAKCAQEAxample\nbase64base64base64\n-----END RSA PRIVATE KEY-----";

        for (String secret : List.of(awsKey, openAi, github, slack, jwt, bearer, stripe, google, pem)) {
            Redaction r = run("Before. " + secret + " After.");
            assertThat(r.text()).as(secret.substring(0, 12)).doesNotContain(secret.length() > 40 ? secret.substring(8, 40) : secret.substring(4))
                .contains(Redactor.SECRET_MARKER).startsWith("Before. ").endsWith(" After.");
            assertThat(r.counts()).containsEntry(DataClass.SECRET, 1);
            assertThat(r.tokens()).as("a secret is never restorable").isEmpty();
        }
    }

    @Test
    void VYB0937_AC1_theUserAndPasswordInAUrlGoButTheRestOfTheUrlStays() {
        Redaction r = run("jdbc via postgresql://svc_user:Sup3rS3cret@db.internal:5432/vyoog and https://example.com/docs");

        assertThat(r.text()).doesNotContain("Sup3rS3cret").doesNotContain("svc_user")
            .contains("postgresql://" + Redactor.SECRET_MARKER + "@db.internal:5432/vyoog").contains("https://example.com/docs");
    }

    @Test
    void VYB0937_AC1_theValueOfACredentialAssignmentGoesAndTheNameStays() {
        for (String line : List.of("password = Tr0ub4dor&3", "DB_PASSWORD=hunter2hunter2", "api_key: \"k3y-v4lu3-1234\"", "\"client_secret\": \"s3cr3t-value-99\"", "token=abc123def456")) {
            Redaction r = run(line);
            assertThat(r.text()).as(line).contains(Redactor.SECRET_MARKER);
            assertThat(r.text()).as(line).doesNotContain("Tr0ub4dor").doesNotContain("hunter2hunter2").doesNotContain("k3y-v4lu3")
                .doesNotContain("s3cr3t-value-99").doesNotContain("abc123def456");
        }
        assertThat(run("password = Tr0ub4dor&3").text()).startsWith("password = ");
    }

    @Test
    void VYB0937_AC1_proseThatMerelyMentionsAPasswordIsNotMistakenForOne() {
        for (String prose : List.of("The password must be rotated every 90 days.", "Reset the token: required before login.",
                "A secret: shared between teams.", "The user enters a password and then submits.")) {
            assertThat(run(prose).text()).as(prose).isEqualTo(prose);
        }
    }

    @Test
    void VYB0937_AC2_secretsCannotBeOptedOutEvenIfTheyAreInTheDisabledSet() {
        Redaction r = redactor.redact("key AKIAIOSFODNN7EXAMPLE here", EnumSet.allOf(DataClass.class), List.of());

        assertThat(r.text()).doesNotContain("AKIAIOSFODNN7EXAMPLE").contains(Redactor.SECRET_MARKER);
    }

    // ------------------------------------------------------------------ personal data

    @Test
    void VYB0937_AC3_anEmailIsReplacedByATokenAndTheSameAddressGetsTheSameToken() {
        Redaction r = run("Ask ann.lee@example.com or bob@corp.example.org; ann.lee@example.com again.");

        assertThat(r.text()).isEqualTo("Ask [EMAIL_1] or [EMAIL_2]; [EMAIL_1] again.");
        assertThat(r.tokens()).containsEntry("[EMAIL_1]", "ann.lee@example.com").containsEntry("[EMAIL_2]", "bob@corp.example.org");
        assertThat(r.counts()).containsEntry(DataClass.EMAIL, 3);
    }

    @Test
    void VYB0937_AC3_aCardNumberIsReplacedOnlyIfItPassesTheLuhnCheck() {
        Redaction real = run("Pay with 4111 1111 1111 1111 today.");
        assertThat(real.text()).isEqualTo("Pay with [CARD_1] today.");

        assertThat(run("Order 4111 1111 1111 1112 shipped.").text()).as("fails Luhn: an id, not a card").contains("4111 1111 1111 1112");
        assertThat(run("Timestamp 1696672800000 ms").text()).contains("1696672800000");
    }

    @Test
    void VYB0937_AC3_anIpAddressIsReplacedButADateOrAnOutOfRangeNumberIsNot() {
        assertThat(run("Server 192.168.10.25 and 10.0.0.1.").text()).isEqualTo("Server [IP_1] and [IP_2].");
        assertThat(run("Released on 2026-10-07 at 12.30.45").text()).isEqualTo("Released on 2026-10-07 at 12.30.45");
        assertThat(run("Not an address 999.1.1.1").text()).contains("999.1.1.1");
    }

    @Test
    void VYB0937_AC3_aPhoneNumberInTheThreeShapesIsReplacedAndABareRunOfDigitsIsNot() {
        assertThat(run("Call +44 20 7946 0958 now").text()).isEqualTo("Call [PHONE_1] now");
        assertThat(run("Call (415) 555-2671 now").text()).isEqualTo("Call [PHONE_1] now");
        assertThat(run("Call 415-555-2671 now").text()).isEqualTo("Call [PHONE_1] now");
        assertThat(run("Call 98765 43210 now").text()).isEqualTo("Call [PHONE_1] now");

        assertThat(run("Retain 1234567890 records for 100 200 300 400 days").text()).as("numbers in a requirement").contains("1234567890").contains("100 200 300 400");
        assertThat(run("Requirement VY-0042 revision 12, 3 steps").text()).isEqualTo("Requirement VY-0042 revision 12, 3 steps");
    }

    @Test
    void VYB0937_AC4_thePeopleInTheUserTableAreFoundAsWholeWordsInAnyCaseAndLongestFirst() {
        Redaction r = redactor.redact("Ann Lee approved it; ask ann lee or ANN. Annabel disagrees. Dr. Ann Lee-Smith too.", ALL_ON,
            List.of("Ann Lee", "Ann", "Annabel"));

        assertThat(r.text()).doesNotContain("Ann Lee approved").contains("[PERSON_1] approved it; ask");
        String annabel = r.tokens().entrySet().stream().filter(e -> e.getValue().equals("Annabel")).map(java.util.Map.Entry::getKey).findFirst().orElseThrow();
        assertThat(r.text()).as("Annabel is its own person, not Ann plus bel").contains(annabel + " disagrees");
        assertThat(r.tokens().values()).contains("Ann Lee", "Annabel").as("each spelling is its own token and comes back as written").contains("ann lee");
    }

    @Test
    void VYB0937_AC4_aNameShorterThanThreeCharactersIsNotMatchedBecauseItWouldHitOrdinaryWords() {
        Redaction r = redactor.redact("Li said it is fine, and Al agreed.", ALL_ON, List.of("Li", "Al", "Bo"));

        assertThat(r.text()).isEqualTo("Li said it is fine, and Al agreed.");
    }

    @Test
    void VYB0937_AC4_aNameThatIsNotInTheListIsNotFoundWhichIsTheStatedLimit() {
        assertThat(run("Please ask Zephaniah Quill about it.").text()).isEqualTo("Please ask Zephaniah Quill about it.");
    }

    // ------------------------------------------------------------------ opt-out

    @Test
    void VYB0937_AC5_eachPersonalDataClassCanBeSwitchedOffOnItsOwn() {
        String text = "Mail a@b.example, call (415) 555-2671, host 10.1.2.3, card 4111111111111111, ask Ann Lee.";
        List<String> people = List.of("Ann Lee");

        for (DataClass off : List.of(DataClass.EMAIL, DataClass.PHONE, DataClass.IP_ADDRESS, DataClass.CARD, DataClass.PERSON)) {
            Redaction r = redactor.redact(text, EnumSet.of(off), people);
            assertThat(r.counts()).as("off: " + off).doesNotContainKey(off);
            assertThat(r.counts().keySet()).as("the others stay on, off: " + off).hasSize(4);
        }
        Redaction allOff = redactor.redact(text, EnumSet.of(DataClass.EMAIL, DataClass.PHONE, DataClass.IP_ADDRESS, DataClass.CARD, DataClass.PERSON), people);
        assertThat(allOff.text()).isEqualTo(text);
        assertThat(allOff.changedAnything()).isFalse();
    }

    // ------------------------------------------------------------------ restore

    @Test
    void VYB0937_AC6_theOriginalsComeBackInTheReplyAndASecretNeverDoes() {
        Redaction r = run("Mail ann@example.com with key AKIAIOSFODNN7EXAMPLE");
        assertThat(r.text()).isEqualTo("Mail [EMAIL_1] with key " + Redactor.SECRET_MARKER);

        String restored = r.restore("I will mail [EMAIL_1] and keep " + Redactor.SECRET_MARKER + " out of it. [EMAIL_1] [EMAIL_7]", false);

        assertThat(restored).isEqualTo("I will mail ann@example.com and keep " + Redactor.SECRET_MARKER + " out of it. ann@example.com [EMAIL_7]");
        assertThat(restored).doesNotContain("AKIA");
    }

    @Test
    void VYB0937_AC6_insideJsonTheOriginalsAreEscapedSoTheDocumentStillParses() throws Exception {
        Redaction r = redactor.redact("Ask Ann \"The Rock\" Lee", ALL_ON, List.of("Ann \"The Rock\" Lee"));
        assertThat(r.text()).isEqualTo("Ask [PERSON_1]");

        String reply = r.restore("{\"owner\":\"[PERSON_1]\"}", true);

        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().readTree(reply).path("owner").asText()).isEqualTo("Ann \"The Rock\" Lee");
    }

    @Test
    void VYB0937_AC6_tokensWithDifferentNumbersDoNotCollide() {
        StringBuilder text = new StringBuilder();
        for (int i = 1; i <= 12; i++) text.append("u").append(i).append("@x.example ");
        Redaction r = run(text.toString());

        String restored = r.restore("[EMAIL_1] [EMAIL_10] [EMAIL_12]", false);

        assertThat(restored).isEqualTo("u1@x.example u10@x.example u12@x.example");
    }

    // ------------------------------------------------------------------ robustness

    @Test
    void VYB0937_AC7_emptyAndNullTextPassThrough() {
        assertThat(run("").text()).isEmpty();
        assertThat(run(null).text()).isNull();
        assertThat(run("   ").text()).isEqualTo("   ");
    }

    @Test
    void VYB0937_AC7_aLargeDocumentIsHandledInReasonableTimeEvenWhenItIsAdversarial() {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 20_000; i++) big.append("The system shall respond within ").append(i).append(" ms for user ann").append(i).append("@example.com. ");
        String evil = "a".repeat(200_000) + "@" + "b.".repeat(100_000) + " " + "1".repeat(100_000) + " " + "password=".repeat(20_000);

        long start = System.nanoTime();
        Redaction large = redactor.redact(big.toString(), ALL_ON, List.of("Ann Lee"));
        redactor.redact(evil, ALL_ON, List.of("Ann Lee"));
        long ms = (System.nanoTime() - start) / 1_000_000;

        assertThat(large.counts().get(DataClass.EMAIL)).isEqualTo(20_000);
        assertThat(ms).as("%d ms for about 1.3 MB of normal and 0.7 MB of hostile text", ms).isLessThan(10_000);
    }
}
