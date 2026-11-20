package com.recranet.jenkins.plugins.upcloud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import com.google.gson.JsonObject;
import com.recranet.jenkins.plugins.upcloud.UpCloudAgentTemplate.ConnectionNetwork;
import com.recranet.jenkins.plugins.upcloud.client.ServerSpec;
import hudson.model.Computer;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.model.User;
import hudson.model.queue.QueueTaskFuture;
import hudson.slaves.Cloud;
import hudson.slaves.EnvironmentVariablesNodeProperty;
import hudson.slaves.NodeProvisioner.PlannedNode;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import java.io.File;
import java.net.ServerSocket;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.html.HtmlSelect;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.SleepBuilder;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.springframework.security.core.Authentication;

@WithJenkins
class UpCloudCloudTest {

    private JenkinsRule r;
    private FakeUpCloud api;
    private String originalApiUrl;

    @BeforeEach
    void setUp(JenkinsRule rule) throws Exception {
        r = rule;
        api = new FakeUpCloud();
        originalApiUrl = UpCloudCloud.apiUrl;
        UpCloudCloud.apiUrl = api.url();
        SystemCredentialsProvider.getInstance()
                .getCredentials()
                .addAll(List.of(
                        new StringCredentialsImpl(
                                CredentialsScope.GLOBAL, "upcloud", null, Secret.fromString(FakeUpCloud.TOKEN)),
                        new UsernamePasswordCredentialsImpl(
                                CredentialsScope.GLOBAL, "ssh-password", null, "root", "s3cret")));
    }

    @AfterEach
    void tearDown() {
        UpCloudCloud.apiUrl = originalApiUrl;
        api.close();
    }

    private UpCloudAgentTemplate template(String name, String labels) {
        UpCloudAgentTemplate template = new UpCloudAgentTemplate(
                name, labels, "fi-hel1", "2xCPU-4GB", FakeUpCloud.CUSTOM_IMAGE, "ssh-password");
        // Nothing listens for SSH in these tests: launches are meant to fail, and to fail quickly.
        template.setStartupTimeoutSeconds(1);
        return template;
    }

    private UpCloudCloud cloud(UpCloudAgentTemplate... templates) {
        UpCloudCloud cloud = new UpCloudCloud("upcloud", "upcloud");
        cloud.setNoDelayProvisioning(false);
        cloud.setTemplates(List.of(templates));
        r.jenkins.clouds.add(cloud);
        return cloud;
    }

    private static Cloud.CloudState state(String label) {
        return new Cloud.CloudState(label == null ? null : Label.get(label), 0);
    }

    private static int closedPort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void await(Callable<Boolean> condition) throws Exception {
        Instant deadline = Instant.now().plusSeconds(60);
        while (!condition.call()) {
            assertTrue(Instant.now().isBefore(deadline), "condition was not met in time");
            Thread.sleep(100);
        }
    }

    /** Adds an agent that is online, standing in for one whose server was created and reached over SSH. */
    private UpCloudAgent onlineAgent(UpCloudCloud cloud, UpCloudAgentTemplate template) throws Exception {
        template.setRemoteFs(new File(r.jenkins.getRootDir(), "agent").getAbsolutePath());
        UpCloudAgent agent = new UpCloudAgent(cloud.name, template, r.createComputerLauncher(null));
        r.jenkins.addNode(agent);
        agent.setServer(cloud.client()
                .getServer(api.addServer(agent.getNodeName(), Instant.now(), r.jenkins.getLegacyInstanceId())));
        r.waitOnline(agent);
        return agent;
    }

    @Test
    void provisionsForMatchingLabelsOnly() throws Exception {
        UpCloudAgentTemplate exclusive = template("docker", "docker");
        exclusive.setMode(Node.Mode.EXCLUSIVE);
        UpCloudCloud cloud = cloud(template("linux", "linux java"), exclusive);

        assertTrue(cloud.canProvision(state("linux")));
        assertTrue(cloud.canProvision(state("java")));
        assertTrue(cloud.canProvision(state("docker")));
        assertTrue(cloud.canProvision(state(null)));
        assertFalse(cloud.canProvision(state("windows")));

        Collection<PlannedNode> planned = cloud.provision(state("docker"), 1);

        assertEquals(1, planned.size());
        UpCloudAgent agent = (UpCloudAgent) planned.iterator().next().future.get();
        assertTrue(agent.getNodeName().matches("docker-[0-9a-f]{8}"));
        assertEquals("docker", agent.getLabelString());
        assertEquals(Node.Mode.EXCLUSIVE, agent.getMode());
        assertEquals("/home/jenkins", agent.getRemoteFS());
        assertEquals(agent, r.jenkins.getNode(agent.getNodeName()));

        assertTrue(cloud.provision(state("windows"), 1).isEmpty());
    }

