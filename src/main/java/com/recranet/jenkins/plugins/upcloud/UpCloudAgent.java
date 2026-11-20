package com.recranet.jenkins.plugins.upcloud;

import com.recranet.jenkins.plugins.upcloud.client.Server;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Extension;
import hudson.model.Descriptor;
import hudson.model.TaskListener;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.AbstractCloudSlave;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.NodeProperty;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import jenkins.model.Jenkins;

public class UpCloudAgent extends AbstractCloudSlave {

    private static final long serialVersionUID = 1L;

    private static final Duration TERMINATION_TIMEOUT = Duration.ofMinutes(5);

    private final String cloudName;
    private final UpCloudAgentTemplate template;

    /** Null until {@link UpCloudLauncher} has created the server. */
    @CheckForNull
    private String serverUuid;

    /**
     * Set together with {@link #serverUuid}. Billing hours count from it, see {@link UpCloudShutdownPolicy}. A
     * {@link Date} because Jenkins does not persist {@link Instant}.
     */
    @CheckForNull
    private Date serverCreated;

    UpCloudAgent(String cloudName, UpCloudAgentTemplate template) throws Descriptor.FormException, IOException {
        this(cloudName, template, new UpCloudLauncher());
    }

    UpCloudAgent(String cloudName, UpCloudAgentTemplate template, ComputerLauncher launcher)
            throws Descriptor.FormException, IOException {
        super(
                template.getName() + "-" + UUID.randomUUID().toString().substring(0, 8),
                template.getRemoteFs(),
                launcher);
        this.cloudName = cloudName;
        this.template = template;
        setNumExecutors(template.getNumExecutors());
        setMode(template.getMode());
        setLabelString(template.getLabelString());
        // A node property belongs to one node, so every agent gets copies of those of its template.
        List<NodeProperty<?>> nodeProperties = new ArrayList<>();
        for (NodeProperty<?> property : template.getNodeProperties()) {
            nodeProperties.add((NodeProperty<?>) Jenkins.XSTREAM2.fromXML(Jenkins.XSTREAM2.toXML(property)));
        }
        setNodeProperties(nodeProperties);
        setRetentionStrategy(new UpCloudRetentionStrategy());
    }

    public String getCloudName() {
        return cloudName;
    }

    /** The template as it was when this agent was created. */
    public UpCloudAgentTemplate getTemplate() {
        return template;
    }

    @CheckForNull
    public String getServerUuid() {
        return serverUuid;
    }

    @CheckForNull
    public Instant getServerCreated() {
        return serverCreated == null ? null : serverCreated.toInstant();
    }

    void setServer(Server server) throws IOException {
        serverUuid = server.uuid();
        serverCreated = Date.from(server.created());
        save();
    }

    UpCloudCloud getCloud() throws IOException {
        if (Jenkins.get().getCloud(cloudName) instanceof UpCloudCloud cloud) {
            return cloud;
        }
        throw new IOException("UpCloud cloud '" + cloudName + "' no longer exists");
    }

    boolean isDisconnected() {
        return toComputer() instanceof UpCloudComputer computer && computer.isDisconnected();
    }

    @Override
    public AbstractCloudComputer<UpCloudAgent> createComputer() {
        return new UpCloudComputer(this);
    }

    @Override
    protected void _terminate(TaskListener listener) throws IOException, InterruptedException {
        if (serverUuid != null) {
            listener.getLogger().println("Deleting UpCloud server " + serverUuid);
            getCloud().client().destroyServer(serverUuid, Instant.now().plus(TERMINATION_TIMEOUT));
        }
    }

    @Extension
    public static class DescriptorImpl extends SlaveDescriptor {

        @Override
        public String getDisplayName() {
            return "UpCloud agent";
        }

        @Override
        public boolean isInstantiable() {
            return false;
        }
    }
}
