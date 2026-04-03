package com.civicworks.dto;

import jakarta.validation.constraints.NotBlank;

public class SaveSearchHistoryRequest {

    @NotBlank
    private String query;

    private String filters;

    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }

    public String getFilters() { return filters; }
    public void setFilters(String filters) { this.filters = filters; }
}
