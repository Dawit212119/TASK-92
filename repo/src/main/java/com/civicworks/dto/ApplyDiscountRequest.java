package com.civicworks.dto;

import com.civicworks.domain.enums.DiscountType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public class ApplyDiscountRequest {

    @NotNull
    private DiscountType discountType;

    @Positive
    private long valueBasisPointsOrCents;

    /** Optimistic-locking version of the Bill. Required — prevents last-write-wins. */
    @NotNull(message = "entityVersion is required to prevent concurrent modification")
    private Integer entityVersion;

    public DiscountType getDiscountType() { return discountType; }
    public void setDiscountType(DiscountType discountType) { this.discountType = discountType; }

    public long getValueBasisPointsOrCents() { return valueBasisPointsOrCents; }
    public void setValueBasisPointsOrCents(long valueBasisPointsOrCents) { this.valueBasisPointsOrCents = valueBasisPointsOrCents; }

    public Integer getEntityVersion() { return entityVersion; }
    public void setEntityVersion(Integer entityVersion) { this.entityVersion = entityVersion; }
}
