package refinerymcp.model;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Properties;

public record MetamodelDefinition(String id, String version, String source, String sourceSha256) {
    public static MetamodelDefinition loadSelected() throws IOException {
        var descriptor = new Properties();
        try (var input = openResource("/metamodels/selected.properties")) {
            descriptor.load(input);
        }
        var id = requireProperty(descriptor, "id");
        var version = requireProperty(descriptor, "version");
        var resource = requireProperty(descriptor, "resource");
        String source;
        try (var input = openResource(resource)) {
            source = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        if (source.isBlank()) {
            throw new IOException("Selected metamodel source is empty.");
        }
        return new MetamodelDefinition(id, version, source, sha256(source));
    }

    private static InputStream openResource(String name) throws IOException {
        var input = MetamodelDefinition.class.getResourceAsStream(name);
        if (input == null) {
            throw new IOException("Missing bundled metamodel resource: " + name);
        }
        return input;
    }

    private static String requireProperty(Properties descriptor, String name) throws IOException {
        var value = descriptor.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IOException("Missing selected metamodel property: " + name);
        }
        return value.trim();
    }

    private static String sha256(String source) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable in this Java runtime.", exception);
        }
    }
}
