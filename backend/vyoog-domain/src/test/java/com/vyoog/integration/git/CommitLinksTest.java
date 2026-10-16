package com.vyoog.integration.git;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** VYB-0922: the rules for turning the git connection's repository address into commit links. */
class CommitLinksTest {

    @Test
    void VYB0922_AC1_aRepositoryAddressBecomesABaseWithoutATrailingSlashOrDotGitAndWithALowerCaseHost() {
        assertThat(CommitLinks.normalise("https://github.com/evyoog/demo")).contains("https://github.com/evyoog/demo");
        assertThat(CommitLinks.normalise("https://GitHub.com/evyoog/demo.git/")).contains("https://github.com/evyoog/demo");
        assertThat(CommitLinks.normalise("https://git.example.com:8443/team/repo//")).contains("https://git.example.com:8443/team/repo");
        assertThat(CommitLinks.normalise("https://github.com")).contains("https://github.com");
    }

    @Test
    void VYB0922_AC2_anAddressThatIsNotSafeToLinkToIsRefusedWithAReason() {
        assertThat(CommitLinks.problem("http://github.com/evyoog/demo")).hasValueSatisfying(p -> assertThat(p).contains("https://"));
        assertThat(CommitLinks.problem("javascript:alert(1)")).isPresent();
        assertThat(CommitLinks.problem("ftp://example.com/x")).isPresent();
        assertThat(CommitLinks.problem("//github.com/evyoog/demo")).isPresent();
        assertThat(CommitLinks.problem("https://user:pw@github.com/evyoog/demo")).hasValueSatisfying(p -> assertThat(p).contains("password"));
        assertThat(CommitLinks.problem("https://github.com/evyoog/demo?x=1")).hasValueSatisfying(p -> assertThat(p).contains("query"));
        assertThat(CommitLinks.problem("https://github.com/evyoog/demo#frag")).hasValueSatisfying(p -> assertThat(p).contains("fragment"));
        assertThat(CommitLinks.problem("https:///nohost")).isPresent();
        assertThat(CommitLinks.problem("not a url")).isPresent();
        assertThat(CommitLinks.problem("https://github.com/a b")).isPresent();
        assertThat(CommitLinks.problem("https://github.com/" + "x".repeat(250))).isPresent();
        assertThat(CommitLinks.problem("https://github.com/evyoog/demo")).isEmpty();
        assertThat(CommitLinks.normalise("javascript:alert(1)")).isEmpty();
    }

    @Test
    void VYB0922_AC3_aCommitPageIsTheBasePlusCommitAndTheShaInLowerCaseAndASillyShaMakesNoLink() {
        String base = "https://github.com/evyoog/demo";
        assertThat(CommitLinks.urlFor(base, "ABCDEF1234567")).contains(base + "/commit/abcdef1234567");
        assertThat(CommitLinks.urlFor(base, "abcdef1")).contains(base + "/commit/abcdef1");
        assertThat(CommitLinks.urlFor(base, "abcde")).as("too short").isEmpty();
        assertThat(CommitLinks.urlFor(base, "zzzzzzzz")).isEmpty();
        assertThat(CommitLinks.urlFor(base, "abc/../def1234")).as("nothing that could change the path").isEmpty();
        assertThat(CommitLinks.urlFor(base, null)).isEmpty();
    }

    @Test
    void VYB0922_AC4_aCommitIsNamedByItsShortShaAndTheFirstLineOfItsMessage() {
        assertThat(CommitLinks.shortSha("0123456789abcdef")).isEqualTo("0123456");
        assertThat(CommitLinks.shortSha("abc")).isEqualTo("abc");
        assertThat(CommitLinks.subjectOf("Fix the pause button\n\nRequirement: VY-12")).isEqualTo("Fix the pause button");
        assertThat(CommitLinks.subjectOf("\n  Leading blank lines are skipped  \nbody")).isEqualTo("Leading blank lines are skipped");
        assertThat(CommitLinks.subjectOf("x".repeat(150))).hasSize(101).endsWith("…");
        assertThat(CommitLinks.subjectOf(null)).isEmpty();
    }

    @Test
    void VYB0922_AC2_aGitConfigurationWithNoAddressOrNotJsonIsNotAProblemAndABadAddressIs() {
        assertThat(CommitLinks.problemWith(null)).isEmpty();
        assertThat(CommitLinks.problemWith("{}")).isEmpty();
        assertThat(CommitLinks.problemWith("{\"repoUrl\":\"\"}")).isEmpty();
        assertThat(CommitLinks.problemWith("not json")).isEmpty();
        assertThat(CommitLinks.problemWith("{\"repoUrl\":\"http://x.example.com/r\"}")).isPresent();
        assertThat(CommitLinks.problemWith("{\"repoUrl\":\"https://github.com/evyoog/demo\"}")).isEmpty();
    }
}
