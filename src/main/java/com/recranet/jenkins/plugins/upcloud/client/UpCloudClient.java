package com.recranet.jenkins.plugins.upcloud.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.ProxyConfiguration;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * Client for the parts of the <a href="https://developers.upcloud.com/1.3/">UpCloud API</a> this plugin needs.
 */
public class UpCloudClient {

    private static final Logger LOGGER = Logger.getLogger(UpCloudClient.class.getName());

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(3);
    private static final int PAGE_SIZE = 100;

    private final String apiUrl;
    private final String authorization;
    private final HttpClient http;

    /**
     * @param apiUrl base URL including the API version, without a trailing slash
     * @param authorization value of the {@code Authorization} header, see {@link #bearer} and {@link #basic}
     */
    public UpCloudClient(String apiUrl, String authorization) {
        this.apiUrl = apiUrl;
        this.authorization = authorization;
        this.http = ProxyConfiguration.newHttpClientBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    public static String bearer(String token) {
        return "Bearer " + token;
    }

    public static String basic(String username, String password) {
        return "Basic "
                + Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    /** Returns the username of the authenticated account. */
    public String getAccountUsername() throws IOException, InterruptedException {
        return string(object(get("/account"), "account"), "username");
    }

    public List<Zone> listZones() throws IOException, InterruptedException {
        return list(
                object(get("/zone"), "zones"),
                "zone",
                zone -> new Zone(string(zone, "id"), string(zone, "description")));
    }

    public List<Plan> listPlans() throws IOException, InterruptedException {
        return list(object(get("/plan"), "plans"), "plan", UpCloudClient::plan);
    }

    /** Lists the storage templates of the account together with the public ones provided by UpCloud. */
    public List<Storage> listTemplates() throws IOException, InterruptedException {
        return paginated("/storage/template", "storages", "storage", UpCloudClient::storage, Storage::uuid);
    }

    public Storage getStorage(String uuid) throws IOException, InterruptedException {
        return storage(object(get("/storage/" + uuid), "storage"));
    }

    /** Lists the SDN private networks of the account, in every zone. */
    public List<Network> listPrivateNetworks() throws IOException, InterruptedException {
        List<Network> networks = new ArrayList<>();
        JsonObject response = get("/network");
        for (JsonElement element : array(object(response, "networks"), "network")) {
            JsonObject network = element.getAsJsonObject();
            if ("private".equals(string(network, "type"))) {
                networks.add(new Network(string(network, "uuid"), string(network, "name"), string(network, "zone")));
            }
        }
        return networks;
    }

    /**
     * Starts creating a server. The server is in {@code maintenance} state until UpCloud has deployed it, see
     * {@link #waitForServerState}.
     */
    public Server createServer(ServerSpec spec) throws IOException, InterruptedException {
        JsonObject server = new JsonObject();
        server.addProperty("title", spec.name());
        server.addProperty("hostname", spec.name());
        server.addProperty("zone", spec.zone());
        server.addProperty("plan", spec.plan());
        // The metadata service is what delivers user data and SSH keys to cloud-init based templates.
        server.addProperty("metadata", "yes");
        // By default UpCloud e-mails a root password to the account owner for every server created.
        server.addProperty("password_delivery", "none");

        JsonObject disk = new JsonObject();
        disk.addProperty("action", "clone");
        disk.addProperty("storage", spec.storage());
        disk.addProperty("title", spec.name());
        disk.addProperty("size", spec.storageSizeGb());
        if (spec.storageTier() != null) {
            disk.addProperty("tier", spec.storageTier());
        }
        server.add("storage_devices", wrap("storage_device", disk));

        JsonArray interfaces = new JsonArray();
        if (spec.publicIpv4()) {
            interfaces.add(networkInterface("public", "IPv4", null));
        }
        if (spec.utilityNetwork()) {
            interfaces.add(networkInterface("utility", "IPv4", null));
        }
        if (spec.publicIpv6()) {
            interfaces.add(networkInterface("public", "IPv6", null));
        }
        if (spec.privateNetwork() != null) {
            interfaces.add(networkInterface("private", "IPv4", spec.privateNetwork()));
        }
        JsonObject interfaceList = new JsonObject();
        interfaceList.add("interface", interfaces);
        JsonObject networking = new JsonObject();
        networking.add("interfaces", interfaceList);
        server.add("networking", networking);

        if (spec.loginUser() != null) {
            JsonObject loginUser = new JsonObject();
            loginUser.addProperty("username", spec.loginUser());
            loginUser.addProperty("create_password", "no");
            JsonArray keys = new JsonArray();
            keys.add(spec.sshPublicKey());
            JsonObject sshKeys = new JsonObject();
            sshKeys.add("ssh_key", keys);
            loginUser.add("ssh_keys", sshKeys);
            server.add("login_user", loginUser);
        }
        if (spec.userData() != null) {
            server.addProperty("user_data", spec.userData());
        }

        JsonArray labels = new JsonArray();
        spec.labels().forEach((key, value) -> {
            JsonObject label = new JsonObject();
            label.addProperty("key", key);
            label.addProperty("value", value);
            labels.add(label);
        });
        JsonObject labelList = new JsonObject();
        labelList.add("label", labels);
        server.add("labels", labelList);

        JsonObject body = new JsonObject();
        body.add("server", server);
        return server(object(send("POST", "/server", body), "server"));
    }

    public Server getServer(String uuid) throws IOException, InterruptedException {
        return server(object(get("/server/" + uuid), "server"));
    }

    /** Lists the servers carrying a label. The returned servers have no addresses. */
    public List<Server> listServers(String labelKey, String labelValue) throws IOException, InterruptedException {
        return paginated(
                "/server?label=" + encode(labelKey + "=" + labelValue),
                "servers",
                "server",
                UpCloudClient::server,
                Server::uuid);
    }

    /** Powers a started server off without shutting its operating system down. */
    public void stopServer(String uuid) throws IOException, InterruptedException {
        JsonObject stop = new JsonObject();
        stop.addProperty("stop_type", "hard");
        JsonObject body = new JsonObject();
        body.add("stop_server", stop);
        send("POST", "/server/" + uuid + "/stop", body);
    }

    /** Deletes a stopped server together with its storages and their backups. */
    public void deleteServer(String uuid) throws IOException, InterruptedException {
        send("DELETE", "/server/" + uuid + "?storages=1&backups=delete", null);
    }

    /**
     * Stops a server if needed and deletes it together with its storages. A server that no longer exists counts
     * as destroyed.
     */
    public void destroyServer(String uuid, Instant deadline) throws IOException, InterruptedException {
        try {
            boolean stopRequested = false;
            while (true) {
                String state = getServer(uuid).state();
                if (state.equals(Server.STATE_STOPPED) || state.equals(Server.STATE_ERROR)) {
                    break;
                }
                // A server can only be stopped once it has started; until then it is still being deployed.
                if (state.equals(Server.STATE_STARTED) && !stopRequested) {
                    stopServer(uuid);
                    stopRequested = true;
                }
                if (Instant.now().isAfter(deadline)) {
                    throw new IOException("Timed out waiting for server " + uuid + " to stop, it is " + state);
                }
                Thread.sleep(POLL_INTERVAL.toMillis());
            }
            deleteServer(uuid);
        } catch (UpCloudException e) {
            if (!e.isNotFound()) {
                throw e;
            }
        }
    }

    /**
     * Polls a server until it reaches a state.
     *
     * @throws IOException when the server enters the {@code error} state or the deadline passes
     */
    public Server waitForServerState(String uuid, String state, Instant deadline)
            throws IOException, InterruptedException {
        while (true) {
            Server server = getServer(uuid);
            if (server.state().equals(state)) {
                return server;
            }
            if (server.state().equals(Server.STATE_ERROR)) {
                throw new IOException("Server " + uuid + " is in error state");
            }
            if (Instant.now().isAfter(deadline)) {
                throw new IOException(
                        "Timed out waiting for server " + uuid + " to be " + state + ", it is " + server.state());
            }
            Thread.sleep(POLL_INTERVAL.toMillis());
        }
    }

    private JsonObject get(String path) throws IOException, InterruptedException {
        return send("GET", path, null);
    }

    private JsonObject send(String method, String path, @CheckForNull JsonObject body)
            throws IOException, InterruptedException {
        HttpRequest request = ProxyConfiguration.newHttpRequestBuilder(URI.create(apiUrl + path))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", authorization)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("User-Agent", "jenkins-upcloud-cloud-plugin")
                .method(
                        method,
                        body == null
                                ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        // Bodies are left out: they hold init scripts and, for new servers, generated passwords.
        LOGGER.fine(() -> method + " " + path + " -> " + response.statusCode());
        if (response.statusCode() >= 400) {
            throw error(response);
        }
        if (response.body().isBlank()) {
            return new JsonObject();
        }
        try {
            return JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            throw new IOException("UpCloud API returned an unreadable response to " + method + " " + path, e);
        }
    }

    private static UpCloudException error(HttpResponse<String> response) {
        String code = "";
        String message = "HTTP " + response.statusCode();
        try {
            JsonElement error =
                    JsonParser.parseString(response.body()).getAsJsonObject().get("error");
            if (error != null && error.isJsonObject()) {
                JsonElement errorCode = error.getAsJsonObject().get("error_code");
                JsonElement errorMessage = error.getAsJsonObject().get("error_message");
                if (errorCode != null) {
                    code = errorCode.getAsString();
                }
                if (errorMessage != null) {
                    message = errorMessage.getAsString();
                }
            }
        } catch (JsonParseException | IllegalStateException e) {
            // Not an API error body, e.g. an error page from a proxy: the status code is all there is to report.
        }
        return new UpCloudException(response.statusCode(), code, "UpCloud API: " + message);
    }

    /**
     * Collects every page of a list endpoint. Stops when a page brings nothing new, so an endpoint that ignores
     * the paging parameters and returns everything at once is read exactly once.
     */
    private <T> List<T> paginated(
            String path, String wrapper, String name, Parser<T> parser, Function<T, String> identity)
            throws IOException, InterruptedException {
        Map<String, T> items = new LinkedHashMap<>();
        String separator = path.contains("?") ? "&" : "?";
        while (true) {
            int before = items.size();
            JsonObject response = get(path + separator + "limit=" + PAGE_SIZE + "&offset=" + before);
            List<T> page = list(object(response, wrapper), name, parser);
            for (T item : page) {
                items.putIfAbsent(identity.apply(item), item);
            }
            if (page.size() < PAGE_SIZE || items.size() == before) {
                return new ArrayList<>(items.values());
            }
        }
    }

    private static Plan plan(JsonObject plan) throws IOException {
        JsonElement storageSize = plan.get("storage_size");
        JsonElement storageTier = plan.get("storage_tier");
        return new Plan(
                string(plan, "name"),
                element(plan, "core_number").getAsInt(),
                element(plan, "memory_amount").getAsInt(),
                storageSize == null ? 0 : storageSize.getAsInt(),
                storageTier == null || storageTier.isJsonNull() ? null : storageTier.getAsString());
    }

    private static Storage storage(JsonObject storage) throws IOException {
        JsonElement zone = storage.get("zone");
        JsonElement tier = storage.get("tier");
        return new Storage(
                string(storage, "uuid"),
                string(storage, "title"),
                string(storage, "access"),
                zone == null || zone.getAsString().isEmpty() ? null : zone.getAsString(),
                element(storage, "size").getAsInt(),
                tier == null || tier.isJsonNull() ? null : tier.getAsString());
    }

    private static Server server(JsonObject server) throws IOException {
        List<Server.Address> addresses = new ArrayList<>();
        JsonElement ipAddresses = server.get("ip_addresses");
        if (ipAddresses != null) {
            for (JsonElement element : array(ipAddresses.getAsJsonObject(), "ip_address")) {
                JsonObject address = element.getAsJsonObject();
                // An address that is still being assigned is listed without its value.
                if (address.has("address")) {
                    addresses.add(new Server.Address(
                            string(address, "access"), string(address, "family"), string(address, "address")));
                }
            }
        }
        return new Server(
                string(server, "uuid"),
                string(server, "title"),
                string(server, "state"),
                Instant.ofEpochSecond(element(server, "created").getAsLong()),
                addresses);
    }

    private static JsonObject networkInterface(String type, String family, @CheckForNull String network) {
        JsonObject address = new JsonObject();
        address.addProperty("family", family);
        JsonObject networkInterface = new JsonObject();
        networkInterface.addProperty("type", type);
        networkInterface.add("ip_addresses", wrap("ip_address", address));
        if (network != null) {
            networkInterface.addProperty("network", network);
        }
        return networkInterface;
    }

    private static JsonObject wrap(String name, JsonObject item) {
        JsonArray array = new JsonArray();
        array.add(item);
        JsonObject wrapper = new JsonObject();
        wrapper.add(name, array);
        return wrapper;
    }

    private static <T> List<T> list(JsonObject wrapper, String name, Parser<T> parser) throws IOException {
        List<T> items = new ArrayList<>();
        for (JsonElement element : array(wrapper, name)) {
            items.add(parser.parse(element.getAsJsonObject()));
        }
        return items;
    }

    private static JsonElement element(JsonObject object, String name) throws IOException {
        JsonElement element = object.get(name);
        if (element == null || element.isJsonNull()) {
            throw new IOException("UpCloud API response has no '" + name + "'");
        }
        return element;
    }

    private static JsonObject object(JsonObject object, String name) throws IOException {
        return element(object, name).getAsJsonObject();
    }

    private static JsonArray array(JsonObject object, String name) throws IOException {
        return element(object, name).getAsJsonArray();
    }

    private static String string(JsonObject object, String name) throws IOException {
        return element(object, name).getAsString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    @FunctionalInterface
    private interface Parser<T> {
        T parse(JsonObject object) throws IOException;
    }
}