    @Test
    void provisionsExecutorsUpToTheCaps() throws Exception {
        UpCloudAgentTemplate small = template("small", "linux");
        small.setNumExecutors(2);
        small.setInstanceCap(2);
        UpCloudAgentTemplate large = template("large", "linux");
        UpCloudCloud cloud = cloud(small, large);
        cloud.setInstanceCap(4);

        // Three executors take two agents of two executors each.
        assertEquals(2, cloud.provision(state("linux"), 3).size());
        // The first template is at its cap, the second one takes over until the cloud is at its cap.
        Collection<PlannedNode> planned = cloud.provision(state("linux"), 5);
        assertEquals(2, planned.size());
        assertTrue(planned.stream().allMatch(node -> node.displayName.startsWith("large-")));
        assertFalse(cloud.canProvision(state("linux")));
        assertTrue(cloud.provision(state("linux"), 1).isEmpty());
    }

    @Test
    void launchCreatesServerAndFailedLaunchHoldsTemplateBack() throws Exception {
        UpCloudAgentTemplate template = template("linux", "linux");
        template.setSshPort(closedPort());
        template.setUserData("#!/bin/sh\napt-get install -y default-jre-headless");
        template.setPrivateNetwork(FakeUpCloud.PRIVATE_NETWORK);
        UpCloudCloud cloud = cloud(template);

        UpCloudAgent agent = (UpCloudAgent)
                cloud.provision(state("linux"), 1).iterator().next().future.get();
        UpCloudComputer computer = (UpCloudComputer) agent.toComputer();
        await(computer::isDisconnected);

        JsonObject request = api.createRequests.get(0);
        assertEquals(agent.getNodeName(), request.get("title").getAsString());
        assertEquals("fi-hel1", request.get("zone").getAsString());
        assertEquals("2xCPU-4GB", request.get("plan").getAsString());
        // The storage size comes from the plan, the tier from the custom image.
        assertEquals(
                "{\"storage_device\":[{\"action\":\"clone\",\"storage\":\"" + FakeUpCloud.CUSTOM_IMAGE
                        + "\",\"title\":\"" + agent.getNodeName() + "\",\"size\":80,\"tier\":\"standard\"}]}",
                request.get("storage_devices").toString());
        // UpCloud is given nothing to install for password credentials.
        assertFalse(request.has("login_user"));
        assertEquals(
                "#!/bin/sh\napt-get install -y default-jre-headless",
                request.get("user_data").getAsString());
        assertTrue(request.get("networking").toString().contains(FakeUpCloud.PRIVATE_NETWORK));
        assertEquals(
                "{\"label\":[{\"key\":\"jenkins-instance\",\"value\":\"" + r.jenkins.getLegacyInstanceId() + "\"}]}",
                request.get("labels").toString());
        assertTrue(api.servers.containsKey(agent.getServerUuid()));
        assertTrue(computer.getLog().contains("Timed out waiting for SSH"), computer.getLog());

        assertFalse(cloud.canProvision(state("linux")));
        assertTrue(cloud.provision(state("linux"), 1).isEmpty());

        UpCloudRetentionStrategy retention = (UpCloudRetentionStrategy) agent.getRetentionStrategy();
        retention.check(computer, System.currentTimeMillis());
        assertNotNull(r.jenkins.getNode(agent.getNodeName()));

        retention.check(
                computer,
                System.currentTimeMillis() + UpCloudRetentionStrategy.DISCONNECTED_RETENTION.toMillis() + 1000);
        await(() -> r.jenkins.getNode(agent.getNodeName()) == null);
        assertTrue(api.servers.isEmpty());
        assertTrue(cloud.canProvision(state("linux")));
    }

