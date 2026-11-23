package refinerymcp.mcp;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import refinerymcp.diagnostics.CatalogExamples;
import refinerymcp.model.MetamodelDefinition;
import refinerymcp.refinery.RefineryRunner;
import refinerymcp.refinery.CompleteInstanceChecker;
import tools.refinery.generator.InvalidProblemException;
import tools.refinery.generator.standalone.StandaloneRefinery;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public final class RefineryMcpServer {
    private static final Logger LOG = LoggerFactory.getLogger(RefineryMcpServer.class);

    private final boolean diagnosticsEnabled;
    private final MetamodelDefinition metamodel;
    private CompleteInstanceChecker instanceChecker;
    private final McpJsonMapper mapper = McpJsonDefaults.getMapper();
    private final Properties buildInfo = new Properties();

    public RefineryMcpServer(boolean diagnosticsEnabled) throws IOException {
        this.diagnosticsEnabled = diagnosticsEnabled;
        this.metamodel = MetamodelDefinition.loadSelected();
        try (var input = getClass().getResourceAsStream("/server-info.properties")) {
            if (input == null) {
                throw new IOException("Missing generated server-info.properties");
            }
            buildInfo.load(input);
        }
    }

    public McpSyncServer start() {
        var transport = new StdioServerTransportProvider(mapper);
        var builder = McpServer.sync(transport)
                .serverInfo(buildInfo.getProperty("server.name"), buildInfo.getProperty("server.version"))
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .toolCall(McpSchema.Tool.builder("get_server_info", Map.of(
                                "type", "object", "properties", Map.of(), "additionalProperties", false))
                        .description("Return server, Java and Refinery versions and whether diagnostic tools are enabled.")
                        .build(), (exchange, request) -> serverInfo())
                .toolCall(McpSchema.Tool.builder("get_metamodel", Map.of(
                                "type", "object", "properties", Map.of(), "additionalProperties", false))
                        .description("Read the server-selected metamodel ID, version, exact Refinery source and its "
                                + "UTF-8 SHA-256 digest. Use this to learn the types, references and constraints "
                                + "before proposing instances. Does not run the solver or modify the metamodel. "
                                + "Accepts no arguments, file paths or model text.")
                        .annotations(McpSchema.ToolAnnotations.builder()
                                .readOnlyHint(true).destructiveHint(false)
                                .idempotentHint(true).openWorldHint(false).build())
                        .build(), (exchange, request) -> metamodelInfo())
                .toolCall(McpSchema.Tool.builder("check_instance", CompleteInstanceChecker.inputSchema())
                        .description("Check a complete object/link snapshot against the selected metamodel. "
                                + "Supply all typed objects and all true links; omitted links are false. "
                                + "Returns VALID or INVALID with Refinery diagnostics. Correct invalid candidates "
                                + "by preserving all required objects and valid links and resubmitting the full snapshot.")
                        .annotations(McpSchema.ToolAnnotations.builder().readOnlyHint(true)
                                .idempotentHint(true).openWorldHint(false).build())
                        .build(), (exchange, request) -> checkInstance(request.arguments()));

        if (diagnosticsEnabled) {
            var schema = Map.<String, Object>of(
                    "type", "object",
                    "properties", Map.of("case_id", Map.of(
                            "type", "string", "enum", List.of("valid", "contradictory"))),
                    "required", List.of("case_id"),
                    "additionalProperties", false);
            builder.toolCall(McpSchema.Tool.builder("run_refinery_diagnostic", schema)
                    .description("Run a bundled valid or contradictory example with the real Refinery solver. "
                            + "SUCCESS means generation succeeded; UNSATISFIABLE means no model satisfies the input. "
                            + "This diagnostic accepts no user model text or file paths.")
                    .build(), (exchange, request) -> runDiagnostic(request.arguments()));
        }

        // The SDK's stdio threads keep the process alive and stop when the client closes stdin.
        return builder.build();
    }

    private McpSchema.CallToolResult serverInfo() {
        return response(Map.of(
                "server", buildInfo.getProperty("server.name"),
                "server_version", buildInfo.getProperty("server.version"),
                "refinery_version", buildInfo.getProperty("refinery.version"),
                "mcp_sdk_version", buildInfo.getProperty("mcp.sdk.version"),
                "java_version", Runtime.version().toString(),
                "diagnostics_enabled", diagnosticsEnabled), false);
    }

    private McpSchema.CallToolResult metamodelInfo() {
        return response(Map.of(
                "metamodel_id", metamodel.id(),
                "metamodel_version", metamodel.version(),
                "metamodel_source", metamodel.source(),
                "source_sha256", metamodel.sourceSha256()), false);
    }

    private synchronized McpSchema.CallToolResult checkInstance(Map<String, Object> arguments) {
        try {
            if (instanceChecker == null) {
                instanceChecker = new CompleteInstanceChecker(metamodel.source());
            }
            var result = instanceChecker.check(arguments);
            return response(Map.of("status", result.status(), "diagnostics", result.diagnostics()), false);
        } catch (CompleteInstanceChecker.InvalidInput exception) {
            return response(Map.of("status", "INVALID_ARGUMENT", "message", exception.getMessage()), true);
        } catch (IOException | RuntimeException exception) {
            LOG.error("Complete instance check failed", exception);
            return response(Map.of("status", "INTERNAL_ERROR", "message", "The instance check failed."), true);
        }
    }

    private synchronized McpSchema.CallToolResult runDiagnostic(Map<String, Object> arguments) {
        if (arguments == null || arguments.size() != 1
                || !(arguments.get("case_id") instanceof String caseId)
                || (!"valid".equals(caseId) && !"contradictory".equals(caseId))) {
            return response(Map.of("status", "INVALID_ARGUMENT",
                    "message", "case_id must be valid or contradictory."), true);
        }

        try {
            var source = CatalogExamples.load(caseId);
            var runner = new RefineryRunner(
                    StandaloneRefinery.getProblemLoader(), StandaloneRefinery.getGeneratorFactory());
            var result = runner.generate(source);
            return response(Map.of("case_id", caseId,
                    "status", result.status().name(), "model_source", result.modelSource()), false);
        } catch (InvalidProblemException exception) {
            return response(Map.of("case_id", caseId, "status", "REJECTED",
                    "message", exception.getMessage()), false);
        } catch (IOException | RuntimeException exception) {
            LOG.error("Refinery diagnostic failed for case {}", caseId, exception);
            return response(Map.of("status", "INTERNAL_ERROR",
                    "message", "The diagnostic could not be completed. See the server error log."), true);
        }
    }

    private McpSchema.CallToolResult response(Map<String, Object> payload, boolean error) {
        try {
            return McpSchema.CallToolResult.builder()
                    .structuredContent(payload)
                    .addTextContent(mapper.writeValueAsString(payload))
                    .isError(error)
                    .build();
        } catch (IOException exception) {
            throw new UncheckedIOException("Cannot serialize the tool response", exception);
        }
    }
}
