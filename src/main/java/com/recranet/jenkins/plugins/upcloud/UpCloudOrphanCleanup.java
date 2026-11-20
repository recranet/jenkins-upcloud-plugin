package com.recranet.jenkins.plugins.upcloud;

import com.recranet.jenkins.plugins.upcloud.client.Server;
import com.recranet.jenkins.plugins.upcloud.client.UpCloudClient;
import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.Node;
import hudson.model.TaskListener;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;

/**
 * Deletes servers that this controller created but that no agent refers to any more. That happens when deleting
 * the server of a terminated agent fails, or when the controller stops between creating a server and recording
 * it on the agent.
 */
@Extension
public class UpCloudOrphanCleanup extends AsyncPeriodicWork {

    private static final Logger LOGGER = Logger.getLogger(UpCloudOrphanCleanup.class.getName());

    private static final Duration PERIOD = Duration.ofMinutes(10);
    private static final Duration TERMINATION_TIMEOUT = Duration.ofMinutes(5);

    /** A server exists for a moment before its agent records it, so young servers are left alone. */
    static final Duration MINIMUM_AGE = Duration.ofMinutes(10);

    public UpCloudOrphanCleanup() {
        super("UpCloud orphaned server cleanup");
    }

    @Override
    public long getRecurrencePeriod() {
        return PERIOD.toMillis();
    }

    @Override
    protected void execute(TaskListener listener) throws InterruptedException {
        Jenkins jenkins = Jenkins.get();
        for (UpCloudCloud cloud : jenkins.clouds.getAll(UpCloudCloud.class)) {
            if (!cloud.isCleanupOrphanedServers()) {
                continue;
            }
            try {
                UpCloudClient client = cloud.client();
                // Listed before reading the agents, so that every listed server old enough to be deleted has
                // had its agent recorded by the time the agents are read.
                List<Server> servers = client.listServers(UpCloudCloud.INSTANCE_LABEL, jenkins.getLegacyInstanceId());
                Set<String> agentServers = agentServers();
                for (Server server : servers) {
                    if (server.created().isBefore(Instant.now().minus(MINIMUM_AGE))
                            && !agentServers.contains(server.uuid())) {
                        LOGGER.warning(() ->
                                "Deleting orphaned UpCloud server " + server.uuid() + " (" + server.title() + ")");
                        client.destroyServer(server.uuid(), Instant.now().plus(TERMINATION_TIMEOUT));
                    }
                }
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Failed to clean up orphaned servers of cloud " + cloud.name, e);
            }
        }
    }

    private static Set<String> agentServers() {
        Set<String> servers = new HashSet<>();
        for (Node node : Jenkins.get().getNodes()) {
            if (node instanceof UpCloudAgent agent && agent.getServerUuid() != null) {
                servers.add(agent.getServerUuid());
            }
        }
        return servers;
    }
}
