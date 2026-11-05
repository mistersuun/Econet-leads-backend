package com.econet.leads.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Call script per business type and language (V9). businessType null = default for the language.
 */
@Entity
@Table(name = "call_scripts")
@Data
@NoArgsConstructor
public class CallScript {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "business_type", length = 100)
    private String businessType;

    @Column(nullable = false, length = 2)
    private String language;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<ScriptSection> sections = new ArrayList<>();

    @Column(nullable = false)
    private Boolean active = true;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
