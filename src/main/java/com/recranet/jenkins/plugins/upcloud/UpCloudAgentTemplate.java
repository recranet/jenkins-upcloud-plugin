package com.recranet.jenkins.plugins.upcloud;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHAuthenticator;
import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import com.recranet.jenkins.plugins.upcloud.client.Network;
import com.recranet.jenkins.plugins.upcloud.client.Plan;
import com.recranet.jenkins.plugins.upcloud.client.Server;
import com.recranet.jenkins.plugins.upcloud.client.ServerSpec;
import com.recranet.jenkins.plugins.upcloud.client.Storage;
import com.recranet.jenkins.plugins.upcloud.client.UpCloudClient;
import com.recranet.jenkins.plugins.upcloud.client.Zone;
import com.trilead.ssh2.Connection;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.Extension;
import hudson.RelativePath;
import hudson.Util;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.Saveable;
import hudson.plugins.sshslaves.SSHLauncher;
import hudson.plugins.sshslaves.verifiers.ManuallyTrustedKeyVerificationStrategy;
import hudson.security.ACL;
import hudson.slaves.NodeProperty;
import hudson.slaves.NodePropertyDescriptor;
import hudson.util.DescribableList;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import java.io.IOException;
import java.io.Serializable;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

/**
 * Describes the servers a cloud creates for a set of labels. Each agent keeps the template it was created from,
 * so editing a cloud's templates affects new agents only.
 */
public class UpCloudAgentTemplate extends AbstractDescribableImpl<UpCloudAgentTemplate> implements Serializable {

    private static final long serialVersionUID = 1L;

    private static final Logger LOGGER = Logger.getLogger(UpCloudAgentTemplate.class.getName());

    /** Agent names are the template name plus a suffix and double as server hostnames. */
    private static final Pattern NAME = Pattern.compile("[a-z0-9]([a-z0-9-]{0,38}[a-z0-9])?");

    /** The network Jenkins reaches an agent over. Saved configurations hold the names of the constants. */
    public enum ConnectionNetwork {
        PUBLIC("Public IPv4 address", "public", "IPv4"),
        PUBLIC_IPV6("Public IPv6 address", "public", "IPv6"),
        UTILITY("Utility network address", "utility", "IPv4"),
        PRIVATE("SDN private network address", "private", "IPv4");

        private final String displayName;
        private final String access;
        private final String family;

        ConnectionNetwork(String displayName, String access, String family) {
            this.displayName = displayName;
            this.access = access;
            this.family = family;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    private final String name;
    private final String labelString;
    private final String zone;
    private final String plan;
    private final String image;
    private final String sshCredentialsId;
    private Node.Mode mode = Node.Mode.NORMAL;
    private int numExecutors = 1;
    private String remoteFs = "/home/jenkins";
    private int storageSizeGb;

    @CheckForNull
    private String storageTier;

    @CheckForNull
    private String userData;

    private boolean publicIpv4 = true;
    private boolean publicIpv6;

    /**
     * Null only in a template that was saved before the utility network could be turned off, see
     * {@link #readResolve}.
     */
    private Boolean utilityNetwork = true;

    @CheckForNull
    private String privateNetwork;

    private ConnectionNetwork connectionNetwork = ConnectionNetwork.PUBLIC;
    private int sshPort = 22;

    @CheckForNull
    private String javaPath;

    @CheckForNull
    private String jvmOptions;

    private int startupTimeoutSeconds = 600;
    private UpCloudShutdownPolicy shutdownPolicy = new UpCloudShutdownPolicy.AfterIdleTime(10);
    private int instanceCap;

    /**
     * Null only in a template that was saved before node properties could be set, see {@link #readResolve}. A
     * {@link DescribableList} because that is what the form needs to find the property of each kind.
     */
    @SuppressFBWarnings(
            value = "SE_BAD_FIELD",
            justification = "Jenkins persists templates with XStream, never with Java serialization")
    private DescribableList<NodeProperty<?>, NodePropertyDescriptor> nodeProperties =
            new DescribableList<>(Saveable.NOOP);

    @DataBoundConstructor
    public UpCloudAgentTemplate(
            String name, String labelString, String zone, String plan, String image, String sshCredentialsId) {
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Template name '" + name
                    + "' must consist of at most 40 lowercase letters, digits and hyphens,"
                    + " and start and end with a letter or digit");
        }
        this.name = name;
        this.labelString = Util.fixNull(labelString);
        this.zone = zone;
        this.plan = plan;
        this.image = image;
        this.sshCredentialsId = sshCredentialsId;
    }

