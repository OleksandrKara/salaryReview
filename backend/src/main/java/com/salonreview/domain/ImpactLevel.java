package com.salonreview.domain;

/**
 * How much a recommended funnel improvement is expected to move the needle, per the AI funnel
 * analysis feature. Persisted as a string so the column stays human-readable in adminer and
 * survives enum reorderings.
 */
public enum ImpactLevel {
    HIGH,
    MEDIUM,
    LOW,
}
