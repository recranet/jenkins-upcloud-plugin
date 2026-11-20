package com.recranet.jenkins.plugins.upcloud;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.recranet.jenkins.plugins.upcloud.client.UpCloudClient;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Extension;
import hudson.Util;
import hudson.model.Descriptor;
import hudson.model.Node;
import hudson.security.ACL;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner.PlannedNode;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import jenkins.util.SystemProperties;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.HttpResponses;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.interceptor.RequirePOST;
import org.kohsuke.stapler.verb.POST;

/**
 * Provisions UpCloud servers as agents.
 *
 * <p>An agent is added to Jenkins before its server exists: {@link UpCloudLauncher} creates the server while
 * launching. The nodes in Jenkins are therefore the complete record of what this cloud has provisioned or is
 * provisioning, and the instance caps count nodes.
 */
public class UpCloudCloud extends Cloud {

    private static final Logger LOGGER = Logger.getLogger(UpCloudCloud.class.getName());

    /**
     * Label put on every server, holding the ID of the controller that created it. {@link UpCloudOrphanCleanup}
     * deletes servers by it, so an UpCloud account can be shared between controllers and with other workloads.
     */
    static final String INSTANCE_LABEL = "jenkins-instance";

    /** Not final so that tests can point it at a stand-in. */
    static String apiUrl =
            SystemProperties.getString(UpCloudCloud.class.getName() + ".apiUrl", "https://api.upcloud.com/1.3");

    private final String credentialsId;
    private int instanceCap;
    private boolean noDelayProvisioning = true;
    private boolean cleanupOrphanedServers = true;
    private List<UpCloudAgentTemplate> templates = new ArrayList<>();

    @DataBoundConstructor
    public UpCloudCloud(String name, String credentialsId) {
        super(name);
        this.credentialsId = credentialsId;
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    /** Maximum number of agents of this cloud, 0 for no limit. */
    public int getInstanceCap() {
        return instanceCap;
    }

    @DataBoundSetter
    public void setInstanceCap(int instanceCap) {
        if (instanceCap < 0) {
            throw new IllegalArgumentException("Instance cap must not be negative");
        }
        this.instanceCap = instanceCap;
    }

    /** Whether agents are provisioned as soon as builds queue up, see {@link UpCloudProvisioningStrategy}. */
    public boolean isNoDelayProvisioning() {
        return noDelayProvisioning;
    }

    @DataBoundSetter
    public void setNoDelayProvisioning(boolean noDelayProvisioning) {
        this.noDelayProvisioning = noDelayProvisioning;
    }

    /** Whether {@link UpCloudOrphanCleanup} deletes servers of this controller that no agent refers to. */
    public boolean isCleanupOrphanedServers() {
        return cleanupOrphanedServers;
    }

    @DataBoundSetter
    public void setCleanupOrphanedServers(boolean cleanupOrphanedServers) {
        this.cleanupOrphanedServers = cleanupOrphanedServers;
    }

    public List<UpCloudAgentTemplate> getTemplates() {
        return templates;
    }

    @DataBoundSetter
    public void setTemplates(List<UpCloudAgentTemplate> templates) {
        this.templates = new ArrayList<>(templates);
    }

    @CheckForNull
    public UpCloudAgentTemplate getTemplate(String name) {
        return templates.stream()
                .filter(template -> template.getName().equals(name))
                .findFirst()
                .orElse(null);
    }

    public UpCloudClient client() throws IOException {
        return client(credentialsId);
    }

    static UpCloudClient client(String credentialsId) throws IOException {
        StandardCredentials credentials = CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentialsInItemGroup(
                        StandardCredentials.class, Jenkins.get(), ACL.SYSTEM2, List.of()),
                CredentialsMatchers.withId(credentialsId));
        if (credentials instanceof StringCredentials token) {
            return new UpCloudClient(
                    apiUrl, UpCloudClient.bearer(token.getSecret().getPlainText()));
        }
        if (credentials instanceof StandardUsernamePasswordCredentials user) {
            return new UpCloudClient(
                    apiUrl,
                    UpCloudClient.basic(user.getUsername(), user.getPassword().getPlainText()));
        }
        throw new IOException("There are no UpCloud API credentials with ID '" + credentialsId + "'");
    }

    @Override
    public boolean canProvision(CloudState state) {
        return templates.stream().anyMatch(template -> template.matches(state.getLabel()) && hasCapacity(template));
    }

