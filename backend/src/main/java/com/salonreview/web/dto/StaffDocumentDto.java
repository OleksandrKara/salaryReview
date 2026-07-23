package com.salonreview.web.dto;

import java.time.Instant;
import java.time.LocalDate;

/** Shared by the owner-facing CRUD endpoints ({@code StaffDocumentController}) and the
 * provider/manager self-service read-only view ({@code StaffDocumentSelfController}). */
public record StaffDocumentDto(
        Long id,
        /** "PROVIDER" or "MANAGER". */
        String personType,
        Long personId,
        String personName,
        String documentType,
        String label,
        String fileName,
        LocalDate expirationDate,
        /** "OK" | "EXPIRING_SOON" | "EXPIRED" — see StaffDocumentService#statusFor. */
        String status,
        String createdBy,
        Instant createdAt) {}
