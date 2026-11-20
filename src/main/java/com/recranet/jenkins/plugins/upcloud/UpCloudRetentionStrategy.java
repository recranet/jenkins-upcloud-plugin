package com.recranet.jenkins.plugins.upcloud;

import hudson.model.Computer;
import hudson.slaves.OfflineCause;
import hudson.slaves.RetentionStrategy;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Terminates idle agents when the shutdown policy of their template says so, and agents that are not connected.
 */
public class UpCloudRetentionStrategy extends RetentionStrategy<UpCloudComputer> {

    private static final Logger LOGGER = Logger.getLogger(UpCloudRetentionStrategy.class.getName());

    /**
     * How long an agent whose launch failed or whose connection was lost is kept. Until it is removed its log
     * and server can be inspected, and its template provisions nothing, see {@link UpCloudCloud}.
     */
    static final Duration DISCONNECTED_RETENTION = Duration.ofMinutes(5);

    @Override
    public void start(UpCloudComputer computer) {
        computer.connect(false);
    }

    @Override
    public long check(UpCloudComputer computer) {
        check(computer, System.currentTimeMillis());
        return 1;
    }

    void check(UpCloudComputer computer, long now) {
        UpCloudAgent agent = computer.getNode();
        // An agent stops accepting tasks once its termination has been scheduled below.
        if (agent == null || !computer.isAcceptingTasks()) {
            return;
        }
        if (computer.isDisconnected()) {
            OfflineCause cause = computer.getOfflineCause();
            if (cause != null && now - cause.getTimestamp() > DISCONNECTED_RETENTION.toMillis()) {
                terminate(computer, agent, "it is not connected");
            }
        } else if (computer.getChannel() != null && computer.isIdle()) {
            Instant serverCreated = agent.getServerCreated();
            if (serverCreated == null) {
                throw new IllegalStateException(agent.getNodeName() + " is connected but has no server");
            }
            if (agent.getTemplate()
                    .getShutdownPolicy()
                    .isDue(
                            serverCreated,
                            Instant.ofEpochMilli(computer.getIdleStartMilliseconds()),
                            Instant.ofEpochMilli(now))) {
                terminate(computer, agent, "it is idle");
            }
        }
    }

    /**
     * Checks run under the queue lock, so no task can be assigned between finding the agent idle and closing it
     * for tasks. Deleting the server takes a while and therefore happens outside of the check.
     */
    private static void terminate(UpCloudComputer computer, UpCloudAgent agent, String reason) {
        LOGGER.info(() -> "Terminating " + agent.getNodeName() + " because " + reason);
        computer.setAcceptingTasks(false);
        Computer.threadPoolForRemoting.submit(() -> {
            try {
                agent.terminate();
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Failed to terminate " + agent.getNodeName(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }
}
