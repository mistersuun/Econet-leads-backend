package com.econet.leads.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/** Google Places API calls made on one day (V13), to honour PLACES_DAILY_LIMIT. */
@Entity
@Table(name = "places_usage")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PlacesUsage {

    // quoted: DAY is a reserved word in H2
    @Id
    @Column(name = "`day`")
    private LocalDate day;

    @Column(nullable = false)
    private int calls;
}
