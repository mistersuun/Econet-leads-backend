package com.econet.leads.controller;

import com.econet.leads.dto.PricingDTOs;
import com.econet.leads.service.PricingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/pricing")
@RequiredArgsConstructor
@Tag(name = "Pricing", description = "Price tables and estimates mirroring the public website")
public class PricingController {

    private final PricingService pricingService;

    @GetMapping
    @Operation(summary = "Price tables")
    public ResponseEntity<PricingDTOs.Tables> tables() {
        return ResponseEntity.ok(pricingService.tables());
    }

    @PostMapping("/estimate")
    @Operation(summary = "Itemised estimate", description = "{service, area, propertyType, addOnIds, frequency}; subtotal/taxes/total in CAD, rounded like the website")
    public ResponseEntity<PricingDTOs.Estimate> estimate(@RequestBody PricingDTOs.EstimateInput input) {
        return ResponseEntity.ok(pricingService.estimate(input));
    }
}