    @Test
    void launchOnPlanWithoutStorageAndWithoutPublicAddress() throws Exception {
        UpCloudAgentTemplate template = new UpCloudAgentTemplate(
                "linux", "linux", "fi-hel1", "CLOUDNATIVE-4xCPU-8GB", FakeUpCloud.CUSTOM_IMAGE, "ssh-password");
        template.setStartupTimeoutSeconds(1);
        template.setSshPort(closedPort());
        template.setPublicIpv4(false);
        template.setConnectionNetwork(ConnectionNetwork.UTILITY);
        UpCloudCloud cloud = cloud(template);

        UpCloudAgent agent = (UpCloudAgent)
                cloud.provision(state("linux"), 1).iterator().next().future.get();
        await(agent::isDisconnected);

        JsonObject request = api.createRequests.get(0);
        assertFalse(request.has("login_user"));
        assertFalse(request.has("user_data"));
        // The plan includes no storage: the disk is as large as the custom image and has its tier.
        assertEquals(
                "{\"storage_device\":[{\"action\":\"clone\",\"storage\":\"" + FakeUpCloud.CUSTOM_IMAGE
                        + "\",\"title\":\"" + agent.getNodeName() + "\",\"size\":20,\"tier\":\"standard\"}]}",
                request.get("storage_devices").toString());
        assertFalse(request.get("networking").toString().contains("public"));
        agent.terminate();
    }

    @Test
    void attachesTheSelectedNetworksAndConnectsOverIpv6() throws Exception {
        UpCloudAgentTemplate template = template("linux", "linux");
        template.setSshPort(closedPort());
        template.setPublicIpv4(false);
        template.setPublicIpv6(true);
        template.setUtilityNetwork(false);
        template.setConnectionNetwork(ConnectionNetwork.PUBLIC_IPV6);
        UpCloudCloud cloud = cloud(template);

        UpCloudAgent agent = (UpCloudAgent)
                cloud.provision(state("linux"), 1).iterator().next().future.get();
        await(agent::isDisconnected);

        assertEquals(
                "{\"interfaces\":{\"interface\":["
                        + "{\"type\":\"public\",\"ip_addresses\":{\"ip_address\":[{\"family\":\"IPv6\"}]}}]}}",
                api.createRequests.get(0).get("networking").toString());
        String log = agent.toComputer().getLog();
        assertTrue(log.contains("Waiting for SSH on ::1:"), log);
        agent.terminate();
    }

    @Test
    void createsNoServerItCouldNotConnectTo() throws Exception {
        UpCloudAgentTemplate template = template("linux", "linux");
        template.setUtilityNetwork(false);
        template.setConnectionNetwork(ConnectionNetwork.UTILITY);
        UpCloudCloud cloud = cloud(template);

        UpCloudAgent agent = (UpCloudAgent)
                cloud.provision(state("linux"), 1).iterator().next().future.get();
        await(agent::isDisconnected);

        assertTrue(api.createRequests.isEmpty());
        String log = agent.toComputer().getLog();
        assertTrue(log.contains("does not attach its servers to that network"), log);
        agent.terminate();
    }

    @Test
    void templateSavedByAnEarlierVersionGetsTheSettingsItLacks() {
        UpCloudAgentTemplate saved = (UpCloudAgentTemplate)
                Jenkins.XSTREAM2.fromXML("<com.recranet.jenkins.plugins.upcloud.UpCloudAgentTemplate>"
                        + "<name>linux</name><labelString>linux</labelString><zone>fi-hel1</zone>"
                        + "<plan>2xCPU-4GB</plan><image>image</image><sshCredentialsId>ssh</sshCredentialsId>"
                        + "<publicIpv4>true</publicIpv4><connectionNetwork>PUBLIC</connectionNetwork>"
                        + "</com.recranet.jenkins.plugins.upcloud.UpCloudAgentTemplate>");

        assertTrue(saved.getNodeProperties().isEmpty());
        assertTrue(saved.isUtilityNetwork());
        assertTrue(saved.isPublicIpv4());
        assertFalse(saved.isPublicIpv6());
    }

    @Test
    void agentsGetTheNodePropertiesOfTheirTemplate() throws Exception {
        UpCloudAgentTemplate template = template("linux", "linux");
        template.setSshPort(closedPort());
        EnvironmentVariablesNodeProperty environment =
                new EnvironmentVariablesNodeProperty(new EnvironmentVariablesNodeProperty.Entry("CLOUD", "upcloud"));
        template.setNodeProperties(List.of(environment));
        UpCloudCloud cloud = cloud(template);

        assertTrue(r.jenkins
                .getDescriptorByType(UpCloudAgentTemplate.DescriptorImpl.class)
                .getNodePropertyDescriptors()
                .contains(r.jenkins.getDescriptor(EnvironmentVariablesNodeProperty.class)));

        List<UpCloudAgent> agents = new ArrayList<>();
        for (PlannedNode planned : cloud.provision(state("linux"), 2)) {
            agents.add((UpCloudAgent) planned.future.get());
        }

        assertEquals(2, agents.size());
        EnvironmentVariablesNodeProperty first = agents.get(0).getNodeProperty(EnvironmentVariablesNodeProperty.class);
        EnvironmentVariablesNodeProperty second = agents.get(1).getNodeProperty(EnvironmentVariablesNodeProperty.class);
        assertEquals("upcloud", first.getEnvVars().get("CLOUD"));
        assertEquals("upcloud", second.getEnvVars().get("CLOUD"));
        // Each agent has a property of its own.
        assertNotSame(environment, first);
        assertNotSame(first, second);

        for (UpCloudAgent agent : agents) {
            await(agent::isDisconnected);
            agent.terminate();
        }
    }

