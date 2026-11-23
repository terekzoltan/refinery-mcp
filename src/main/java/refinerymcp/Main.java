package refinerymcp;

import refinerymcp.diagnostics.CatalogExamples;
import refinerymcp.mcp.RefineryMcpServer;
import refinerymcp.refinery.RefineryRunner;
import tools.refinery.generator.InvalidProblemException;
import tools.refinery.generator.standalone.StandaloneRefinery;

import java.io.IOException;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length > 0 && "--mcp".equals(args[0])) {
            if (args.length > 2 || (args.length == 2 && !"--diagnostics".equals(args[1]))) {
                throw new IllegalArgumentException("Usage: --mcp [--diagnostics]");
            }
            var server = new RefineryMcpServer(args.length == 2).start();
            Runtime.getRuntime().addShutdownHook(new Thread(server::close, "mcp-shutdown"));
            return;
        }

        if (args.length > 1) {
            throw new IllegalArgumentException("Usage: [valid|contradictory]");
        }
        var caseName = args.length == 0 ? "valid" : args[0];
        var source = CatalogExamples.load(caseName);
        var runner = new RefineryRunner(
                StandaloneRefinery.getProblemLoader(),
                StandaloneRefinery.getGeneratorFactory());

        System.out.println("Case: " + caseName);
        try {
            var result = runner.generate(source);
            System.out.println("Refinery result: " + result.status());
            if (!result.modelSource().isEmpty()) {
                System.out.println("Generated model:");
                System.out.print(result.modelSource());
            }
        } catch (InvalidProblemException exception) {
            System.out.println("Refinery result: REJECTED");
            System.out.println(exception.getMessage());
        }
    }
}
