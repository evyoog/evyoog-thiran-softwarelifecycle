package com.vyoog.api.web;

import com.vyoog.search.SearchService;
import java.util.List;
import org.springframework.web.bind.annotation.*;

/** VYB-0766. */
@RestController
@RequestMapping("/api/v1/search")
public class SearchController {

    private final SearchService search;

    public SearchController(SearchService search) {
        this.search = search;
    }

    @GetMapping
    public List<SearchService.Result> search(@RequestParam String q) {
        return search.search(q);
    }
}
