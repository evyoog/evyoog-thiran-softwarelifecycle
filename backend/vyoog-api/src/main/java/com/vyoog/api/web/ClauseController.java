package com.vyoog.api.web;

import com.vyoog.identity.AccessRule;

import com.vyoog.api.config.RequiresAccess;

import com.vyoog.clause.Clause;
import com.vyoog.clause.ClauseRepository;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * VYB-0611: control clauses. Nothing ingests these from a real compliance-framework
 * source in this session — they're entered directly, which is an intake gap, not a
 * detection one (see BUILD-REGISTER.md).
 */
@RestController
@RequestMapping("/api/v1/clauses")
public class ClauseController {

    private final ClauseRepository clauses;

    public ClauseController(ClauseRepository clauses) {
        this.clauses = clauses;
    }

    public record ClauseView(String id, String standard, String section, String text) {}
    public record CreateClause(@NotBlank String standard, String section, @NotBlank String text) {}

    private static ClauseView toView(Clause c) {
        return new ClauseView(c.getId().toString(), c.getStandard(), c.getSection(), c.getText());
    }

    @GetMapping
    public List<ClauseView> list() {
        return clauses.findAll().stream().map(ClauseController::toView).toList();
    }

    // VYB-0906: the compliance clause register is administrator-managed.
    @RequiresAccess(value = AccessRule.ADMIN)
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ClauseView create(@RequestBody CreateClause body) {
        return toView(clauses.save(new Clause(body.standard(), body.section(), body.text())));
    }

    @GetMapping("/{id}")
    public ClauseView get(@PathVariable UUID id) {
        return toView(clauses.findById(id).orElseThrow(NoSuchElementException::new));
    }
}
