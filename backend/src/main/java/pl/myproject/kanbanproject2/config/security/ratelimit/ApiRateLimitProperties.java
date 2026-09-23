package pl.myproject.kanbanproject2.config.security.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The per-account limit on the authenticated API - see {@link ApiRateLimiter}. Its own prefix rather
 * than more fields on {@link AuthRateLimitProperties}: that limiter is an escalating cooldown for
 * guessing attacks on a handful of unauthenticated routes, this one is a plain token bucket for
 * everything a signed-in account can call, and the two are tuned for different reasons.
 */
@ConfigurationProperties(prefix = "security.api-rate-limit")
public record ApiRateLimitProperties(

        /* Turns the limit off entirely, without a rollback, if it ever misfires. */
        @DefaultValue("true") boolean enabled,

        /*
         * The sustained rate one account may call the API at, fleet-wide. Twenty a second is far
         * past anything a person does; the spam run that motivated this reached 214 a second from
         * one account and pinned four cores doing it.
         */
        @DefaultValue("20") int perSecond,

        /*
         * How many calls may arrive at once before the rate applies. A board load is five to ten
         * requests and every live-sync frame is another re-read, so a hundred absorbs a busy board
         * with room to spare while a script is refused after its first hundred.
         */
        @DefaultValue("100") int burst) {
}
