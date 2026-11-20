package com.recranet.jenkins.plugins.upcloud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.recranet.jenkins.plugins.upcloud.UpCloudAgentTemplate.ConnectionNetwork;
import hudson.model.Node;
import hudson.slaves.EnvironmentVariablesNodeProperty;
import io.jenkins.plugins.casc.ConfigurationContext;
import io.jenkins.plugins.casc.ConfiguratorRegistry;
import io.jenkins.plugins.casc.misc.ConfiguredWithCode;
import io.jenkins.plugins.casc.misc.JenkinsConfiguredWithCodeRule;
import io.jenkins.plugins.casc.misc.Util;
import io.jenkins.plugins.casc.misc.junit.jupiter.WithJenkinsConfiguredWithCode;
import io.jenkins.plugins.casc.model.CNode;
import org.junit.jupiter.api.Test;

@WithJenkinsConfiguredWithCode
class ConfigurationAsCodeTest {

    @Test
    @ConfiguredWithCode("configuration-as-code.yml")
    void configuresCloud(JenkinsConfiguredWithCodeRule r) {
        UpCloudCloud cloud = (UpCloudCloud) r.jenkins.getCloud("upcloud");
        assertEquals("upcloud-api", cloud.getCredentialsId());
        assertEquals(10, cloud.getInstanceCap());
        assertFalse(cloud.isNoDelayProvisioning());
        assertTrue(cloud.isCleanupOrphanedServers());
        assertEquals(3, cloud.getTemplates().size());
        assertTrue(
                cloud.getTemplate("hourly").getShutdownPolicy() instanceof UpCloudShutdownPolicy.BeforeBillingHourEnds);

        UpCloudAgentTemplate full = cloud.getTemplate("linux");
        assertEquals("linux docker", full.getLabelString());
        assertEquals("fi-hel1", full.getZone());
        assertEquals("2xCPU-4GB", full.getPlan());
        assertEquals("01000000-0000-4000-8000-000030240200", full.getImage());
        assertEquals("agent-ssh", full.getSshCredentialsId());
        assertEquals(Node.Mode.EXCLUSIVE, full.getMode());
        assertEquals(2, full.getNumExecutors());
        assertEquals("/var/jenkins", full.getRemoteFs());
        assertEquals(100, full.getStorageSizeGb());
        assertEquals("standard", full.getStorageTier());
        assertEquals("#!/bin/sh\napt-get update\napt-get install -y default-jre-headless\n", full.getUserData() + "\n");
        assertFalse(full.isPublicIpv4());
        assertTrue(full.isPublicIpv6());
        assertFalse(full.isUtilityNetwork());
        assertEquals("03aa7245-0000-4000-8000-000000000002", full.getPrivateNetwork());
        assertEquals(ConnectionNetwork.PRIVATE, full.getConnectionNetwork());
        assertEquals(2222, full.getSshPort());
        assertEquals("/opt/java/bin/java", full.getJavaPath());
        assertEquals("-Xmx512m", full.getJvmOptions());
        assertEquals(300, full.getStartupTimeoutSeconds());
        assertEquals(30, ((UpCloudShutdownPolicy.AfterIdleTime) full.getShutdownPolicy()).getIdleMinutes());
        assertEquals(5, full.getInstanceCap());
        assertEquals(1, full.getNodeProperties().size());
        assertEquals(
                "upcloud",
                full.getNodeProperties()
                        .get(EnvironmentVariablesNodeProperty.class)
                        .getEnvVars()
                        .get("CLOUD"));

        UpCloudAgentTemplate minimal = cloud.getTemplate("minimal");
        assertEquals(Node.Mode.NORMAL, minimal.getMode());
        assertEquals(1, minimal.getNumExecutors());
        assertEquals("/home/jenkins", minimal.getRemoteFs());
        assertEquals(0, minimal.getStorageSizeGb());
        assertNull(minimal.getStorageTier());
        assertNull(minimal.getUserData());
        assertTrue(minimal.isPublicIpv4());
        assertFalse(minimal.isPublicIpv6());
        assertTrue(minimal.isUtilityNetwork());
        assertEquals(ConnectionNetwork.PUBLIC, minimal.getConnectionNetwork());
        assertEquals(22, minimal.getSshPort());
        assertEquals(600, minimal.getStartupTimeoutSeconds());
        assertEquals(10, ((UpCloudShutdownPolicy.AfterIdleTime) minimal.getShutdownPolicy()).getIdleMinutes());
    }

    @Test
    @ConfiguredWithCode("configuration-as-code.yml")
    void exportsCloud(JenkinsConfiguredWithCodeRule r) throws Exception {
        ConfigurationContext context = new ConfigurationContext(ConfiguratorRegistry.get());
        CNode clouds = Util.getJenkinsRoot(context).get("clouds");

        assertEquals(Util.toStringFromYamlFile(this, "configuration-as-code-export.yml"), Util.toYamlString(clouds));
    }
}