    public String getName() {
        return name;
    }

    public String getLabelString() {
        return labelString;
    }

    public String getZone() {
        return zone;
    }

    public String getPlan() {
        return plan;
    }

    /** UUID of the storage template the boot disk is cloned from. */
    public String getImage() {
        return image;
    }

    public String getSshCredentialsId() {
        return sshCredentialsId;
    }

    public Node.Mode getMode() {
        return mode;
    }

    @DataBoundSetter
    public void setMode(Node.Mode mode) {
        this.mode = mode;
    }

    public int getNumExecutors() {
        return numExecutors;
    }

    @DataBoundSetter
    public void setNumExecutors(int numExecutors) {
        this.numExecutors = atLeast(1, numExecutors, "Number of executors");
    }

    public String getRemoteFs() {
        return remoteFs;
    }

    @DataBoundSetter
    public void setRemoteFs(String remoteFs) {
        this.remoteFs = remoteFs;
    }

    /** Boot disk size, 0 for the size included in the plan. */
    public int getStorageSizeGb() {
        return storageSizeGb;
    }

    @DataBoundSetter
    public void setStorageSizeGb(int storageSizeGb) {
        this.storageSizeGb = atLeast(0, storageSizeGb, "Storage size");
    }

    /** Boot disk tier, null for the tier included in the plan. */
    @CheckForNull
    public String getStorageTier() {
        return storageTier;
    }

    @DataBoundSetter
    public void setStorageTier(String storageTier) {
        this.storageTier = Util.fixEmptyAndTrim(storageTier);
    }

    /** Script, or URL of a script, that UpCloud runs as root when the server boots for the first time. */
    @CheckForNull
    public String getUserData() {
        return userData;
    }

    @DataBoundSetter
    public void setUserData(String userData) {
        this.userData = Util.fixEmptyAndTrim(userData);
    }

    public boolean isPublicIpv4() {
        return publicIpv4;
    }

    @DataBoundSetter
    public void setPublicIpv4(boolean publicIpv4) {
        this.publicIpv4 = publicIpv4;
    }

    public boolean isPublicIpv6() {
        return publicIpv6;
    }

    @DataBoundSetter
    public void setPublicIpv6(boolean publicIpv6) {
        this.publicIpv6 = publicIpv6;
    }

    public boolean isUtilityNetwork() {
        return utilityNetwork;
    }

    @DataBoundSetter
    public void setUtilityNetwork(boolean utilityNetwork) {
        this.utilityNetwork = utilityNetwork;
    }

    /**
     * Fills in the settings a template saved by an earlier version does not have: servers were always attached to
     * the utility network, and agents had no node properties.
     */
    private Object readResolve() {
        if (utilityNetwork == null) {
            utilityNetwork = true;
        }
        if (nodeProperties == null) {
            nodeProperties = new DescribableList<>(Saveable.NOOP);
        }
        return this;
    }

    /** UUID of an SDN private network to attach the server to, null for none. */
    @CheckForNull
    public String getPrivateNetwork() {
        return privateNetwork;
    }

    @DataBoundSetter
    public void setPrivateNetwork(String privateNetwork) {
        this.privateNetwork = Util.fixEmptyAndTrim(privateNetwork);
    }

    public ConnectionNetwork getConnectionNetwork() {
        return connectionNetwork;
    }

    @DataBoundSetter
    public void setConnectionNetwork(ConnectionNetwork connectionNetwork) {
        this.connectionNetwork = connectionNetwork;
    }

    public int getSshPort() {
        return sshPort;
    }

    @DataBoundSetter
    public void setSshPort(int sshPort) {
        this.sshPort = atLeast(1, sshPort, "SSH port");
    }

    @CheckForNull
    public String getJavaPath() {
        return javaPath;
    }

    @DataBoundSetter
    public void setJavaPath(String javaPath) {
        this.javaPath = Util.fixEmptyAndTrim(javaPath);
    }

    @CheckForNull
    public String getJvmOptions() {
        return jvmOptions;
    }

    @DataBoundSetter
    public void setJvmOptions(String jvmOptions) {
        this.jvmOptions = Util.fixEmptyAndTrim(jvmOptions);
    }

    /** How long a server may take to start and, separately, to then become reachable and finish initializing. */
    public int getStartupTimeoutSeconds() {
        return startupTimeoutSeconds;
    }

