package com.salonreview.domain;

/**
 * Sync state of a KB article relative to the RAG store.
 *
 * <ul>
 *   <li>{@code NOT_SYNCED} — never synced.</li>
 *   <li>{@code SYNCED} — body matches what's in the RAG store (hash unchanged since last sync).</li>
 *   <li>{@code CHANGED} — body was edited since the last successful sync.</li>
 *   <li>{@code ERROR} — the last sync failed (see {@code last_sync_error}, e.g. PII quarantine).</li>
 * </ul>
 */
public enum SyncStatus {
    NOT_SYNCED, SYNCED, CHANGED, ERROR
}
