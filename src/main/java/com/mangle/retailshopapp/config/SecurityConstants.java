package com.mangle.retailshopapp.config;

/**
 * The two role authorities this application grants.
 *
 * <p>These are full Spring Security AUTHORITIES, carrying the {@code ROLE_}
 * prefix, which is why callers use {@code hasAnyAuthority} rather than
 * {@code hasAnyRole} — the latter prepends the prefix itself and would look for
 * {@code ROLE_ROLE_ADMIN}.
 *
 * <p>design-api.md §3.5 calls this class "dead code today" and proposes either
 * using it or deleting it. It is not dead: {@code RetailAppUserService} has been
 * assigning roles from these constants all along. What §3.5 is right about is
 * that {@code SecurityConfig} was not using them, and it now does — so the two
 * places that decide what a role is called agree by construction.
 *
 * <p>⚠ {@code ROLE_USER} IS THE STAFF ROLE (log §15.11). There is no
 * {@code ROLE_STAFF} and one must not be created: measured on the restore
 * 2026-10-12, the only values in {@code rc_user.roles} are
 * {@code ROLE_ADMIN|ROLE_USER} (the owner), {@code ROLE_USER} (working staff)
 * and {@code ROLE_CUSTOMER} (water customers). The generic name is a recorded
 * fragility rather than a defect: nothing grants {@code ROLE_USER} to a
 * customer-facing account today, and introducing {@code ROLE_STAFF} is the fix
 * if anything ever does.
 */
public final class SecurityConstants {

    /** Grants everything, including the counter. Held by the owner. */
    public static final String ADMIN_USER = "ROLE_ADMIN";

    /** The staff role. Grants the counter; grants nothing customer-facing. */
    public static final String NORMAL_USER = "ROLE_USER";

    private SecurityConstants() {
        // Constants only. They are final rather than merely static because a
        // mutable public static authority name is reassignable at runtime by
        // anything on the classpath, and reassigning one of these would change
        // what every authorisation rule in the application means.
    }
}
