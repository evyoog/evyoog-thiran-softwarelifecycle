package com.vyoog.api.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.vyoog.api.config.RequiresAccess;
import com.vyoog.identity.AccessRule;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * VYB-0906 (F02): no write endpoint may exist without a stated access rule.
 *
 * <p>Every POST/PUT/PATCH/DELETE handler in {@code com.vyoog.api.web} must be one of:
 * <ol>
 *   <li>annotated {@link RequiresAccess} (enforced by the interceptor; exercised by {@link AccessRulesTest});</li>
 *   <li>guarded in its own code (its source calls {@code guard.*} or a guard helper), which this test checks by reading the source;</li>
 *   <li>{@link #OPEN_BY_DESIGN}: it authenticates itself some other way; or</li>
 *   <li>{@link #PENDING}: not done yet, listed so the list can only shrink.</li>
 * </ol>
 * A new write endpoint that is none of these fails the build. A stale entry in the two lists (the
 * endpoint is now annotated or guarded, or no longer exists) also fails it, so each session of
 * VYB-0906 deletes the entries it completes. VYB-0906 is finished when {@code PENDING} is empty.
 */
class AccessPolicyTest {

    /** Authenticated by something other than a user token. Never role-guarded. */
    static final Map<String, String> OPEN_BY_DESIGN = Map.of(
        "AuthController#login", "this is how a token is obtained",
        "AuthController#refresh", "refresh-token cookie",
        "AuthController#logout", "refresh-token cookie",
        "WebhookController#receive", "HMAC signature of the sending system",
        "InternalSsoController#token", "shared-secret header between backends",
        "InternalSsoController#logout", "shared-secret header between backends");

    /** VYB-0906 session 6b (portfolio structure, glossary, clauses, documents, variants, import): done; the list is empty. */
    static final Set<String> PENDING_6B = Set.of();

    /** VYB-0906 session 6c: design, releases, quality, delivery, teams, personal state, misc. */
    static final Set<String> PENDING_6C = Set.of(
        "DesignController#createFlow", "DesignController#deleteFlow", "DesignController#generate",
        "DesignController#addNode", "DesignController#addEdge", "DesignController#deleteNode",
        "DesignController#link", "DesignController#unlink",
        "ReleaseController#create", "ReleaseController#setTargetDate", "ReleaseController#commit",
        "ReleaseController#remove",
        "DefectController#raise", "DefectController#classify", "DefectController#close",
        "TestCaseController#draft", "TestCaseController#update",
        "BriefController#generate", "EnvironmentController#create",
        "TeamController#create", "TeamController#addMember",
        "TaskController#complete", "TaskController#reopen",
        "NotificationController#markRead",
        "SavedViewController#save", "SavedViewController#delete",
        "LintController#lint", "AiController#reembedStale");

    /**
     * The rule each annotated endpoint is meant to have, written out independently of the annotations,
     * so changing an endpoint's rule means changing this table too and the change is reviewed on its own.
     * Scope is the {@link RequiresAccess.Scope}: where the role is checked.
     */
    record Expected(AccessRule rule, RequiresAccess.Scope scope) {}

    static final Map<String, Expected> EXPECTED = Map.ofEntries(
        // requirements: "Create req" / "Edit req" = Business Analyst, Architect
        Map.entry("RequirementController#create", new Expected(AccessRule.CREATE_EDIT_REQ, RequiresAccess.Scope.ANYWHERE)),
        Map.entry("RequirementController#addCriterion", new Expected(AccessRule.CREATE_EDIT_REQ, RequiresAccess.Scope.REQUIREMENT)),
        Map.entry("RequirementController#reorderCriteria", new Expected(AccessRule.CREATE_EDIT_REQ, RequiresAccess.Scope.REQUIREMENT)),
        Map.entry("RequirementController#editCriterion", new Expected(AccessRule.CREATE_EDIT_REQ, RequiresAccess.Scope.CRITERION)),
        Map.entry("AcceptanceCriterionController#remove", new Expected(AccessRule.CREATE_EDIT_REQ, RequiresAccess.Scope.CRITERION)),
        Map.entry("BulkEditController#apply", new Expected(AccessRule.CREATE_EDIT_REQ, RequiresAccess.Scope.ANYWHERE)),
        Map.entry("BulkEditController#undo", new Expected(AccessRule.CREATE_EDIT_REQ, RequiresAccess.Scope.ANYWHERE)),
        Map.entry("ClarificationController#answer", new Expected(AccessRule.CREATE_EDIT_REQ, RequiresAccess.Scope.ANYWHERE)),
        Map.entry("ChangeRequestController#raise", new Expected(AccessRule.CREATE_EDIT_REQ, RequiresAccess.Scope.ANYWHERE)),
        Map.entry("TraceLinkController#createLink", new Expected(AccessRule.CREATE_EDIT_REQ, RequiresAccess.Scope.ANYWHERE)),
        Map.entry("TraceLinkController#deleteLink", new Expected(AccessRule.CREATE_EDIT_REQ, RequiresAccess.Scope.ANYWHERE)),
        // 6b: portfolio structure and clauses are administrator-only (nearest matrix column: Admin)
        Map.entry("ProductController#create", new Expected(AccessRule.ADMIN, RequiresAccess.Scope.NONE)),
        Map.entry("ProductController#update", new Expected(AccessRule.ADMIN, RequiresAccess.Scope.NONE)),
        Map.entry("ApplicationController#create", new Expected(AccessRule.ADMIN, RequiresAccess.Scope.NONE)),
        Map.entry("ApplicationController#update", new Expected(AccessRule.ADMIN, RequiresAccess.Scope.NONE)),
        Map.entry("CapabilityController#create", new Expected(AccessRule.ADMIN, RequiresAccess.Scope.NONE)),
        Map.entry("CapabilityController#update", new Expected(AccessRule.ADMIN, RequiresAccess.Scope.NONE)),
        Map.entry("ClauseController#create", new Expected(AccessRule.ADMIN, RequiresAccess.Scope.NONE)),
        // 6b: authored content = Business Analyst, Architect
        Map.entry("GlossaryController#create", new Expected(AccessRule.CREATE_EDIT_REQ, RequiresAccess.Scope.ANYWHERE)),
        Map.entry("GlossaryController#recordUsage", new Expected(AccessRule.CREATE_EDIT_REQ, RequiresAccess.Scope.ANYWHERE)),
        Map.entry("DocumentController#create", new Expected(AccessRule.CREATE_EDIT_REQ, RequiresAccess.Scope.ANYWHERE)),
        Map.entry("DocumentController#addRequirement", new Expected(AccessRule.CREATE_EDIT_REQ, RequiresAccess.Scope.ANYWHERE)),
        Map.entry("DocumentController#removeRequirement", new Expected(AccessRule.CREATE_EDIT_REQ, RequiresAccess.Scope.ANYWHERE)),
        Map.entry("DocumentController#reorder", new Expected(AccessRule.CREATE_EDIT_REQ, RequiresAccess.Scope.ANYWHERE)),
        Map.entry("VariantController#create", new Expected(AccessRule.CREATE_EDIT_REQ, RequiresAccess.Scope.ANYWHERE)),
        Map.entry("VariantController#markApplies", new Expected(AccessRule.CREATE_EDIT_REQ, RequiresAccess.Scope.ANYWHERE)),
        Map.entry("VariantController#clear", new Expected(AccessRule.CREATE_EDIT_REQ, RequiresAccess.Scope.ANYWHERE)),
        Map.entry("ImportController#upload", new Expected(AccessRule.CREATE_EDIT_REQ, RequiresAccess.Scope.ANYWHERE)),
        Map.entry("ImportController#extract", new Expected(AccessRule.CREATE_EDIT_REQ, RequiresAccess.Scope.BATCH)),
        Map.entry("ImportController#analyse", new Expected(AccessRule.CREATE_EDIT_REQ, RequiresAccess.Scope.BATCH)),
        Map.entry("ImportController#confirmBatchPlacement", new Expected(AccessRule.CREATE_EDIT_REQ, RequiresAccess.Scope.BATCH)),
        Map.entry("ImportController#lint", new Expected(AccessRule.CREATE_EDIT_REQ, RequiresAccess.Scope.CANDIDATE)),
        Map.entry("ImportController#proposeCapability", new Expected(AccessRule.CREATE_EDIT_REQ, RequiresAccess.Scope.CANDIDATE)),
        Map.entry("ImportController#confirmType", new Expected(AccessRule.CREATE_EDIT_REQ, RequiresAccess.Scope.CANDIDATE)),
        Map.entry("ImportController#confirmAcceptanceCriteria", new Expected(AccessRule.CREATE_EDIT_REQ, RequiresAccess.Scope.CANDIDATE)),
        Map.entry("ImportController#proposeTraceLinks", new Expected(AccessRule.CREATE_EDIT_REQ, RequiresAccess.Scope.CANDIDATE)),
        Map.entry("ImportController#confirmTraceLinks", new Expected(AccessRule.CREATE_EDIT_REQ, RequiresAccess.Scope.CANDIDATE)),
        Map.entry("ImportController#edit", new Expected(AccessRule.CREATE_EDIT_REQ, RequiresAccess.Scope.CANDIDATE)),
        Map.entry("ImportController#confirmPlacement", new Expected(AccessRule.CREATE_EDIT_REQ, RequiresAccess.Scope.CANDIDATE)),
        Map.entry("ImportController#confirmCapability", new Expected(AccessRule.CREATE_EDIT_REQ, RequiresAccess.Scope.CANDIDATE)),
        Map.entry("ImportController#select", new Expected(AccessRule.CREATE_EDIT_REQ, RequiresAccess.Scope.CANDIDATE)),
        Map.entry("ImportController#setImportReason", new Expected(AccessRule.CREATE_EDIT_REQ, RequiresAccess.Scope.CANDIDATE)),
        Map.entry("ImportController#acceptAnalysis", new Expected(AccessRule.CREATE_EDIT_REQ, RequiresAccess.Scope.ANALYSIS)),
        Map.entry("ImportController#dismissAnalysis", new Expected(AccessRule.CREATE_EDIT_REQ, RequiresAccess.Scope.ANALYSIS)),
        Map.entry("VariantController#matrix", new Expected(AccessRule.PERSON, RequiresAccess.Scope.NONE)),
        // review: Reviewer, Approver, Compliance Lead, Architect
        Map.entry("ReviewController#open", new Expected(AccessRule.REVIEW, RequiresAccess.Scope.ANYWHERE)),
        Map.entry("ReviewController#comment", new Expected(AccessRule.REVIEW, RequiresAccess.Scope.ANYWHERE)),
        Map.entry("FindingController#dismiss", new Expected(AccessRule.REVIEW, RequiresAccess.Scope.ANYWHERE)),
        Map.entry("FindingController#accept", new Expected(AccessRule.REVIEW, RequiresAccess.Scope.ANYWHERE)),
        Map.entry("FindingController#reopen", new Expected(AccessRule.REVIEW, RequiresAccess.Scope.ANYWHERE)),
        Map.entry("TraceLinkController#reviewLink", new Expected(AccessRule.REVIEW, RequiresAccess.Scope.ANYWHERE)),
        // any signed-in person: own state, a question or comment, advice that stores nothing, AI proposals
        Map.entry("RequirementController#transition", new Expected(AccessRule.PERSON, RequiresAccess.Scope.NONE)),
        Map.entry("RequirementController#authoringSignals", new Expected(AccessRule.PERSON, RequiresAccess.Scope.NONE)),
        Map.entry("RequirementController#rewriteSuggestion", new Expected(AccessRule.PERSON, RequiresAccess.Scope.NONE)),
        Map.entry("RequirementController#testCaseSuggestions", new Expected(AccessRule.PERSON, RequiresAccess.Scope.NONE)),
        Map.entry("RequirementController#testCaseSuggestionsBulk", new Expected(AccessRule.PERSON, RequiresAccess.Scope.NONE)),
        Map.entry("RequirementController#dependencyCluster", new Expected(AccessRule.PERSON, RequiresAccess.Scope.NONE)),
        Map.entry("CommentController#add", new Expected(AccessRule.PERSON, RequiresAccess.Scope.NONE)),
        Map.entry("ClarificationController#raise", new Expected(AccessRule.PERSON, RequiresAccess.Scope.NONE)),
        Map.entry("ChangeRequestController#impact", new Expected(AccessRule.PERSON, RequiresAccess.Scope.NONE)));

    static final Set<String> PENDING = new TreeSet<>();
    static {
        PENDING.addAll(PENDING_6B);
        PENDING.addAll(PENDING_6C);
    }

    private static final Pattern GUARD_CALL =
        Pattern.compile("guard\\.|requireEditRole|requireCreateRoleOnBatch|requireAdminOrTeamLead");

    record Write(String key, Class<?> controller, Method method) {}

    static List<Write> writeEndpoints() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<Write> out = new ArrayList<>();
        for (BeanDefinition bd : scanner.findCandidateComponents("com.vyoog.api.web")) {
            Class<?> c;
            try {
                c = Class.forName(bd.getBeanClassName());
            } catch (ClassNotFoundException e) {
                throw new AssertionError(e);
            }
            for (Method m : c.getDeclaredMethods()) {
                if (m.isAnnotationPresent(PostMapping.class) || m.isAnnotationPresent(PutMapping.class)
                    || m.isAnnotationPresent(PatchMapping.class) || m.isAnnotationPresent(DeleteMapping.class)) {
                    out.add(new Write(c.getSimpleName() + "#" + m.getName(), c, m));
                }
            }
        }
        out.sort((a, b) -> a.key().compareTo(b.key()));
        return out;
    }

    /** The source text of one handler's body, read from src/main (tests run with the module as working directory). */
    static String bodyOf(Write w) {
        Path src = Path.of("src/main/java", w.controller().getName().replace('.', '/') + ".java");
        String text;
        try {
            text = Files.readString(src);
        } catch (IOException e) {
            throw new AssertionError("Cannot read " + src.toAbsolutePath(), e);
        }
        Matcher m = Pattern.compile("public [^=;{]*\\b" + w.method().getName() + "\\s*\\(").matcher(text);
        assertThat(m.find()).as("handler %s in source", w.key()).isTrue();
        int start = m.end();
        int end = text.indexOf("\n    }\n", start);
        return text.substring(start, end < 0 ? text.length() : end);
    }

    @Test
    void VYB0906_AC1_everyWriteEndpointHasAStatedAccessRule() {
        Map<String, List<String>> unclassified = new LinkedHashMap<>();
        List<String> annotated = new ArrayList<>(), inCode = new ArrayList<>();
        List<Write> writes = writeEndpoints();
        assertThat(writes).as("write endpoints found").hasSizeGreaterThan(100);
        for (Write w : writes) {
            String k = w.key();
            if (w.method().isAnnotationPresent(RequiresAccess.class)) {
                annotated.add(k);
            } else if (GUARD_CALL.matcher(bodyOf(w)).find()) {
                inCode.add(k);
            } else if (!OPEN_BY_DESIGN.containsKey(k) && !PENDING.contains(k)) {
                unclassified.computeIfAbsent("no rule", x -> new ArrayList<>()).add(k);
            }
        }
        assertThat(unclassified).as("write endpoints with no access rule: annotate with @RequiresAccess, "
            + "call the guard, or (if it authenticates itself) add it to OPEN_BY_DESIGN with a reason").isEmpty();
        assertThat(annotated).as("annotated endpoints").hasSizeGreaterThanOrEqualTo(60);
        assertThat(inCode).as("endpoints guarded in their own code").isNotEmpty();
    }

    @Test
    void VYB0906_AC1_theOpenAndPendingListsOnlyNameEndpointsThatStillNeedThem() {
        Map<String, Write> byKey = new LinkedHashMap<>();
        writeEndpoints().forEach(w -> byKey.put(w.key(), w));
        List<String> stale = new ArrayList<>();
        for (String k : new TreeSet<>(OPEN_BY_DESIGN.keySet())) {
            if (!byKey.containsKey(k)) stale.add(k + " (open: no such endpoint)");
        }
        for (String k : PENDING) {
            Write w = byKey.get(k);
            if (w == null) stale.add(k + " (pending: no such endpoint)");
            else if (w.method().isAnnotationPresent(RequiresAccess.class)) stale.add(k + " (pending: now annotated, remove it)");
            else if (GUARD_CALL.matcher(bodyOf(w)).find()) stale.add(k + " (pending: now guarded, remove it)");
        }
        assertThat(stale).as("stale entries").isEmpty();
    }

    @Test
    void VYB0906_AC2_everyAnnotatedEndpointHasExactlyTheRuleTheTableSays() {
        Map<String, Expected> actual = new LinkedHashMap<>();
        for (Write w : writeEndpoints()) {
            RequiresAccess a = w.method().getAnnotation(RequiresAccess.class);
            if (a != null) actual.put(w.key(), new Expected(a.value(), a.scope()));
        }
        assertThat(actual).as("annotated endpoints and their rules: change EXPECTED together with the annotation")
            .containsExactlyInAnyOrderEntriesOf(EXPECTED);
    }

    @Test
    void VYB0906_AC2_noEndpointIsBothOpenAndPending() {
        assertThat(PENDING).doesNotContainAnyElementsOf(OPEN_BY_DESIGN.keySet());
    }

    @Test
    void VYB0906_AC2_pendingWorkIsTrackedByCount() {
        // A shrinking number, kept visible: sessions 6a and 6b are done; 6c is what remains.
        assertThat(PENDING_6B).isEmpty();
        assertThat(PENDING_6C).hasSize(28);
    }
}
