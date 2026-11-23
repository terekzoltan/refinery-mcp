package refinerymcp.diagnostics;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class CatalogExamples {
    private static final String RESOURCE_ROOT = "/diagnostics/catalog/";

    private CatalogExamples() {
    }

    public static String load(String caseName) throws IOException {
        if (!"valid".equals(caseName) && !"contradictory".equals(caseName)) {
            throw new IllegalArgumentException("Unknown case: " + caseName
                    + ". Expected valid or contradictory.");
        }
        // These diagnostic instances always belong to the bundled catalog metamodel.
        return read("/metamodels/catalog.problem") + "\n" + read(RESOURCE_ROOT + caseName + ".problem");
    }

    private static String read(String name) throws IOException {
        try (var input = CatalogExamples.class.getResourceAsStream(name)) {
            if (input == null) {
                throw new IOException("Missing bundled resource: " + name);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
