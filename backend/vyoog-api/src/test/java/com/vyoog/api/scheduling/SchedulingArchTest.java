package com.vyoog.api.scheduling;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * VYB-0909 (F33-F35): a trigger that is not in {@link ScheduledJobs} is not behind a
 * {@code SchedulerLock}, and would run on every instance. This fails the build if someone adds one.
 */
@AnalyzeClasses(packages = "com.vyoog", importOptions = ImportOption.DoNotIncludeTests.class)
class SchedulingArchTest {

    @ArchTest
    static final ArchRule everyScheduledTriggerIsInScheduledJobs =
        methods().that().areAnnotatedWith(Scheduled.class)
            .should().beDeclaredInClassesThat().haveFullyQualifiedName(ScheduledJobs.class.getName())
            .because("every scheduled job must run behind SchedulerLock, or it runs on every instance at once");
}
