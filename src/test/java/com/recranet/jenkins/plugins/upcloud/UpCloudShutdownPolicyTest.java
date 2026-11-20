package com.recranet.jenkins.plugins.upcloud;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class UpCloudShutdownPolicyTest {

    private static final Instant CREATED = Instant.parse("2026-11-20T10:17:30Z");

    @Test
    void idleTimeCountsFromWhenTheAgentBecameIdle() {
        UpCloudShutdownPolicy policy = new UpCloudShutdownPolicy.AfterIdleTime(10);
        Instant idleSince = CREATED.plus(Duration.ofHours(3));

        assertFalse(policy.isDue(CREATED, idleSince, idleSince.plus(Duration.ofMinutes(10))));
        assertTrue(policy.isDue(
                CREATED, idleSince, idleSince.plus(Duration.ofMinutes(10)).plusMillis(1)));
    }

    @Test
    void idleTimeMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new UpCloudShutdownPolicy.AfterIdleTime(0));
    }

    @Test
    void billingHoursCountFromWhenTheServerWasCreated() {
        UpCloudShutdownPolicy policy = new UpCloudShutdownPolicy.BeforeBillingHourEnds();

        assertFalse(isDueAfter(policy, Duration.ZERO));
        assertFalse(isDueAfter(policy, Duration.ofMinutes(55).minusMillis(1)));
        assertTrue(isDueAfter(policy, Duration.ofMinutes(55)));
        assertTrue(isDueAfter(policy, Duration.ofMinutes(60).minusMillis(1)));
        // The next hour has started and is paid for: the agent stays until that one is nearly over.
        assertFalse(isDueAfter(policy, Duration.ofMinutes(60)));
        assertFalse(isDueAfter(policy, Duration.ofMinutes(114)));
        assertTrue(isDueAfter(policy, Duration.ofMinutes(115)));
        assertTrue(isDueAfter(policy, Duration.ofHours(30).minusMinutes(1)));
    }

    /** How long the agent has been idle makes no difference to the billing hour policy. */
    private static boolean isDueAfter(UpCloudShutdownPolicy policy, Duration age) {
        Instant now = CREATED.plus(age);
        return policy.isDue(CREATED, now, now);
    }
}
