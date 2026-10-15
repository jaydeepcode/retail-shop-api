package com.mangle.retailshopapp.recharge.model.response;

import java.util.List;

import org.springframework.data.domain.Page;

/**
 * A page of anything (design-api.md §6.1).
 *
 * <p>A deliberate deviation from {@code PurchasePartyController:140-145}, which
 * returns Spring's {@code Page<CustomerTripLedger>} — both an entity leak and an
 * unstable JSON shape, since {@code Page}'s serialised form is a Spring internal
 * that has changed across versions. Five fields the client can rely on.
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    public static <T> PageResponse<T> of(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }

    public static <T> PageResponse<T> of(List<T> content, int page, int size, long total) {
        int pages = size <= 0 ? 0 : (int) Math.ceil((double) total / size);
        return new PageResponse<>(content, page, size, total, pages);
    }
}