    @DataBoundSetter
    public void setStartupTimeoutSeconds(int startupTimeoutSeconds) {
        this.startupTimeoutSeconds = atLeast(1, startupTimeoutSeconds, "Startup timeout");
    }

    /** When an idle agent is removed. */
    public UpCloudShutdownPolicy getShutdownPolicy() {
        return shutdownPolicy;
    }

    @DataBoundSetter
    public void setShutdownPolicy(UpCloudShutdownPolicy shutdownPolicy) {
        this.shutdownPolicy = shutdownPolicy;
    }

    /** Maximum number of agents of this template, 0 for no limit. */
    public int getInstanceCap() {
        return instanceCap;
    }

    @DataBoundSetter
    public void setInstanceCap(int instanceCap) {
        this.instanceCap = atLeast(0, instanceCap, "Instance cap");
    }

    /** The node properties every agent of this template gets a copy of. */
    public DescribableList<NodeProperty<?>, NodePropertyDescriptor> getNodeProperties() {
        return nodeProperties;
    }

    @DataBoundSetter
    public void setNodeProperties(List<? extends NodeProperty<?>> nodeProperties) {
        this.nodeProperties = new DescribableList<>(Saveable.NOOP, nodeProperties);
    }

    private static int atLeast(int minimum, int value, String what) {
        if (value < minimum) {
            throw new IllegalArgumentException(what + " must be at least " + minimum);
        }
        return value;
    }

    /** Whether agents of this template may run builds asking for a label, or for none at all. */
    public boolean matches(@CheckForNull Label label) {
        return label == null ? mode == Node.Mode.NORMAL : label.matches(Label.parse(labelString));
    }

    ServerSpec serverSpec(String serverName, UpCloudClient client) throws IOException, InterruptedException {
        // Found out before there is a server to pay for.
        if (!isAttached(connectionNetwork, publicIpv4, publicIpv6, utilityNetwork, privateNetwork)) {
            throw new IOException("Template " + name + " connects to its agents over the "
                    + connectionNetwork.getDisplayName().toLowerCase(Locale.ROOT)
                    + ", but does not attach its servers to that network");
        }

        int size = storageSizeGb;
        String tier = storageTier;
        if (size == 0 || tier == null) {
            Plan included = client.listPlans().stream()
                    .filter(candidate -> candidate.name().equals(plan))
                    .findFirst()
                    .orElseThrow(() -> new IOException("UpCloud has no plan named " + plan));
            Storage source = client.getStorage(image);
            if (size == 0) {
                // Plans without storage get a disk just large enough for the image.
                size = included.storageSizeGb() > 0 ? included.storageSizeGb() : source.sizeGb();
            }
            if (tier == null) {
                // UpCloud refuses to clone one of the account's own images into a tier other than the one the
                // image is kept on, e.g. a standard image into the MaxIOPS storage of a plan. Its public images
                // go to any tier.
                tier = source.isPublic() ? included.storageTier() : source.tier();
            }
        }

        // UpCloud can install a key but not a password, so with password credentials the image has to accept
        // the login as it is.
        StandardUsernameCredentials credentials = sshCredentials();
        String sshPublicKey = credentials instanceof SSHUserPrivateKey key ? SshKeys.publicKey(key) : null;

        return new ServerSpec(
                serverName,
                zone,
                plan,
                image,
                size,
                tier,
                userData,
                sshPublicKey == null ? null : credentials.getUsername(),
                sshPublicKey,
                publicIpv4,
                publicIpv6,
                utilityNetwork,
                privateNetwork,
                Map.of(UpCloudCloud.INSTANCE_LABEL, Jenkins.get().getLegacyInstanceId()));
    }

    StandardUsernameCredentials sshCredentials() throws IOException {
        StandardUsernameCredentials credentials = SSHLauncher.lookupSystemCredentials(sshCredentialsId);
        if (credentials == null) {
            throw new IOException("There are no SSH credentials with ID '" + sshCredentialsId + "'");
        }
        return credentials;
    }

    /** The address of a server on the network Jenkins connects over. */
    String address(Server server) throws IOException {
        String address = server.address(connectionNetwork.access, connectionNetwork.family);
        if (address == null) {
            throw new IOException("Server " + server.uuid() + " has no "
                    + connectionNetwork.getDisplayName().toLowerCase(Locale.ROOT));
        }
        return address;
    }