    @Test
    void picksStorageSizeAndTier() throws Exception {
        UpCloudCloud cloud = cloud();

        // A public image goes to the storage the plan includes.
        ServerSpec spec = new UpCloudAgentTemplate(
                        "linux", "", "fi-hel1", "2xCPU-4GB", FakeUpCloud.PUBLIC_IMAGE, "ssh-password")
                .serverSpec("linux-1", cloud.client());
        assertEquals(80, spec.storageSizeGb());
        assertEquals("maxiops", spec.storageTier());

        // A plan without storage leaves the tier of a public image to UpCloud.
        spec = new UpCloudAgentTemplate(
                        "linux", "", "fi-hel1", "CLOUDNATIVE-4xCPU-8GB", FakeUpCloud.PUBLIC_IMAGE, "ssh-password")
                .serverSpec("linux-1", cloud.client());
        assertEquals(4, spec.storageSizeGb());
        assertNull(spec.storageTier());

        // A custom image stays on its own tier, whatever the plan includes.
        spec = new UpCloudAgentTemplate("linux", "", "fi-hel1", "2xCPU-4GB", FakeUpCloud.CUSTOM_IMAGE, "ssh-password")
                .serverSpec("linux-1", cloud.client());
        assertEquals("standard", spec.storageTier());

        // What is configured wins.
        UpCloudAgentTemplate configured =
                new UpCloudAgentTemplate("linux", "", "fi-hel1", "2xCPU-4GB", FakeUpCloud.CUSTOM_IMAGE, "ssh-password");
        configured.setStorageSizeGb(200);
        configured.setStorageTier("hdd");
        spec = configured.serverSpec("linux-1", cloud.client());
        assertEquals(200, spec.storageSizeGb());
        assertEquals("hdd", spec.storageTier());
    }

    @Test
    void failedServerCreationLeavesNoServer() throws Exception {
        api.createError = "INSUFFICIENT_CREDITS";
        UpCloudCloud cloud = cloud(template("linux", "linux"));

        UpCloudAgent agent = (UpCloudAgent)
                cloud.provision(state("linux"), 1).iterator().next().future.get();
        UpCloudComputer computer = (UpCloudComputer) agent.toComputer();
        await(computer::isDisconnected);

        assertNull(agent.getServerUuid());
        assertTrue(computer.getLog().contains("The server cannot be created"), computer.getLog());
        // An error from UpCloud is reported as such, without a stack trace.
        assertFalse(computer.getLog().contains("probably a bug in Jenkins"), computer.getLog());
        assertFalse(computer.getLog().contains("\tat "), computer.getLog());
        assertFalse(cloud.canProvision(state("linux")));
        agent.terminate();
        assertNull(r.jenkins.getNode(agent.getNodeName()));
    }

    @Test
    void terminatesIdleAgentAndDeletesItsServer() throws Exception {
        UpCloudAgentTemplate template = template("linux", "linux");
        template.setShutdownPolicy(new UpCloudShutdownPolicy.AfterIdleTime(3));
        UpCloudCloud cloud = cloud(template);
        UpCloudAgent agent = onlineAgent(cloud, template);
        UpCloudComputer computer = (UpCloudComputer) agent.toComputer();
        UpCloudRetentionStrategy retention = (UpCloudRetentionStrategy) agent.getRetentionStrategy();

        FreeStyleProject project = r.createFreeStyleProject();
        project.setAssignedLabel(Label.get("linux"));
        FreeStyleBuild build = r.buildAndAssertSuccess(project);
        assertEquals(agent, build.getBuiltOn());

        // An executor that is still wrapping up its build reports an idle start in the future.
        await(computer::isIdle);
        long idleSince = computer.getIdleStartMilliseconds();
        retention.check(computer, idleSince + Duration.ofMinutes(3).toMillis());
        assertTrue(computer.isAcceptingTasks());
        assertNotNull(r.jenkins.getNode(agent.getNodeName()));

        retention.check(computer, idleSince + Duration.ofMinutes(3).toMillis() + 1);
        assertFalse(computer.isAcceptingTasks());
        await(() -> r.jenkins.getNode(agent.getNodeName()) == null);
        assertTrue(api.servers.isEmpty());
    }

