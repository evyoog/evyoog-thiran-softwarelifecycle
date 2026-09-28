package com.vyoog.api.web;

import com.vyoog.requirements.AcceptanceCriterionService;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** Criterion ids are globally unique, so removal doesn't need the parent requirement's id. */
@RestController
@RequestMapping("/api/v1/acceptance-criteria")
public class AcceptanceCriterionController {

    private final AcceptanceCriterionService acceptanceCriteria;

    public AcceptanceCriterionController(AcceptanceCriterionService acceptanceCriteria) {
        this.acceptanceCriteria = acceptanceCriteria;
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable UUID id) {
        acceptanceCriteria.remove(id);
    }
}
