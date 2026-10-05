package com.econet.leads.dto;

import java.util.List;

/** Response of GET /api/businesses/filters */
public record BusinessFilterOptionsDTO(List<String> businessTypes, List<String> cities, List<String> dataSources) {
}