    @Test
    void keepsIdleAgentUntilItsBillingHourEnds() throws Exception {
        UpCloudAgentTemplate template = template("linux", "linux");
        template.setShutdownPolicy(new UpCloudShutdownPolicy.BeforeBillingHourEnds());
        UpCloudAgent agent = onlineAgent(cloud(template), template);
        UpCloudComputer computer = (UpCloudComputer) agent.toComputer();
        UpCloudRetentionStrategy retention = (UpCloudRetentionStrategy) agent.getRetentionStrategy();
        long created = agent.getServerCreated().toEpochMilli();
        await(computer::isIdle);

        retention.check(computer, created + Duration.ofMinutes(54).toMillis());
        retention.check(computer, created + Duration.ofMinutes(61).toMillis());
        assertTrue(computer.isAcceptingTasks());

        retention.check(computer, created + Duration.ofMinutes(116).toMillis());
        assertFalse(computer.isAcceptingTasks());
        await(() -> r.jenkins.getNode(agent.getNodeName()) == null);
        assertTrue(api.servers.isEmpty());
    }

    @Test
    void keepsAgentThatIsBuilding() throws Exception {
        UpCloudAgentTemplate template = template("linux", "linux");
        UpCloudCloud cloud = cloud(template);
        UpCloudAgent agent = onlineAgent(cloud, template);
        UpCloudComputer computer = (UpCloudComputer) agent.toComputer();

        FreeStyleProject project = r.createFreeStyleProject();
        project.setAssignedLabel(Label.get("linux"));
        project.getBuildersList().add(new SleepBuilder(Long.MAX_VALUE));
        FreeStyleBuild build = project.scheduleBuild2(0).waitForStart();

        ((UpCloudRetentionStrategy) agent.getRetentionStrategy())
                .check(computer, System.currentTimeMillis() + Duration.ofDays(1).toMillis());

        assertTrue(computer.isAcceptingTasks());
        assertNotNull(r.jenkins.getNode(agent.getNodeName()));
        build.doStop();
        r.waitForCompletion(build);
        agent.terminate();
    }

    @Test
    void deletingAnAgentDeletesItsServer() throws Exception {
        UpCloudAgentTemplate template = template("linux", "linux");
        UpCloudAgent agent = onlineAgent(cloud(template), template);

        agent.toComputer().doDoDelete();

        assertNull(r.jenkins.getNode(agent.getNodeName()));
        assertTrue(api.servers.isEmpty());
    }

    @Test
    void agentIsRestoredFromDisk() throws Exception {
        UpCloudAgentTemplate template = template("linux", "linux");
        template.setUserData("#!/bin/sh\necho hello");
        UpCloudAgent agent = onlineAgent(cloud(template), template);

        r.jenkins.getNodesObject().load();

        UpCloudAgent restored = (UpCloudAgent) r.jenkins.getNode(agent.getNodeName());
        assertEquals("upcloud", restored.getCloudName());
        assertEquals(agent.getServerUuid(), restored.getServerUuid());
        assertEquals(agent.getServerCreated(), restored.getServerCreated());
        r.assertEqualDataBoundBeans(template, restored.getTemplate());
        assertTrue(restored.getRetentionStrategy() instanceof UpCloudRetentionStrategy);
        restored.terminate();
        assertTrue(api.servers.isEmpty());
    }

    @Test
    void agentsCannotBeConfigured() throws Exception {
        UpCloudAgentTemplate template = template("linux", "linux");
        UpCloudAgent agent = onlineAgent(cloud(template), template);
        Computer computer = agent.toComputer();

        r.jenkins.setSecurityRealm(r.createDummySecurityRealm());
        Authentication admin = User.getById("admin", true).impersonate2();
        assertFalse(computer.getACL().hasPermission2(admin, Computer.CONFIGURE));
        assertTrue(computer.getACL().hasPermission2(admin, Computer.DELETE));
        String details = r.createWebClient().goTo(computer.getUrl()).asNormalizedText();
        assertTrue(details.contains(agent.getServerUuid()), details);
        assertTrue(details.contains("2xCPU-4GB"), details);
        String page = r.createWebClient().goTo(computer.getUrl() + "configure").asNormalizedText();
        assertTrue(page.contains(agent.getServerUuid()), page);
        assertFalse(page.contains("Save"), page);
        agent.terminate();
    }

