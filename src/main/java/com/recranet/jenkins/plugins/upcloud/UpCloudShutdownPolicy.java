package com.recranet.jenkins.plugins.upcloud;

import hudson.Extension;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.util.FormValidation;
import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import jenkins.model.Jenkins;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

/**
 * Decides when an agent that is idle is removed and its server deleted.
 */
public abstract class UpCloudShutdownPolicy extends AbstractDescribableImpl<UpCloudShutdownPolicy>
        implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * @param serverCreated when UpCloud created the agent's server
     * @param idleSince when the agent, which is idle now, last finished a build or connected
     */
    abstract boolean isDue(Instant serverCreated, Instant idleSince, Instant now);

    /** Removes an agent once it has been idle for a fixed time. */
    public static class AfterIdleTime extends UpCloudShutdownPolicy {

        private static final long serialVersionUID = 1L;

        private final int idleMinutes;

        @DataBoundConstructor
        public AfterIdleTime(int idleMinutes) {
            if (idleMinutes < 1) {
                throw new IllegalArgumentException("Idle time must be at least 1 minute");
            }
            this.idleMinutes = idleMinutes;
        }

        public int getIdleMinutes() {
            return idleMinutes;
        }

        @Override
        boolean isDue(Instant serverCreated, Instant idleSince, Instant now) {
            return Duration.between(idleSince, now).compareTo(Duration.ofMinutes(idleMinutes)) > 0;
        }

        @Extension(ordinal = 1)
        @Symbol("idle")
        public static class DescriptorImpl extends Descriptor<UpCloudShutdownPolicy> {

            @Override
            public String getDisplayName() {
                return "After it has been idle for a while";
            }

            @POST
            public FormValidation doCheckIdleMinutes(@QueryParameter String value) {
                Jenkins.get().checkPermission(Jenkins.ADMINISTER);
                return FormValidation.validatePositiveInteger(value);
            }
        }
    }

    /**
     * Keeps an agent for as long as its server is paid for anyway. UpCloud bills a server for every hour it
     * starts, so an idle agent is only removed shortly before its server would start another one.
     */
    public static class BeforeBillingHourEnds extends UpCloudShutdownPolicy {

        private static final long serialVersionUID = 1L;

        /**
         * How long before the end of a billing hour an idle agent is removed. Agents are checked once a minute
         * and deleting a server takes a while; cutting it closer would risk starting the next hour.
         */
        static final Duration MARGIN = Duration.ofMinutes(5);

        @DataBoundConstructor
        public BeforeBillingHourEnds() {}

        @Override
        boolean isDue(Instant serverCreated, Instant idleSince, Instant now) {
            long intoBillingHour = Duration.between(serverCreated, now).toMillis()
                    % Duration.ofHours(1).toMillis();
            return intoBillingHour >= Duration.ofHours(1).minus(MARGIN).toMillis();
        }

        @Extension
        @Symbol("billingHour")
        public static class DescriptorImpl extends Descriptor<UpCloudShutdownPolicy> {

            @Override
            public String getDisplayName() {
                return "Just before the billing hour of its server ends";
            }
        }
    }
}
