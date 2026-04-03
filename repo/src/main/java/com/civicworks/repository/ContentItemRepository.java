package com.civicworks.repository;

import com.civicworks.domain.entity.ContentItem;
import com.civicworks.domain.enums.ContentState;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ContentItemRepository extends JpaRepository<ContentItem, UUID> {

    List<ContentItem> findByState(ContentState state);

    List<ContentItem> findByStateAndScheduledAtBefore(ContentState state, OffsetDateTime time);

    @Query(value = "SELECT * FROM content_items WHERE search_vector @@ plainto_tsquery('english', :query) " +
            "AND (:state IS NULL OR state = :state) AND (:type IS NULL OR type = :type)",
            nativeQuery = true)
    List<ContentItem> fullTextSearch(@Param("query") String query,
                                     @Param("state") String state,
                                     @Param("type") String type);

    /**
     * Full-text search with additional filters and sort controls.
     * All filter params are optional (null means no constraint).
     *
     * @param query       full-text search term
     * @param state       optional state filter (e.g. "PUBLISHED")
     * @param type        optional ContentType filter (e.g. "EVENT")
     * @param origin      optional origin label filter (exact match)
     * @param minPrice    optional min price in cents (inclusive)
     * @param maxPrice    optional max price in cents (inclusive)
     * @param sortByCol   SQL column to sort by (validated by service before use)
     * @param sortDir     "ASC" or "DESC"
     */
    @Query(value = "SELECT * FROM content_items " +
            "WHERE search_vector @@ plainto_tsquery('english', :query) " +
            "AND (:state IS NULL OR state = :state) " +
            "AND (:type IS NULL OR type = :type) " +
            "AND (:origin IS NULL OR origin = :origin) " +
            "AND (:minPrice IS NULL OR price_cents >= :minPrice) " +
            "AND (:maxPrice IS NULL OR price_cents <= :maxPrice) " +
            "ORDER BY " +
            "  CASE WHEN :sortByCol = 'title' AND :sortDir = 'ASC'  THEN title END ASC, " +
            "  CASE WHEN :sortByCol = 'title' AND :sortDir = 'DESC' THEN title END DESC, " +
            "  CASE WHEN :sortByCol = 'date'  AND :sortDir = 'ASC'  THEN created_at END ASC, " +
            "  CASE WHEN :sortByCol = 'date'  AND :sortDir = 'DESC' THEN created_at END DESC, " +
            "  created_at DESC",
            nativeQuery = true)
    List<ContentItem> fullTextSearchWithFilters(
            @Param("query") String query,
            @Param("state") String state,
            @Param("type") String type,
            @Param("origin") String origin,
            @Param("minPrice") Long minPrice,
            @Param("maxPrice") Long maxPrice,
            @Param("sortByCol") String sortByCol,
            @Param("sortDir") String sortDir);

    /**
     * Returns deduplicated queries from search_history for the given user
     * whose query text starts with {@code prefix} (case-insensitive),
     * ordered by most recent usage timestamp descending.
     */
    @Query(value = "SELECT sh.query FROM search_history sh " +
            "WHERE sh.user_id = :userId " +
            "AND lower(sh.query) LIKE lower(concat(:prefix, '%')) " +
            "GROUP BY sh.query " +
            "ORDER BY MAX(sh.created_at) DESC " +
            "LIMIT 10",
            nativeQuery = true)
    List<String> findRecentQuerySuggestions(@Param("userId") UUID userId,
                                             @Param("prefix") String prefix);

    @Query("SELECT c FROM ContentItem c WHERE " +
           "(:type IS NULL OR CAST(c.type AS string) = :type) AND " +
           "(:state IS NULL OR CAST(c.state AS string) = :state)")
    Page<ContentItem> findWithFilters(@Param("type") String type,
                                       @Param("state") String state,
                                       Pageable pageable);

    /** Org-scoped single-item lookup.  Returns empty for cross-org access. */
    @Query("SELECT c FROM ContentItem c WHERE c.id = :id AND c.organization.id = :orgId")
    Optional<ContentItem> findByIdAndOrganizationId(@Param("id") UUID id,
                                                      @Param("orgId") UUID orgId);

    /** Org-scoped paginated list with optional type/state filter. */
    @Query("SELECT c FROM ContentItem c WHERE c.organization.id = :orgId AND " +
           "(:type IS NULL OR CAST(c.type AS string) = :type) AND " +
           "(:state IS NULL OR CAST(c.state AS string) = :state)")
    Page<ContentItem> findWithFiltersAndOrg(@Param("orgId") UUID orgId,
                                             @Param("type") String type,
                                             @Param("state") String state,
                                             Pageable pageable);

    /** Org-scoped full-text search (basic — no sort/price/origin filters). */
    @Query(value = "SELECT * FROM content_items WHERE organization_id = CAST(:orgId AS UUID) " +
            "AND search_vector @@ plainto_tsquery('english', :query) " +
            "AND (:state IS NULL OR state = :state) AND (:type IS NULL OR type = :type)",
            nativeQuery = true)
    List<ContentItem> fullTextSearchByOrg(@Param("orgId") UUID orgId,
                                           @Param("query") String query,
                                           @Param("state") String state,
                                           @Param("type") String type);

    /** Org-scoped full-text search with full filter/sort controls. */
    @Query(value = "SELECT * FROM content_items " +
            "WHERE organization_id = CAST(:orgId AS UUID) " +
            "AND search_vector @@ plainto_tsquery('english', :query) " +
            "AND (:state IS NULL OR state = :state) " +
            "AND (:type IS NULL OR type = :type) " +
            "AND (:origin IS NULL OR origin = :origin) " +
            "AND (:minPrice IS NULL OR price_cents >= :minPrice) " +
            "AND (:maxPrice IS NULL OR price_cents <= :maxPrice) " +
            "ORDER BY " +
            "  CASE WHEN :sortByCol = 'title' AND :sortDir = 'ASC'  THEN title END ASC, " +
            "  CASE WHEN :sortByCol = 'title' AND :sortDir = 'DESC' THEN title END DESC, " +
            "  CASE WHEN :sortByCol = 'date'  AND :sortDir = 'ASC'  THEN created_at END ASC, " +
            "  CASE WHEN :sortByCol = 'date'  AND :sortDir = 'DESC' THEN created_at END DESC, " +
            "  created_at DESC",
            nativeQuery = true)
    List<ContentItem> fullTextSearchWithFiltersByOrg(
            @Param("orgId") UUID orgId,
            @Param("query") String query,
            @Param("state") String state,
            @Param("type") String type,
            @Param("origin") String origin,
            @Param("minPrice") Long minPrice,
            @Param("maxPrice") Long maxPrice,
            @Param("sortByCol") String sortByCol,
            @Param("sortDir") String sortDir);
}