    private static boolean isAttached(
            ConnectionNetwork network,
            boolean publicIpv4,
            boolean publicIpv6,
            boolean utilityNetwork,
            @CheckForNull String privateNetwork) {
        return switch (network) {
            case PUBLIC -> publicIpv4;
            case PUBLIC_IPV6 -> publicIpv6;
            case UTILITY -> utilityNetwork;
            case PRIVATE -> privateNetwork != null;
        };
    }

    SSHLauncher sshLauncher(String host) {
        // Servers are new, so there is no known host key to compare against: the key seen on the first
        // connection is trusted and later connections must present the same one.
        return new SSHLauncher(
                host,
                sshPort,
                sshCredentialsId,
                jvmOptions,
                javaPath,
                null,
                null,
                null,
                null,
                null,
                new ManuallyTrustedKeyVerificationStrategy(false));
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<UpCloudAgentTemplate> {

        @Override
        public String getDisplayName() {
            return "UpCloud agent template";
        }

        public List<NodePropertyDescriptor> getNodePropertyDescriptors() {
            return NodeProperty.all().stream()
                    .filter(descriptor -> descriptor.isApplicable(UpCloudAgent.class))
                    .toList();
        }

        @POST
        public FormValidation doCheckName(@QueryParameter String value) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            return NAME.matcher(value).matches()
                    ? FormValidation.ok()
                    : FormValidation.error("Use at most 40 lowercase letters, digits and hyphens,"
                            + " starting and ending with a letter or digit");
        }

        @POST
        public FormValidation doCheckRemoteFs(@QueryParameter String value) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            return value.startsWith("/") ? FormValidation.ok() : FormValidation.error("An absolute path is required");
        }

