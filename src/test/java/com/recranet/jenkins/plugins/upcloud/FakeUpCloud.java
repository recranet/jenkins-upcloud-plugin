package com.recranet.jenkins.plugins.upcloud;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * An in-memory stand-in for the UpCloud API, answering in the shapes of the real one.
 */
public class FakeUpCloud implements AutoCloseable {

    public static final String TOKEN = "ucat_test";
    public static final String PUBLIC_IMAGE = "01000000-0000-4000-8000-000030240200";
    public static final String CUSTOM_IMAGE = "0d0a1b2c-0000-4000-8000-000000000001";
    public static final String PRIVATE_NETWORK = "03aa7245-0000-4000-8000-000000000002";

    private final HttpServer server;

    /** The servers that exist, by UUID. */
    public final Map<String, JsonObject> servers = new LinkedHashMap<>();

    /** The bodies of the create server requests received. */
    public final List<JsonObject> createRequests = new ArrayList<>();

    /** The paths requested, with their query strings. */
    public final List<String> requests = new ArrayList<>();

    /** Templates listed in addition to the two fixed images. */
    public int extraTemplates;

    /** Whether the template listing honours {@code limit} and {@code offset}. */
    public boolean paginate = true;

    /** The state new servers are in. */
    public String initialState = "started";

    /** When set, creating a server fails with this error code. */
    public String createError;

