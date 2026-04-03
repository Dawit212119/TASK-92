package com.civicworks.service;

import com.civicworks.domain.entity.ContentItem;
import com.civicworks.domain.entity.SearchHistory;
import com.civicworks.domain.entity.User;
import com.civicworks.dto.SaveSearchHistoryRequest;
import com.civicworks.repository.ContentItemRepository;
import com.civicworks.repository.SearchHistoryRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.civicworks.domain.enums.Role;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class SearchService {

    /** Allowed sort columns; validated before being passed to the native query. */
    private static final Set<String> ALLOWED_SORT_COLS = Set.of("title", "date");
    private static final Set<String> ALLOWED_SORT_DIRS = Set.of("ASC", "DESC");

    private final ContentService contentService;
    private final ContentItemRepository contentItemRepository;
    private final SearchHistoryRepository searchHistoryRepository;

    public SearchService(ContentService contentService,
                         ContentItemRepository contentItemRepository,
                         SearchHistoryRepository searchHistoryRepository) {
        this.contentService = contentService;
        this.contentItemRepository = contentItemRepository;
        this.searchHistoryRepository = searchHistoryRepository;
    }

    /** Basic typeahead — delegates to full-text search without extra filters. */
    @Transactional(readOnly = true)
    public List<ContentItem> typeahead(String query, String state, String type, User actor) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        return contentService.search(query, state, type, actor);
    }

    /**
     * Enhanced typeahead with category, price-range, origin, and sort controls.
     * All filter parameters are optional (null = no constraint).
     *
     * @param query    full-text search term (required)
     * @param state    content state filter
     * @param category ContentType filter (maps to the {@code type} column)
     * @param origin   origin label filter (exact match)
     * @param minPrice min price in cents (inclusive)
     * @param maxPrice max price in cents (inclusive)
     * @param sortBy   sort column: "title" or "date" (default "date")
     * @param sortDir  sort direction: "ASC" or "DESC" (default "DESC")
     */
    @Transactional(readOnly = true)
    public List<ContentItem> search(String query, String state, String category,
                                     String origin, Long minPrice, Long maxPrice,
                                     String sortBy, String sortDir, User actor) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String col = ALLOWED_SORT_COLS.contains(sortBy) ? sortBy : "date";
        String dir = ALLOWED_SORT_DIRS.contains(
                sortDir != null ? sortDir.toUpperCase() : "") ? sortDir.toUpperCase() : "DESC";

        UUID orgId = ContentService.resolveOrgId(actor);
        if (orgId != null) {
            return contentItemRepository.fullTextSearchWithFiltersByOrg(
                    orgId, query, state, category, origin, minPrice, maxPrice, col, dir);
        }
        return contentItemRepository.fullTextSearchWithFilters(
                query, state, category, origin, minPrice, maxPrice, col, dir);
    }

    /**
     * Returns recent unique query strings from the caller's search history that
     * start with {@code prefix} (case-insensitive, up to 10 results).
     * Enables client-side typeahead suggestion drop-downs backed by real history.
     */
    @Transactional(readOnly = true)
    public List<String> getSuggestions(String prefix, UUID userId) {
        if (prefix == null || prefix.isBlank()) {
            return List.of();
        }
        return contentItemRepository.findRecentQuerySuggestions(userId, prefix);
    }

    @Transactional
    public SearchHistory saveHistory(SaveSearchHistoryRequest request, User actor) {
        SearchHistory history = new SearchHistory();
        history.setUserId(actor.getId());
        history.setQuery(request.getQuery());
        history.setFilters(request.getFilters());
        return searchHistoryRepository.save(history);
    }

    @Transactional(readOnly = true)
    public List<SearchHistory> getUserHistory(UUID userId) {
        return searchHistoryRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    @Transactional(readOnly = true)
    public Page<SearchHistory> getUserHistoryPage(UUID userId, Pageable pageable) {
        return searchHistoryRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
    }

    @Transactional
    public int cleanupOldHistory() {
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(90);
        return searchHistoryRepository.deleteByCreatedAtBefore(cutoff);
    }
}
