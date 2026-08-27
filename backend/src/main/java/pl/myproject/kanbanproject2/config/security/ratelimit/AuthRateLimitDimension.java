package pl.myproject.kanbanproject2.config.security.ratelimit;

/**
 * What a bucket is keyed on. Both dimensions are checked because neither is sufficient alone:
 * a per-IP limit does nothing against a botnet aimed at one account, and a per-account limit does
 * nothing against one host spraying a password across many accounts.
 */
public enum AuthRateLimitDimension {

    /** The caller, as resolved by {@link ClientIpResolver}. */
    IP,

    /** The email address the request targets, which is the account under attack. */
    ACCOUNT
}