    public FakeUpCloud() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/1.3";
    }

    /** Adds a server as if it had been created earlier. */
    public synchronized String addServer(String title, Instant created, String instanceLabel) {
        String uuid = UUID.randomUUID().toString();
        JsonObject label = new JsonObject();
        label.addProperty("key", UpCloudCloud.INSTANCE_LABEL);
        label.addProperty("value", instanceLabel);
        JsonArray labels = new JsonArray();
        labels.add(label);
        JsonObject labelList = new JsonObject();
        labelList.add("label", labels);
        JsonObject existing = new JsonObject();
        existing.addProperty("uuid", uuid);
        existing.addProperty("title", title);
        existing.addProperty("hostname", title);
        existing.addProperty("state", "started");
        existing.addProperty("created", created.getEpochSecond());
        existing.add("labels", labelList);
        servers.put(uuid, existing);
        return uuid;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String response = respond(exchange);
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            if (body.length == 0) {
                exchange.sendResponseHeaders(204, -1);
            } else {
                exchange.sendResponseHeaders(exchange.getRequestMethod().equals("POST") ? 202 : 200, body.length);
                exchange.getResponseBody().write(body);
            }
        } catch (ApiError e) {
            JsonObject error = new JsonObject();
            error.addProperty("error_code", e.code);
            error.addProperty("error_message", e.getMessage());
            JsonObject wrapper = new JsonObject();
            wrapper.add("error", error);
            byte[] body = wrapper.toString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(e.status, body.length);
            exchange.getResponseBody().write(body);
        } finally {
            exchange.close();
        }
    }

    private synchronized String respond(HttpExchange exchange) throws IOException, ApiError {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath().substring("/1.3".length());
        Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
        requests.add(method + " " + path
                + (exchange.getRequestURI().getRawQuery() == null
                        ? ""
                        : "?" + exchange.getRequestURI().getRawQuery()));
        if (!("Bearer " + TOKEN).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
            throw new ApiError(401, "AUTHENTICATION_FAILED", "Authentication failed using the given credentials.");
        }
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

        if (method.equals("GET") && path.equals("/account")) {
            return "{\"account\":{\"username\":\"jenkins\",\"resource_limits\":{}}}";
        }
        if (method.equals("GET") && path.equals("/zone")) {
            return "{\"zones\":{\"zone\":["
                    + "{\"id\":\"fi-hel1\",\"description\":\"Helsinki #1\",\"public\":\"yes\"},"
                    + "{\"id\":\"nl-ams1\",\"description\":\"Amsterdam #1\",\"public\":\"yes\"}]}}";
        }
        if (method.equals("GET") && path.equals("/plan")) {
            return "{\"plans\":{\"plan\":["
                    + "{\"name\":\"2xCPU-4GB\",\"core_number\":2,\"memory_amount\":4096,\"storage_size\":80,"
                    + "\"storage_tier\":\"maxiops\",\"family\":\"general_purpose\",\"current_offering\":\"yes\"},"
                    + "{\"name\":\"1xCPU-1GB\",\"core_number\":1,\"memory_amount\":1024,\"storage_size\":25,"
                    + "\"storage_tier\":\"maxiops\",\"family\":\"general_purpose\",\"current_offering\":\"yes\"},"
                    + "{\"name\":\"CLOUDNATIVE-4xCPU-8GB\",\"core_number\":4,\"memory_amount\":8192,"
                    + "\"storage_tier\":null,\"family\":\"cloud_native\",\"current_offering\":\"yes\"}]}}";
        }
        if (method.equals("GET") && path.equals("/storage/template")) {
            List<JsonObject> templates = templates();
            JsonArray page = new JsonArray();
            int offset = paginate ? Integer.parseInt(query.get("offset")) : 0;
            int limit = paginate ? Integer.parseInt(query.get("limit")) : templates.size();
            templates.stream().skip(offset).limit(limit).forEach(page::add);
            return wrap("storages", "storage", page);
        }
        if (method.equals("GET") && path.startsWith("/storage/")) {
            String uuid = path.substring("/storage/".length());
            JsonObject storage = templates().stream()
                    .filter(template -> template.get("uuid").getAsString().equals(uuid))
                    .findFirst()
                    .orElseThrow(() -> new ApiError(404, "STORAGE_NOT_FOUND", "The storage does not exist."));
            JsonObject wrapper = new JsonObject();
            wrapper.add("storage", storage);
            return wrapper.toString();
        }
        if (method.equals("GET") && path.equals("/network")) {
            return "{\"networks\":{\"network\":["
                    + "{\"name\":\"Public fi-hel1\",\"type\":\"public\",\"uuid\":\"03000000-0000-4000-8000-000000000001\","
                    + "\"zone\":\"fi-hel1\"},"
                    + "{\"name\":\"build-net\",\"type\":\"private\",\"uuid\":\"" + PRIVATE_NETWORK
                    + "\",\"zone\":\"fi-hel1\"}]}}";
        }
        if (method.equals("POST") && path.equals("/server")) {
            if (createError != null) {
                throw new ApiError(409, createError, "The server cannot be created.");
            }
            JsonObject request = JsonParser.parseString(body).getAsJsonObject().getAsJsonObject("server");
            createRequests.add(request);
            JsonObject created = new JsonObject();
            created.addProperty("uuid", UUID.randomUUID().toString());
            created.addProperty("title", request.get("title").getAsString());
            created.addProperty("hostname", request.get("hostname").getAsString());
            created.addProperty("state", initialState);
            created.addProperty("created", Instant.now().getEpochSecond());
            created.add("labels", request.get("labels"));
            JsonArray addresses = new JsonArray();
            for (JsonElement element : request.getAsJsonObject("networking")
                    .getAsJsonObject("interfaces")
                    .getAsJsonArray("interface")) {
                String family = element.getAsJsonObject()
                        .getAsJsonObject("ip_addresses")
                        .getAsJsonArray("ip_address")
                        .get(0)
                        .getAsJsonObject()
                        .get("family")
                        .getAsString();
                JsonObject address = new JsonObject();
                address.addProperty(
                        "access", element.getAsJsonObject().get("type").getAsString());
                address.addProperty("family", family);
                address.addProperty("address", family.equals("IPv6") ? "::1" : "127.0.0.1");
                addresses.add(address);
            }
            JsonObject addressList = new JsonObject();
            addressList.add("ip_address", addresses);
            created.add("ip_addresses", addressList);
            servers.put(created.get("uuid").getAsString(), created);
            return server(created);
        }
        if (method.equals("GET") && path.equals("/server")) {
            String[] label = query.get("label").split("=", 2);
            JsonArray matching = new JsonArray();
            for (JsonObject candidate : servers.values()) {
                for (JsonElement element : candidate.getAsJsonObject("labels").getAsJsonArray("label")) {
                    JsonObject candidateLabel = element.getAsJsonObject();
                    if (candidateLabel.get("key").getAsString().equals(label[0])
                            && candidateLabel.get("value").getAsString().equals(label[1])) {
                        matching.add(candidate);
                    }
                }
            }
            return wrap("servers", "server", matching);
        }
        if (path.startsWith("/server/")) {
            String[] parts = path.substring("/server/".length()).split("/");
            JsonObject existing = servers.get(parts[0]);
            if (existing == null) {
                throw new ApiError(404, "SERVER_NOT_FOUND", "The server " + parts[0] + " does not exist.");
            }
            if (method.equals("GET") && parts.length == 1) {
                return server(existing);
            }
            if (method.equals("POST") && parts.length == 2 && parts[1].equals("stop")) {
                if (!existing.get("state").getAsString().equals("started")) {
                    throw new ApiError(
                            409, "SERVER_STATE_ILLEGAL", "The server is in a state in which it cannot be used.");
                }
                existing.addProperty("state", "stopped");
                return server(existing);
            }
            if (method.equals("DELETE") && parts.length == 1) {
                if (existing.get("state").getAsString().equals("started")) {
                    throw new ApiError(
                            409, "SERVER_STATE_ILLEGAL", "The server is in a state in which it cannot be used.");
                }
                if (!"1".equals(query.get("storages"))) {
                    throw new ApiError(400, "BAD_REQUEST", "The storages would be left behind.");
                }
                servers.remove(parts[0]);
                return "";
            }
        }
        throw new ApiError(404, "RESOURCE_NOT_FOUND", "Unknown resource " + method + " " + path);
    }

    private List<JsonObject> templates() {
        List<JsonObject> templates = new ArrayList<>();
        templates.add(template(PUBLIC_IMAGE, "Ubuntu Server 24.04 LTS (Noble Numbat)", "public", null, 4, "maxiops"));
        templates.add(template(CUSTOM_IMAGE, "jenkins-agent", "private", "fi-hel1", 20, "standard"));
        for (int i = 0; i < extraTemplates; i++) {
            templates.add(template(
                    String.format("0e000000-0000-4000-8000-%012d", i),
                    "Extra " + i,
                    "private",
                    "nl-ams1",
                    10,
                    "maxiops"));
        }
        return templates;
    }

    private static JsonObject template(String uuid, String title, String access, String zone, int size, String tier) {
        JsonObject template = new JsonObject();
        template.addProperty("uuid", uuid);
        template.addProperty("title", title);
        template.addProperty("access", access);
        template.addProperty("type", "template");
        template.addProperty("state", "online");
        template.addProperty("size", size);
        template.addProperty("tier", tier);
        if (zone != null) {
            template.addProperty("zone", zone);
        }
        return template;
    }

    private static String server(JsonObject server) {
        JsonObject wrapper = new JsonObject();
        wrapper.add("server", server);
        return wrapper.toString();
    }

    private static String wrap(String outer, String inner, JsonArray items) {
        JsonObject list = new JsonObject();
        list.add(inner, items);
        JsonObject wrapper = new JsonObject();
        wrapper.add(outer, list);
        return wrapper.toString();
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> query = new LinkedHashMap<>();
        if (raw != null) {
            for (String pair : raw.split("&")) {
                String[] parts = pair.split("=", 2);
                query.put(parts[0], URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
            }
        }
        return query;
    }

    private static class ApiError extends Exception {
        private static final long serialVersionUID = 1L;
        private final int status;
        private final String code;

        ApiError(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }
    }
}
