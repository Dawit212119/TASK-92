package com.civicworks.dto;

import com.civicworks.domain.enums.CalculationType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public class CreateFeeItemRequest {

    @NotBlank
    private String code;

    @NotNull
    private CalculationType calculationType;

    @Positive
    private long rateCents;

    private boolean taxableFlag = false;

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public CalculationType getCalculationType() { return calculationType; }
    public void setCalculationType(CalculationType calculationType) { this.calculationType = calculationType; }

    public long getRateCents() { return rateCents; }
    public void setRateCents(long rateCents) { this.rateCents = rateCents; }

    public boolean isTaxableFlag() { return taxableFlag; }
    public void setTaxableFlag(boolean taxableFlag) { this.taxableFlag = taxableFlag; }
}
