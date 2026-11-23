package refinerymcp.refinery;

import tools.refinery.generator.GeneratorResult;
import tools.refinery.generator.ModelGeneratorFactory;
import tools.refinery.generator.ProblemLoader;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class RefineryRunner {
    private final ProblemLoader problemLoader;
    private final ModelGeneratorFactory generatorFactory;

    public RefineryRunner(ProblemLoader problemLoader, ModelGeneratorFactory generatorFactory) {
        this.problemLoader = problemLoader;
        this.generatorFactory = generatorFactory;
    }

    public Result generate(String source) throws IOException {
        var problem = problemLoader.loadString(source);
        try (var generator = generatorFactory.createGenerator(problem)) {
            generator.setRandomSeed(1);
            var status = generator.tryGenerateWithTimeout(10, TimeUnit.SECONDS);
            if (status != GeneratorResult.SUCCESS) {
                return new Result(status, "");
            }

            var solution = generator.serialize();
            try (var output = new ByteArrayOutputStream()) {
                solution.eResource().save(output, Map.of());
                return new Result(status, output.toString(StandardCharsets.UTF_8));
            }
        }
    }

    public record Result(GeneratorResult status, String modelSource) {
    }
}