    @Test
    void provisionsWithoutDelayForQueuedBuild() throws Exception {
        UpCloudAgentTemplate template = template("linux", "linux");
        template.setSshPort(closedPort());
        UpCloudCloud cloud = cloud(template);
        cloud.setNoDelayProvisioning(true);

        FreeStyleProject project = r.createFreeStyleProject();
        project.setAssignedLabel(Label.get("linux"));
        QueueTaskFuture<FreeStyleBuild> build = project.scheduleBuild2(0);

        // The standard strategy would not provision within its first 100 seconds.
        await(() -> r.jenkins.getNodes().stream().anyMatch(UpCloudAgent.class::isInstance));
        await(() -> !api.createRequests.isEmpty());
        assertEquals(1, r.jenkins.getNodes().size());

        build.cancel(true);
        for (Node node : r.jenkins.getNodes()) {
            await(((UpCloudAgent) node)::isDisconnected);
            ((UpCloudAgent) node).terminate();
        }
    }

    @Test
    void provisionsOnRequest() throws Exception {
        UpCloudAgentTemplate template = template("linux", "linux");
        template.setSshPort(closedPort());
        UpCloudCloud cloud = cloud(template);

        JenkinsRule.WebClient client = r.createWebClient();
        HtmlPage nodes = client.goTo("computer/");
        assertTrue(nodes.asNormalizedText().contains("Provision via upcloud"), nodes.asNormalizedText());

        WebRequest request =
                new WebRequest(new URL(r.getURL(), cloud.getUrl() + "provision?template=linux"), HttpMethod.POST);
        client.getPage(client.addCrumb(request));

        UpCloudAgent agent = (UpCloudAgent) r.jenkins.getNodes().get(0);
        assertEquals("linux", agent.getTemplate().getName());

        client.setThrowExceptionOnFailingStatusCode(false);
        WebRequest unknown =
                new WebRequest(new URL(r.getURL(), cloud.getUrl() + "provision?template=nope"), HttpMethod.POST);
        assertEquals(
                400, client.getPage(client.addCrumb(unknown)).getWebResponse().getStatusCode());

        await(agent::isDisconnected);
        agent.terminate();
    }

    @Test
    void cleansUpOrphanedServers() throws Exception {
        UpCloudAgentTemplate template = template("linux", "linux");
        UpCloudCloud cloud = cloud(template);
        String instance = r.jenkins.getLegacyInstanceId();
        Instant old = Instant.now().minus(Duration.ofHours(1));
        UpCloudAgent agent = onlineAgent(cloud, template);
        api.servers.get(agent.getServerUuid()).addProperty("created", old.getEpochSecond());
        String orphan = api.addServer("linux-orphan", old, instance);
        String young = api.addServer("linux-young", Instant.now(), instance);
        String foreign = api.addServer("linux-foreign", old, "another-controller");
        UpCloudOrphanCleanup cleanup =
                r.jenkins.getExtensionList(UpCloudOrphanCleanup.class).get(0);

        cloud.setCleanupOrphanedServers(false);
        cleanup.execute(TaskListener.NULL);
        assertEquals(4, api.servers.size());

        cloud.setCleanupOrphanedServers(true);
        cleanup.execute(TaskListener.NULL);

        assertFalse(api.servers.containsKey(orphan));
        assertEquals(List.of(agent.getServerUuid(), young, foreign), List.copyOf(api.servers.keySet()));
        agent.terminate();
    }

