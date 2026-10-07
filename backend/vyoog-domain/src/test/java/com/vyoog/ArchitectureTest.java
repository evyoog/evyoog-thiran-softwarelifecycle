package com.vyoog;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.*;

/**
 * Module boundaries are enforced here rather than by the Maven reactor, so that
 * extracting a module later stays cheap without paying for it now.
 */
@AnalyzeClasses(packages = "com.vyoog", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule domainHasNoWebDependency =
        noClasses().that().resideInAPackage("com.vyoog..")
            .and().resideOutsideOfPackage("com.vyoog.api..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("org.springframework.web..", "jakarta.servlet..")
            .because("the domain must not know about HTTP; if it needs an HTTP type, "
                   + "the design is wrong");

    @ArchTest
    static final ArchRule noModuleReachesIntoAnotherInternals =
        noClasses().that().resideInAPackage("com.vyoog.(*)..")
            .should().dependOnClassesThat().resideInAPackage("com.vyoog.(*).internal..")
            .because("cross-module access goes through published api packages");

    @ArchTest
    static final ArchRule onlyTheGatewayTalksHttpToAModelProvider =
        noClasses().that().resideInAPackage("com.vyoog.ai..")
            .and().doNotHaveFullyQualifiedName("com.vyoog.ai.OpenAiGateway")
            .should().dependOnClassesThat().resideInAPackage("java.net.http..")
            .because("VYB-0936: every model call goes through ModelGateway, so timeouts, retries and the circuit "
                   + "breaker are written once; a second copy of the request block is how they drift apart");
}