    @Override
    public Collection<PlannedNode> provision(CloudState state, int excessWorkload) {
        List<PlannedNode> planned = new ArrayList<>();
        for (UpCloudAgentTemplate template : templates) {
            if (!template.matches(state.getLabel())) {
                continue;
            }
            while (excessWorkload > 0 && hasCapacity(template)) {
                UpCloudAgent agent;
                try {
                    agent = addAgent(template);
                } catch (IOException | Descriptor.FormException e) {
                    LOGGER.log(Level.WARNING, "Failed to add an agent from template " + template.getName(), e);
                    return planned;
                }
                planned.add(new PlannedNode(
                        agent.getNodeName(), CompletableFuture.completedFuture(agent), agent.getNumExecutors()));
                excessWorkload -= agent.getNumExecutors();
            }
        }
        return planned;
    }

    /**
     * Adds the agent to Jenkins right away rather than leaving that to the provisioner, which gets to it only on
     * its next run: the instance caps count nodes, so an agent must be one before the next one is considered.
     */
    private UpCloudAgent addAgent(UpCloudAgentTemplate template) throws IOException, Descriptor.FormException {
        UpCloudAgent agent = new UpCloudAgent(name, template);
        Jenkins.get().addNode(agent);
        return agent;
    }

    private boolean hasCapacity(UpCloudAgentTemplate template) {
        List<UpCloudAgent> agents = agents();
        if (instanceCap > 0 && agents.size() >= instanceCap) {
            return false;
        }
        List<UpCloudAgent> ofTemplate = agents.stream()
                .filter(agent -> agent.getTemplate().getName().equals(template.getName()))
                .toList();
        if (template.getInstanceCap() > 0 && ofTemplate.size() >= template.getInstanceCap()) {
            return false;
        }
        // An agent that failed to launch stays around for a while before UpCloudRetentionStrategy removes it.
        // Holding its template back meanwhile keeps a broken template or an exhausted account from being
        // retried in a tight loop, each attempt creating a server.
        return ofTemplate.stream().noneMatch(UpCloudAgent::isDisconnected);
    }

    private List<UpCloudAgent> agents() {
        List<UpCloudAgent> agents = new ArrayList<>();
        for (Node node : Jenkins.get().getNodes()) {
            if (node instanceof UpCloudAgent agent && agent.getCloudName().equals(name)) {
                agents.add(agent);
            }
        }
        return agents;
    }

    /** Provisions an agent on request, from the "Provision via" button on the nodes page. */
    @RequirePOST
    public HttpResponse doProvision(@QueryParameter String template) throws IOException, Descriptor.FormException {
        checkPermission(PROVISION);
        UpCloudAgentTemplate agentTemplate = getTemplate(template);
        if (agentTemplate == null) {
            throw HttpResponses.error(HttpServletResponse.SC_BAD_REQUEST, "No such template: " + template);
        }
        if (!hasCapacity(agentTemplate)) {
            throw HttpResponses.error(
                    HttpServletResponse.SC_BAD_REQUEST,
                    "Template " + template + " is at its instance cap or has an agent that failed to launch");
        }
        return HttpResponses.redirectViaContextPath(
                "computer/" + Util.rawEncode(addAgent(agentTemplate).getNodeName()));
    }

    @Extension
    @Symbol("upcloud")
    public static class DescriptorImpl extends Descriptor<Cloud> {

        @Override
        public String getDisplayName() {
            return "UpCloud";
        }

        @POST
        public ListBoxModel doFillCredentialsIdItems(@QueryParameter String credentialsId) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            return new StandardListBoxModel()
                    .includeEmptyValue()
                    .includeMatchingAs(
                            ACL.SYSTEM2,
                            Jenkins.get(),
                            StandardCredentials.class,
                            List.of(),
                            CredentialsMatchers.anyOf(
                                    CredentialsMatchers.instanceOf(StringCredentials.class),
                                    CredentialsMatchers.instanceOf(StandardUsernamePasswordCredentials.class)))
                    .includeCurrentValue(credentialsId);
        }

        @POST
        public FormValidation doCheckCredentialsId(@QueryParameter String value) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            return Util.fixEmpty(value) == null
                    ? FormValidation.error("Credentials are required")
                    : FormValidation.ok();
        }

        @POST
        public FormValidation doTestConnection(@QueryParameter String credentialsId) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            try {
                return FormValidation.ok("Connected as " + client(credentialsId).getAccountUsername());
            } catch (IOException e) {
                return FormValidation.error(e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return FormValidation.error("Interrupted");
            }
        }
    }
}
