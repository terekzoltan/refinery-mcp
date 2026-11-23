package refinerymcp.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import refinerymcp.Main;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class RefineryMcpServerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void normalModeExposesReadToolsAndCannotRunDiagnostics() {
        try (var client = connect(false)) {
            assertEquals(Set.of("get_server_info", "get_metamodel", "check_instance"), toolNames(client));
            var result = client.callTool(request("get_server_info", Map.of()));
            assertFalse(result.isError());
            var info = payload(result);
            assertEquals(client.getServerInfo().version(), info.get("server_version"));
            assertEquals("0.3.0", info.get("refinery_version"));
            assertEquals(false, info.get("diagnostics_enabled"));

            assertThrows(McpError.class, () -> client.callTool(request(
                    "run_refinery_diagnostic", Map.of("case_id", "valid"))));
            assertFalse(client.callTool(request("get_server_info", Map.of())).isError());
        }
    }

    @Test
    void metamodelReturnsExactSourceAndRejectsClientSelection() throws Exception {
        try (var client = connect(false)) {
            var tool = client.listTools().tools().stream()
                    .filter(candidate -> "get_metamodel".equals(candidate.name())).findFirst().orElseThrow();
            assertTrue(tool.annotations().readOnlyHint());
            assertTrue(tool.annotations().idempotentHint());
            assertFalse(tool.annotations().openWorldHint());

            var result = client.callTool(request("get_metamodel", Map.of()));
            assertFalse(result.isError());
            var metamodel = payload(result);
            assertEquals("catalog", metamodel.get("metamodel_id"));
            assertEquals("1.0.0", metamodel.get("metamodel_version"));
            String expectedSource;
            try (var input = getClass().getResourceAsStream("/metamodels/catalog.problem")) {
                assertNotNull(input);
                expectedSource = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
            assertEquals(expectedSource, metamodel.get("metamodel_source"));
            assertTrue(expectedSource.contains("Category[1] category"));
            assertTrue(expectedSource.contains("error categoryOutsideCatalog"));
            assertFalse(expectedSource.contains("Catalog(shop)"));
            var expectedHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(expectedSource.getBytes(StandardCharsets.UTF_8)));
            assertEquals(expectedHash, metamodel.get("source_sha256"));
            var textContent = assertInstanceOf(McpSchema.TextContent.class, result.content().getFirst());
            assertEquals(metamodel, McpJsonDefaults.getMapper().readValue(
                    textContent.text(), new TypeRef<Map<String, Object>>() {}));

            assertTrue(client.callTool(request("get_metamodel", Map.of("path", "../secret"))).isError());
            assertTrue(client.callTool(request("get_metamodel", Map.of("metamodel_id", "other"))).isError());
            var repeated = client.callTool(request("get_metamodel", Map.of()));
            assertFalse(repeated.isError());
            assertEquals(metamodel, payload(repeated));
        }
    }

    @Test
    void checksClosedInstancesAndReportsTheObjectsToRepair() {
        var objects = List.of(object("shop", "Catalog"), object("laptop", "Product"), object("book", "Product"),
                object("books", "Category"), object("electronics", "Category"));
        var links = new ArrayList<>(List.of(link("shop", "products", "laptop"), link("shop", "products", "book"),
                link("shop", "categories", "electronics"), link("shop", "categories", "books"),
                link("book", "category", "books"), link("laptop", "category", "electronics")));
        var erroneousLink = link("laptop", "category", "books");

        try (var client = connect(false)) {
            links.add(erroneousLink);
            var invalid = client.callTool(request("check_instance", Map.of("objects", objects, "links", links)));
            assertFalse(invalid.isError());
            assertEquals("INVALID", payload(invalid).get("status"));
            assertTrue(payload(invalid).get("diagnostics").toString().contains("invalidMultiplicity(laptop)"));

            links.remove(erroneousLink);
            var repaired = client.callTool(request("check_instance", Map.of("objects", objects, "links", links)));
            assertFalse(repaired.isError());
            assertEquals("VALID", payload(repaired).get("status"));

            // Leaving the book's required relation out must fail rather than silently completing the model.
            links.remove(link("book", "category", "books"));
            var missing = client.callTool(request("check_instance", Map.of("objects", objects, "links", links)));
            assertFalse(missing.isError());
            assertEquals("INVALID", payload(missing).get("status"));
            assertTrue(payload(missing).get("diagnostics").toString().contains("invalidMultiplicity(book)"));

            links.add(link("book", "category", "books"));
            links.remove(link("shop", "categories", "books"));
            var otherCatalog = new ArrayList<>(objects);
            otherCatalog.add(object("another", "Catalog"));
            links.add(link("another", "categories", "books"));
            var outside = client.callTool(request("check_instance", Map.of("objects", otherCatalog, "links", links)));
            assertFalse(outside.isError());
            assertEquals("INVALID", payload(outside).get("status"));
            assertTrue(payload(outside).get("diagnostics").toString().contains("categoryOutsideCatalog(book, books)"));

            assertTrue(client.callTool(request("check_instance", Map.of("objects", objects,
                    "links", List.of(link("shop", "products", "absent"))))).isError());
            assertTrue(client.callTool(request("check_instance", Map.of("objects",
                    List.of(object("shop). class Extra. Catalog(x", "Catalog")), "links", List.of()))).isError());
            var empty = client.callTool(request("check_instance", Map.of("objects", List.of(), "links", List.of())));
            assertFalse(empty.isError());
            assertEquals("VALID", payload(empty).get("status"));
            assertTrue(client.callTool(request("check_instance", Map.of("objects", List.of(object("x", "OtherType")),
                    "links", List.of()))).isError());
            assertTrue(client.callTool(request("check_instance", Map.of("objects", objects,
                    "links", List.of(), "metamodel_source", "class Replacement."))).isError());
        }
    }

    private static Map<String, String> object(String id, String type) {
        return Map.of("id", id, "type", type);
    }

    private static Map<String, String> link(String source, String reference, String target) {
        return Map.of("source", source, "reference", reference, "target", target);
    }

    @Test
    void diagnosticsCallTheRealSolverAndRecoverAfterBadArguments() {
        try (var client = connect(true)) {
            assertEquals(Set.of("get_server_info", "get_metamodel", "check_instance", "run_refinery_diagnostic"), toolNames(client));
            var valid = callDiagnostic(client, Map.of("case_id", "valid"));
            assertFalse(valid.isError());
            assertEquals("SUCCESS", payload(valid).get("status"));
            assertTrue(((String) payload(valid).get("model_source")).contains("category(laptop, electronics)."));

            var contradictory = callDiagnostic(client, Map.of("case_id", "contradictory"));
            assertFalse(contradictory.isError());
            assertEquals("UNSATISFIABLE", payload(contradictory).get("status"));
            assertEquals("", payload(contradictory).get("model_source"));

            assertTrue(callDiagnostic(client, Map.of()).isError());
            assertTrue(callDiagnostic(client, Map.of("case_id", "../metamodel")).isError());
            assertTrue(callDiagnostic(client, Map.of("case_id", 7)).isError());
            assertTrue(callDiagnostic(client, Map.of("case_id", "valid", "extra", true)).isError());
            assertEquals("SUCCESS", payload(callDiagnostic(client, Map.of("case_id", "valid"))).get("status"));
        }
    }

    @Test
    void stdoutContainsProtocolJsonAndServerExitsOnEofAfterHandshake() throws Exception {
        var process = startProcess();
        var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        try {
            var initialize = """
                    {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"eof-test","version":"1"}}}
                    """;
            process.getOutputStream().write(initialize.getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().flush();
            var response = McpJsonDefaults.getMapper().readValue(readLine(reader), new TypeRef<Map<String, Object>>() {});
            assertEquals("2.0", response.get("jsonrpc"));
            assertEquals(1, ((Number) response.get("id")).intValue());
            assertTrue(response.containsKey("result"));

            process.getOutputStream().close();
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Server must exit after client closes stdin");
            assertEquals(0, process.exitValue());
            assertNull(reader.readLine(), "No console banner or non-protocol output may follow");
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
            }
            reader.close();
        }
    }

    @Test
    void serverAlsoExitsWhenClientClosesInputImmediately() throws Exception {
        var process = startProcess();
        try {
            process.getOutputStream().close();
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue());
            assertEquals("", new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    private McpSyncClient connect(boolean diagnostics) {
        var parameters = ServerParameters.builder(javaCommand())
                .args(serverArguments(diagnostics).toArray(String[]::new)).build();
        var transport = new StdioClientTransport(parameters, McpJsonDefaults.getMapper());
        var client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(30)).build();
        try {
            client.initialize();
            return client;
        } catch (RuntimeException exception) {
            client.close();
            throw exception;
        }
    }

    private Process startProcess() throws IOException {
        var command = new ArrayList<String>();
        command.add(javaCommand());
        command.addAll(serverArguments(false));
        return new ProcessBuilder(command)
                .redirectError(temporaryDirectory.resolve("server-stderr.log").toFile()).start();
    }

    private static List<String> serverArguments(boolean diagnostics) {
        var arguments = new ArrayList<>(List.of("--enable-native-access=ALL-UNNAMED",
                "-cp", System.getProperty("app.runtimeClasspath"), Main.class.getName(), "--mcp"));
        if (diagnostics) {
            arguments.add("--diagnostics");
        }
        return arguments;
    }

    private static String javaCommand() {
        var executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable).toString();
    }

    private static Set<String> toolNames(McpSyncClient client) {
        return client.listTools().tools().stream().map(McpSchema.Tool::name).collect(Collectors.toSet());
    }

    private static McpSchema.CallToolResult callDiagnostic(McpSyncClient client, Map<String, Object> arguments) {
        return client.callTool(request("run_refinery_diagnostic", arguments));
    }

    private static McpSchema.CallToolRequest request(String toolName, Map<String, Object> arguments) {
        return McpSchema.CallToolRequest.builder(toolName).arguments(arguments).build();
    }

    private static Map<?, ?> payload(McpSchema.CallToolResult result) {
        return assertInstanceOf(Map.class, result.structuredContent());
    }

    private static String readLine(BufferedReader reader) throws Exception {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return reader.readLine();
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }).get(20, TimeUnit.SECONDS);
    }
}
