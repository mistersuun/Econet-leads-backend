package com.econet.leads.sms;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/** One SMS handed to the provider (V12). The recipient is stored masked. */
@Entity
@Table(name = "sms_messages")
@Getter
@Setter
@NoArgsConstructor
public class SmsMessage {

    public static final String ALERT = "alert";
    public static final String FOLLOW_UP = "follow_up";
    public static final String SENT = "sent";
    public static final String FAILED = "failed";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "business_id")
    private UUID businessId;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "to_masked", nullable = false, length = 30)
    private String toMasked;

    @Column(nullable = false, length = 20)
    private String kind;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "provider_sid", length = 64)
    private String providerSid;

    @Column(length = 500)
    private String error;

    @Column(length = 300)
    private String body;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
