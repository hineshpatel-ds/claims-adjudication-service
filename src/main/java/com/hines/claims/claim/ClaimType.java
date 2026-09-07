package com.hines.claims.claim;

/**
 * The benefit category a claim is made against.
 *
 * <p>Kept deliberately small. Real insurers carry hundreds of benefit codes;
 * modelling that here would add volume without adding anything to demonstrate.
 *
 * <p>The set is mirrored by a {@code CHECK} constraint on {@code claims.claim_type}
 * (see V1 migration and ADR-0003). Adding a value here therefore requires a
 * migration - the database will reject an unknown value, which is the intended
 * behaviour rather than an inconvenience.
 */
public enum ClaimType {
    MEDICAL,
    DENTAL,
    VISION,
    DISABILITY
}