    @Test
    void fillsSelectsFromTheApi() {
        UpCloudAgentTemplate.DescriptorImpl descriptor =
                r.jenkins.getDescriptorByType(UpCloudAgentTemplate.DescriptorImpl.class);

        assertEquals(List.of("fi-hel1", "nl-ams1"), values(descriptor.doFillZoneItems("upcloud", "")));
        ListBoxModel plans = descriptor.doFillPlanItems("upcloud", "");
        assertEquals(List.of("1xCPU-1GB", "2xCPU-4GB", "CLOUDNATIVE-4xCPU-8GB"), values(plans));
        assertEquals("1xCPU-1GB (1 cores, 1024 MB memory, 25 GB maxiops)", plans.get(0).name);
        assertEquals("CLOUDNATIVE-4xCPU-8GB (4 cores, 8192 MB memory, no storage included)", plans.get(2).name);

        ListBoxModel images = descriptor.doFillImageItems("upcloud", "");
        assertEquals(List.of(FakeUpCloud.CUSTOM_IMAGE, FakeUpCloud.PUBLIC_IMAGE), values(images));
        assertEquals("Custom: jenkins-agent (fi-hel1, 20 GB)", images.get(0).name);
        // A configured image that is no longer on offer stays selectable.
        assertEquals(
                List.of(FakeUpCloud.CUSTOM_IMAGE, FakeUpCloud.PUBLIC_IMAGE, "deleted-image"),
                values(descriptor.doFillImageItems("upcloud", "deleted-image")));

        ListBoxModel networks = descriptor.doFillPrivateNetworkItems("upcloud", "");
        assertEquals(List.of("", FakeUpCloud.PRIVATE_NETWORK), values(networks));
        assertEquals("build-net (fi-hel1)", networks.get(1).name);
        assertEquals(List.of("", "ssh-password"), values(descriptor.doFillSshCredentialsIdItems("")));

        // Without usable credentials there is nothing to list, but the configured value is kept.
        assertEquals(List.of("", "fi-hel1"), values(descriptor.doFillZoneItems("", "fi-hel1")));
        assertEquals(List.of("", "fi-hel1"), values(descriptor.doFillZoneItems("missing", "fi-hel1")));
    }

    @Test
    void newTemplateOffersCustomImages() throws Exception {
        UpCloudCloud cloud = cloud();
        JenkinsRule.WebClient client = r.createWebClient();
        HtmlPage page = client.goTo(cloud.getUrl() + "configure");

        r.getButtonByCaption(page.getFormByName("config"), "Add template").click();
        client.waitForBackgroundJavaScript(10_000);

        HtmlSelect image = page.getElementByName("_.image");
        List<String> images =
                image.getOptions().stream().map(option -> option.getText()).toList();
        assertEquals(
                List.of("Custom: jenkins-agent (fi-hel1, 20 GB)", "Ubuntu Server 24.04 LTS (Noble Numbat)"), images);
        HtmlSelect network = page.getElementByName("_.privateNetwork");
        assertEquals(
                List.of("None", "build-net (fi-hel1)"),
                network.getOptions().stream().map(option -> option.getText()).toList());
    }

