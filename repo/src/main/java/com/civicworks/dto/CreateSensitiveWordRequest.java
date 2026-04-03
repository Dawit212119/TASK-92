package com.civicworks.dto;

import jakarta.validation.constraints.NotBlank;

public class CreateSensitiveWordRequest {

    @NotBlank
    private String word;

    private String replacement;

    public String getWord() { return word; }
    public void setWord(String word) { this.word = word; }

    public String getReplacement() { return replacement; }
    public void setReplacement(String replacement) { this.replacement = replacement; }
}
