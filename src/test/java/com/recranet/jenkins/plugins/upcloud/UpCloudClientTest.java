package com.recranet.jenkins.plugins.upcloud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.recranet.jenkins.plugins.upcloud.client.Plan;
import com.recranet.jenkins.plugins.upcloud.client.Server;
import com.recranet.jenkins.plugins.upcloud.client.ServerSpec;
import com.recranet.jenkins.plugins.upcloud.client.Storage;
import com.recranet.jenkins.plugins.upcloud.client.UpCloudClient;
import com.recranet.jenkins.plugins.upcloud.client.UpCloudException;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UpCloudClientTest {

    private FakeUpCloud api;
    private UpCloudClient client;

    @BeforeEach
    void setUp() throws Exception {
        api = new FakeUpCloud();
        client = new UpCloudClient(api.url(), UpCloudClient.bearer(FakeUpCloud.TOKEN));
    }

    @AfterEach
    void tearDown() {
        api.close();
    }

    @Test
    void readsAccountZonesAndPlans() throws Exception {
        assertEquals("jenkins", client.getAccountUsername());
        assertEquals(
                List.of("fi-hel1", "nl-ams1"),
                client.listZones().stream().map(zone -> zone.id()).toList());

        List<Plan> plans = client.listPlans();
        assertEquals(new Plan("2xCPU-4GB", 2, 4096, 80, "maxiops"), plans.get(0));
        assertEquals(new Plan("CLOUDNATIVE-4xCPU-8GB", 4, 8192, 0, null), plans.get(2));
    }

    @Test
    void readsTemplates() throws Exception {
        List<Storage> templates = client.listTemplates();

        assertEquals(2, templates.size());
        assertTrue(templates.get(0).isPublic());
        assertNull(templates.get(0).zone());
        assertEquals(
                new Storage(FakeUpCloud.CUSTOM_IMAGE, "jenkins-agent", "private", "fi-hel1", 20, "standard"),
                templates.get(1));
        assertEquals(20, client.getStorage(FakeUpCloud.CUSTOM_IMAGE).sizeGb());
    }

    @Test
    void readsEveryPageOfTemplates() throws Exception {
        api.extraTemplates = 250;

        List<Storage> templates = client.listTemplates();

        assertEquals(252, templates.size());
        assertEquals(
                List.of(
                        "GET /storage/template?limit=100&offset=0",
                        "GET /storage/template?limit=100&offset=100",
                        "GET /storage/template?limit=100&offset=200"),
                api.requests);
    }

    @Test
    void readsTemplatesOnceWhenPagingIsIgnored() throws Exception {
        api.extraTemplates = 150;
        api.paginate = false;

        List<Storage> templates = client.listTemplates();

        assertEquals(152, templates.size());
        assertEquals(152, new HashSet<>(templates).size());
    }

    @Test
    void listsOnlyPrivateNetworks() throws Exception {
        assertEquals(
                List.of(FakeUpCloud.PRIVATE_NETWORK),
                client.listPrivateNetworks().stream()
                        .map(network -> network.uuid())
                        .toList());
    }

    @Test
    void createsServer() throws Exception {
        Server server = client.createServer(new ServerSpec(
                "linux-1234",
                "fi-hel1",
                "2xCPU-4GB",
                FakeUpCloud.CUSTOM_IMAGE,
                80,
                "maxiops",
                "#!/bin/sh\napt-get install -y default-jre-headless",
                "jenkins",
                "ssh-ed25519 AAAA",
                true,
                true,
                true,
                FakeUpCloud.PRIVATE_NETWORK,
                Map.of("jenkins-instance", "abc")));

        assertEquals("linux-1234", server.title());
        assertEquals(Server.STATE_STARTED, server.state());
        assertEquals("127.0.0.1", server.address("public", "IPv4"));
        assertEquals("::1", server.address("public", "IPv6"));
        assertEquals("127.0.0.1", server.address("private", "IPv4"));
        assertNull(server.address("private", "IPv6"));
        assertEquals(server, client.getServer(server.uuid()));

        JsonObject request = api.createRequests.get(0);
        assertEquals("linux-1234", request.get("hostname").getAsString());
        assertEquals("fi-hel1", request.get("zone").getAsString());
        assertEquals("2xCPU-4GB", request.get("plan").getAsString());
        assertEquals("yes", request.get("metadata").getAsString());
        assertEquals("none", request.get("password_delivery").getAsString());
        assertEquals(
                "{\"storage_device\":[{\"action\":\"clone\",\"storage\":\"" + FakeUpCloud.CUSTOM_IMAGE
                        + "\",\"title\":\"linux-1234\",\"size\":80,\"tier\":\"maxiops\"}]}",
                request.get("storage_devices").toString());
        assertEquals(
                "{\"interfaces\":{\"interface\":["
                        + "{\"type\":\"public\",\"ip_addresses\":{\"ip_address\":[{\"family\":\"IPv4\"}]}},"
                        + "{\"type\":\"utility\",\"ip_addresses\":{\"ip_address\":[{\"family\":\"IPv4\"}]}},"
                        + "{\"type\":\"public\",\"ip_addresses\":{\"ip_address\":[{\"family\":\"IPv6\"}]}},"
                        + "{\"type\":\"private\",\"ip_addresses\":{\"ip_address\":[{\"family\":\"IPv4\"}]},"
                        + "\"network\":\"" + FakeUpCloud.PRIVATE_NETWORK + "\"}]}}",
                request.get("networking").toString());
        assertEquals(
                "{\"username\":\"jenkins\",\"create_password\":\"no\",\"ssh_keys\":{\"ssh_key\":[\"ssh-ed25519 AAAA\"]}}",
                request.get("login_user").toString());
        assertEquals(
                "#!/bin/sh\napt-get install -y default-jre-headless",
                request.get("user_data").getAsString());
        assertEquals(
                "{\"label\":[{\"key\":\"jenkins-instance\",\"value\":\"abc\"}]}",
                request.get("labels").toString());
    }

    @Test
    void createsServerWithoutOptionalParts() throws Exception {
        client.createServer(new ServerSpec(
                "linux-1234",
                "fi-hel1",
                "2xCPU-4GB",
                FakeUpCloud.PUBLIC_IMAGE,
                80,
                null,
                null,
                null,
                null,
                false,
                false,
                true,
                null,
                Map.of()));

        JsonObject request = api.createRequests.get(0);
        assertFalse(request.has("login_user"));
        assertFalse(request.has("user_data"));
        assertFalse(request.getAsJsonObject("storage_devices").toString().contains("tier"));
        assertEquals(
                "{\"interfaces\":{\"interface\":["
                        + "{\"type\":\"utility\",\"ip_addresses\":{\"ip_address\":[{\"family\":\"IPv4\"}]}}]}}",
                request.get("networking").toString());
    }

    @Test
    void listsServersByLabel() throws Exception {
        String mine = api.addServer("mine", Instant.now(), "abc");
        api.addServer("theirs", Instant.now(), "xyz");

        List<Server> servers = client.listServers("jenkins-instance", "abc");

        assertEquals(List.of(mine), servers.stream().map(Server::uuid).toList());
        assertEquals("GET /server?label=jenkins-instance%3Dabc&limit=100&offset=0", api.requests.get(0));
    }

    @Test
    void destroysStartedServer() throws Exception {
        String uuid = api.addServer("agent", Instant.now(), "abc");

        client.destroyServer(uuid, Instant.now().plusSeconds(30));

        assertTrue(api.servers.isEmpty());
        assertTrue(api.requests.contains("POST /server/" + uuid + "/stop"));
        assertTrue(api.requests.contains("DELETE /server/" + uuid + "?storages=1&backups=delete"));
    }

    @Test
    void destroyingMissingServerSucceeds() throws Exception {
        client.destroyServer(
                "00000000-0000-4000-8000-000000000000", Instant.now().plusSeconds(30));
    }

    @Test
    void destroyingGivesUpOnServerThatNeverStarts() throws Exception {
        String uuid = api.addServer("agent", Instant.now(), "abc");
        api.servers.get(uuid).addProperty("state", "maintenance");

        assertThrows(Exception.class, () -> client.destroyServer(uuid, Instant.now()));
        assertEquals(1, api.servers.size());
    }

    @Test
    void waitingFailsOnServerInErrorState() throws Exception {
        String uuid = api.addServer("agent", Instant.now(), "abc");
        api.servers.get(uuid).addProperty("state", "error");

        assertThrows(
                Exception.class,
                () -> client.waitForServerState(
                        uuid, Server.STATE_STARTED, Instant.now().plusSeconds(30)));
    }

    @Test
    void reportsApiErrors() {
        UpCloudClient unauthorized = new UpCloudClient(api.url(), UpCloudClient.bearer("wrong"));

        UpCloudException e = assertThrows(UpCloudException.class, unauthorized::getAccountUsername);

        assertEquals(401, e.getStatusCode());
        assertEquals("AUTHENTICATION_FAILED", e.getErrorCode());
        assertEquals("UpCloud API: Authentication failed using the given credentials.", e.getMessage());
    }

    @Test
    void encodesBasicCredentials() {
        assertEquals("Basic amVua2luczpzM2NyZXQ=", UpCloudClient.basic("jenkins", "s3cret"));
    }
}
