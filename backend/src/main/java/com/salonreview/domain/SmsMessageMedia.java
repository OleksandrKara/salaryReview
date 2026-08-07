package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * One MMS photo attached to an {@link SmsMessage} (inbound or outbound) — see V69. Stored as
 * {@code bytea} directly, same convention as {@link StaffDocument#getFileData()}, no S3/disk
 * storage. {@link #accessToken} is an opaque public identifier (same shape as
 * {@link SmsMessage#getClickToken()}) letting both the dashboard's {@code <img>} tags and Twilio's
 * own outbound-media-fetch requests retrieve the file with no session/auth header — see
 * {@code SmsMediaController}.
 */
@Entity
@Table(name = "sms_message_media")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class SmsMessageMedia {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sms_message_id", nullable = false)
    private Long smsMessageId;

    @Column(name = "content_type", nullable = false)
    private String contentType;

    @Column(name = "file_data", nullable = false)
    private byte[] fileData;

    @Column(name = "access_token", nullable = false)
    private String accessToken;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