        @POST
        public FormValidation doCheckConnectionNetwork(
                @QueryParameter String value,
                @QueryParameter boolean publicIpv4,
                @QueryParameter boolean publicIpv6,
                @QueryParameter boolean utilityNetwork,
                @QueryParameter String privateNetwork) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            return isAttached(
                            ConnectionNetwork.valueOf(value),
                            publicIpv4,
                            publicIpv6,
                            utilityNetwork,
                            Util.fixEmpty(privateNetwork))
                    ? FormValidation.ok()
                    : FormValidation.error("The server is not attached to this network");
        }

        @POST
        public ListBoxModel doFillZoneItems(
                @RelativePath("..") @QueryParameter String credentialsId, @QueryParameter String zone) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            return fill(credentialsId, zone, "Select credentials to list zones", (client, items) -> {
                for (Zone candidate : client.listZones()) {
                    items.add(candidate.description() + " (" + candidate.id() + ")", candidate.id());
                }
            });
        }

        @POST
        public ListBoxModel doFillPlanItems(
                @RelativePath("..") @QueryParameter String credentialsId, @QueryParameter String plan) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            return fill(credentialsId, plan, "Select credentials to list plans", (client, items) -> {
                List<Plan> plans = client.listPlans();
                plans.sort(Comparator.comparingInt(Plan::cores).thenComparingInt(Plan::memoryMb));
                for (Plan candidate : plans) {
                    String storage = candidate.storageSizeGb() > 0
                            ? candidate.storageSizeGb() + " GB " + candidate.storageTier()
                            : "no storage included";
                    items.add(
                            candidate.name() + " (" + candidate.cores() + " cores, " + candidate.memoryMb()
                                    + " MB memory, " + storage + ")",
                            candidate.name());
                }
            });
        }

        /*
         * The image and private network lists do not depend on the selected zone, although both can only be used
         * in their own zone: a zone select that fills itself in does not report its first value as a change, so
         * lists narrowed down to the zone would start out without any of the account's own resources. Everything
         * is listed with its zone instead, and a mismatch is reported by the checks.
         */

        @POST
        public ListBoxModel doFillImageItems(
                @RelativePath("..") @QueryParameter String credentialsId, @QueryParameter String image) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            return fill(credentialsId, image, "Select credentials to list images", (client, items) -> {
                List<Storage> templates = client.listTemplates();
                // The account's own images first: they are the ones built for the purpose.
                templates.sort(Comparator.comparing(Storage::isPublic).thenComparing(Storage::title));
                for (Storage template : templates) {
                    items.add(
                            template.isPublic()
                                    ? template.title()
                                    : "Custom: " + template.title() + " (" + template.zone() + ", " + template.sizeGb()
                                            + " GB)",
                            template.uuid());
                }
            });
        }

        @POST
        public FormValidation doCheckImage(
                @RelativePath("..") @QueryParameter String credentialsId,
                @QueryParameter String value,
                @QueryParameter String zone) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            if (Util.fixEmpty(credentialsId) == null || Util.fixEmpty(value) == null || Util.fixEmpty(zone) == null) {
                return FormValidation.ok();
            }
            try {
                // Public images have no zone and are available in all of them.
                String imageZone =
                        UpCloudCloud.client(credentialsId).getStorage(value).zone();
                return imageZone == null || imageZone.equals(zone)
                        ? FormValidation.ok()
                        : FormValidation.error("This image is stored in zone " + imageZone
                                + " and can only be used for servers in that zone");
            } catch (IOException e) {
                return FormValidation.warning("The image could not be checked: " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return FormValidation.ok();
            }
        }

        @POST
        public ListBoxModel doFillPrivateNetworkItems(
                @RelativePath("..") @QueryParameter String credentialsId, @QueryParameter String privateNetwork) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            ListBoxModel items = new ListBoxModel();
            items.add("None", "");
            if (Util.fixEmpty(credentialsId) != null) {
                try {
                    for (Network network : UpCloudCloud.client(credentialsId).listPrivateNetworks()) {
                        items.add(network.name() + " (" + network.zone() + ")", network.uuid());
                    }
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Failed to list UpCloud private networks", e);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return withCurrent(items, privateNetwork);
        }

        @POST
        public FormValidation doCheckPrivateNetwork(
                @RelativePath("..") @QueryParameter String credentialsId,
                @QueryParameter String value,
                @QueryParameter String zone) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            if (Util.fixEmpty(credentialsId) == null || Util.fixEmpty(value) == null || Util.fixEmpty(zone) == null) {
                return FormValidation.ok();
            }
            try {
                for (Network network : UpCloudCloud.client(credentialsId).listPrivateNetworks()) {
                    if (network.uuid().equals(value) && !network.zone().equals(zone)) {
                        return FormValidation.error("This network is in zone " + network.zone()
                                + " and can only be used for servers in that zone");
                    }
                }
                return FormValidation.ok();
            } catch (IOException e) {
                return FormValidation.warning("The network could not be checked: " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return FormValidation.ok();
            }
        }

        @POST
        public ListBoxModel doFillStorageTierItems() {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            ListBoxModel items = new ListBoxModel();
            items.add("Automatic", "");
            items.add("MaxIOPS", "maxiops");
            items.add("Standard", "standard");
            items.add("HDD", "hdd");
            return items;
        }

        @POST
        public ListBoxModel doFillConnectionNetworkItems() {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            ListBoxModel items = new ListBoxModel();
            for (ConnectionNetwork network : ConnectionNetwork.values()) {
                items.add(network.getDisplayName(), network.name());
            }
            return items;
        }

        @POST
        public ListBoxModel doFillSshCredentialsIdItems(@QueryParameter String sshCredentialsId) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            return new StandardListBoxModel()
                    .includeEmptyValue()
                    .includeMatchingAs(
                            ACL.SYSTEM2,
                            Jenkins.get(),
                            StandardUsernameCredentials.class,
                            List.of(SSHLauncher.SSH_SCHEME),
                            SSHAuthenticator.matcher(Connection.class))
                    .includeCurrentValue(sshCredentialsId);
        }

        @POST
        public FormValidation doCheckSshCredentialsId(@QueryParameter String value) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            return Util.fixEmpty(value) == null
                    ? FormValidation.error("Credentials are required")
                    : FormValidation.ok();
        }

        /**
         * Builds the options of a select from the API. The configured value stays selectable when the API cannot
         * be reached or no longer offers it, so that saving the form does not silently replace it.
         */
        private static ListBoxModel fill(String credentialsId, String current, String placeholder, Filler filler) {
            ListBoxModel items = new ListBoxModel();
            if (Util.fixEmpty(credentialsId) == null) {
                items.add(placeholder, "");
            } else {
                try {
                    filler.fill(UpCloudCloud.client(credentialsId), items);
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Failed to list UpCloud resources", e);
                    items.add("Failed to load: " + e.getMessage(), "");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return withCurrent(items, current);
        }

        private static ListBoxModel withCurrent(ListBoxModel items, String current) {
            if (Util.fixEmpty(current) != null && items.stream().noneMatch(item -> item.value.equals(current))) {
                items.add(current + " (not available)", current);
            }
            return items;
        }

        @FunctionalInterface
        private interface Filler {
            void fill(UpCloudClient client, ListBoxModel items) throws IOException, InterruptedException;
        }
    }
}
