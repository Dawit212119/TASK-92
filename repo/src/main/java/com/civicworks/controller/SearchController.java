package com.civicworks.controller;

import com.civicworks.config.AuthUtils;
import com.civicworks.domain.entity.ContentItem;
import com.civicworks.domain.entity.SearchHistory;
import com.civicworks.domain.entity.User;
import com.civicworks.dto.SaveSearchHistoryRequest;
import com.civicworks.service.SearchService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/search")
public class SearchController {

    private final SearchService searchService;
    private final AuthUtils authUtils;

    public SearchController(SearchService searchService, AuthUtils authUtils) {
        this.searchService = searchService;
        this.authUtils = authUtils;
    }

    /**
     * Basic full-text typeahead (backward-compatible).
     */
    @GetMapping("/typeahead")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<ContentItem>> typeahead(
            @RequestParam String q,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String type,
            Authentication authentication) {
        User actor = authUtils.resolveUser(authentication);
        return ResponseEntity.ok(searchService.typeahead(q, state, type, actor));
    }

    /**
     * Enhanced typeahead with category, price-range, origin, and sort controls.
     *
     * <ul>
     *   <li>{@code category} — ContentType filter (NEWS, POLICY, EVENT, CLASS)</li>
     *   <li>{@code origin}   — origin label exact-match filter</li>
     *   <li>{@code minPrice}/{@code maxPrice} — price-range filter in cents</li>
     *   <li>{@code sortBy}   — "title" or "date" (default "date")</li>
     *   <li>{@code sortDir}  — "ASC" or "DESC" (default "DESC")</li>
     * </ul>
     */
    @GetMapping("/typeahead/filter")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<ContentItem>> typeaheadWithFilters(
            @RequestParam String q,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String origin,
            @RequestParam(required = false) Long minPrice,
            @RequestParam(required = false) Long maxPrice,
            @RequestParam(required = false, defaultValue = "date") String sortBy,
            @RequestParam(required = false, defaultValue = "DESC") String sortDir,
            Authentication authentication) {
        User actor = authUtils.resolveUser(authentication);
        return ResponseEntity.ok(
                searchService.search(q, state, category, origin, minPrice, maxPrice, sortBy, sortDir, actor));
    }

    /**
     * Returns recent unique query strings from the caller's history that start
     * with the given prefix — powers client typeahead suggestion lists.
     */
    @GetMapping("/typeahead/suggestions")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<String>> suggestions(
            @RequestParam String q,
            Authentication authentication) {
        User actor = authUtils.resolveUser(authentication);
        return ResponseEntity.ok(searchService.getSuggestions(q, actor.getId()));
    }

    @PostMapping("/history")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<SearchHistory> saveHistory(
            @Valid @RequestBody SaveSearchHistoryRequest request,
            Authentication authentication) {
        User actor = authUtils.resolveUser(authentication);
        return ResponseEntity.status(HttpStatus.CREATED).body(searchService.saveHistory(request, actor));
    }

    @GetMapping("/history")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Map<String, Object>> getUserHistory(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            Authentication authentication) {
        User actor = authUtils.resolveUser(authentication);
        org.springframework.data.domain.Page<SearchHistory> result =
                searchService.getUserHistoryPage(actor.getId(),
                        org.springframework.data.domain.PageRequest.of(
                                Math.max(0, page), Math.min(100, size)));
        return ResponseEntity.ok(Map.of(
                "data", result.getContent(),
                "page", result.getNumber(),
                "size", result.getSize(),
                "total", result.getTotalElements()
        ));
    }

    @GetMapping("/history/retention")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasRole('MODERATOR')")
    public ResponseEntity<Map<String, Object>> retentionInfo() {
        return ResponseEntity.ok(Map.of(
                "retentionDays", 90,
                "info", "Search history older than 90 days is deleted daily."));
    }
}
