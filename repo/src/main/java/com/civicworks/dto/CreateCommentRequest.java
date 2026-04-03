package com.civicworks.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

public class CreateCommentRequest {

    @NotBlank
    private String contentText;

    private UUID parentId;

    public String getContentText() { return contentText; }
    public void setContentText(String contentText) { this.contentText = contentText; }

    public UUID getParentId() { return parentId; }
    public void setParentId(UUID parentId) { this.parentId = parentId; }
}