    @Test
    void validatesForm() {
        UpCloudCloud.DescriptorImpl cloud = r.jenkins.getDescriptorByType(UpCloudCloud.DescriptorImpl.class);
        assertEquals(List.of("", "ssh-password", "upcloud"), values(cloud.doFillCredentialsIdItems("")));
        assertEquals("Connected as jenkins", cloud.doTestConnection("upcloud").getMessage());
        assertEquals(FormValidation.Kind.ERROR, cloud.doTestConnection("ssh-password").kind);
        assertEquals(FormValidation.Kind.ERROR, cloud.doTestConnection("missing").kind);

        UpCloudAgentTemplate.DescriptorImpl template =
                r.jenkins.getDescriptorByType(UpCloudAgentTemplate.DescriptorImpl.class);
        assertEquals(FormValidation.Kind.OK, template.doCheckName("linux-x64").kind);
        assertEquals(FormValidation.Kind.ERROR, template.doCheckName("Linux").kind);
        assertEquals(FormValidation.Kind.ERROR, template.doCheckName("-linux").kind);
        assertEquals(FormValidation.Kind.ERROR, template.doCheckRemoteFs("jenkins").kind);
        // Jenkins can only connect over a network the server is attached to.
        assertEquals(FormValidation.Kind.OK, template.doCheckConnectionNetwork("PUBLIC", true, false, false, "").kind);
        assertEquals(
                FormValidation.Kind.ERROR, template.doCheckConnectionNetwork("PUBLIC", false, true, true, "net").kind);
        assertEquals(
                FormValidation.Kind.OK, template.doCheckConnectionNetwork("PUBLIC_IPV6", false, true, false, "").kind);
        assertEquals(
                FormValidation.Kind.ERROR,
                template.doCheckConnectionNetwork("PUBLIC_IPV6", true, false, true, "net").kind);
        assertEquals(FormValidation.Kind.OK, template.doCheckConnectionNetwork("UTILITY", false, false, true, "").kind);
        assertEquals(
                FormValidation.Kind.ERROR, template.doCheckConnectionNetwork("UTILITY", true, true, false, "net").kind);
        assertEquals(
                FormValidation.Kind.OK, template.doCheckConnectionNetwork("PRIVATE", false, false, false, "net").kind);
        assertEquals(
                FormValidation.Kind.ERROR, template.doCheckConnectionNetwork("PRIVATE", true, true, true, "").kind);

        // The account's own images and networks can only be used in the zone they are in.
        assertEquals(
                FormValidation.Kind.OK, template.doCheckImage("upcloud", FakeUpCloud.CUSTOM_IMAGE, "fi-hel1").kind);
        assertEquals(
                "This image is stored in zone fi-hel1 and can only be used for servers in that zone",
                template.doCheckImage("upcloud", FakeUpCloud.CUSTOM_IMAGE, "nl-ams1")
                        .getMessage());
        assertEquals(
                FormValidation.Kind.OK, template.doCheckImage("upcloud", FakeUpCloud.PUBLIC_IMAGE, "nl-ams1").kind);
        assertEquals(FormValidation.Kind.WARNING, template.doCheckImage("upcloud", "deleted-image", "nl-ams1").kind);
        assertEquals(
                FormValidation.Kind.OK,
                template.doCheckPrivateNetwork("upcloud", FakeUpCloud.PRIVATE_NETWORK, "fi-hel1").kind);
        assertEquals(
                FormValidation.Kind.ERROR,
                template.doCheckPrivateNetwork("upcloud", FakeUpCloud.PRIVATE_NETWORK, "nl-ams1").kind);
        assertEquals(FormValidation.Kind.OK, template.doCheckPrivateNetwork("upcloud", "", "nl-ams1").kind);

        assertThrows(
                IllegalArgumentException.class,
                () -> new UpCloudAgentTemplate("Not A Hostname", "", "fi-hel1", "2xCPU-4GB", "image", "ssh-password"));
    }

    @Test
    void configurationSurvivesTheForm() throws Exception {
        UpCloudAgentTemplate template = new UpCloudAgentTemplate(
                "linux", "linux java", "fi-hel1", "2xCPU-4GB", FakeUpCloud.CUSTOM_IMAGE, "ssh-password");
        template.setMode(Node.Mode.EXCLUSIVE);
        template.setNumExecutors(2);
        template.setRemoteFs("/var/jenkins");
        template.setStorageSizeGb(100);
        template.setStorageTier("standard");
        template.setUserData("#!/bin/sh\necho hello");
        template.setPublicIpv4(false);
        template.setPublicIpv6(true);
        template.setUtilityNetwork(false);
        template.setPrivateNetwork(FakeUpCloud.PRIVATE_NETWORK);
        template.setConnectionNetwork(ConnectionNetwork.PRIVATE);
        template.setSshPort(2222);
        template.setJavaPath("/opt/java/bin/java");
        template.setJvmOptions("-Xmx512m");
        template.setStartupTimeoutSeconds(300);
        template.setShutdownPolicy(new UpCloudShutdownPolicy.AfterIdleTime(30));
        template.setInstanceCap(5);
        template.setNodeProperties(List.of(
                new EnvironmentVariablesNodeProperty(new EnvironmentVariablesNodeProperty.Entry("CLOUD", "upcloud"))));
        UpCloudCloud cloud = new UpCloudCloud("upcloud", "upcloud");
        cloud.setInstanceCap(10);
        cloud.setNoDelayProvisioning(false);
        cloud.setCleanupOrphanedServers(false);
        UpCloudAgentTemplate other =
                new UpCloudAgentTemplate("other", "", "nl-ams1", "1xCPU-1GB", FakeUpCloud.PUBLIC_IMAGE, "ssh-password");
        other.setShutdownPolicy(new UpCloudShutdownPolicy.BeforeBillingHourEnds());
        cloud.setTemplates(List.of(template, other));
        r.jenkins.clouds.add(cloud);

        r.submit(r.createWebClient().goTo(cloud.getUrl() + "configure").getFormByName("config"));

        UpCloudCloud saved = (UpCloudCloud) r.jenkins.getCloud("upcloud");
        r.assertEqualDataBoundBeans(cloud, saved);
        r.assertEqualDataBoundBeans(cloud.getTemplates(), saved.getTemplates());
    }

    private static List<String> values(ListBoxModel items) {
        return items.stream().map(item -> item.value).toList();
    }
}
