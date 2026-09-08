package com.hines.claims.common.dto;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * A page of results, in a shape this API controls.
 *
 * <p>Spring Data's {@code Page} serialises to JSON, and it is tempting to return
 * it directly. Spring itself warns against that: the JSON shape is an internal
 * detail, it carries fields nobody wants in an API contract (a whole nested
 * {@code pageable} object, {@code sort} metadata), and it can change between Boot
 * versions - so a framework upgrade silently changes your public contract and
 * breaks clients.
 *
 * <p>Same reasoning as ADR-0006, applied to a framework type rather than an
 * entity: the wire format is a contract, and a contract should not be something
 * a dependency owns.
 *
 * @param content       the results for this page
 * @param page          zero-based page number
 * @param size          requested page size
 * @param totalElements total matching rows across all pages
 * @param totalPages    number of pages at this size
 * @param first         whether this is the first page
 * @param last          whether this is the last page
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last) {

    /**
     * Convert a Spring {@link Page} of entities into a page of DTOs.
     *
     * <p>Takes the mapper so entities are converted inside whatever transaction
     * produced them - never handed upward for the web layer to map, which with
     * {@code open-in-view} disabled (ADR-0004) would fail on any lazy access.
     */
    public static <E, T> PageResponse<T> from(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast());
    }
}
