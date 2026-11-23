package refinerymcp.refinery;

import com.google.inject.Inject;
import org.junit.jupiter.api.Test;
import refinerymcp.diagnostics.CatalogExamples;
import tools.refinery.generator.GeneratorResult;
import tools.refinery.generator.InvalidProblemException;
import tools.refinery.generator.ModelGeneratorFactory;
import tools.refinery.generator.ProblemLoader;
import tools.refinery.language.tests.InjectWithRefinery;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

@InjectWithRefinery
class RefineryRunnerTest {
    @Inject
    private ProblemLoader problemLoader;

    @Inject
    private ModelGeneratorFactory generatorFactory;

    @Test
    void validCatalogProducesReloadableModelAndKeepsFixedCategory() throws IOException {
        var runner = runner();
        var result = runner.generate(CatalogExamples.load("valid"));

        assertEquals(GeneratorResult.SUCCESS, result.status());
        assertFalse(result.modelSource().isBlank());
        assertEquals(GeneratorResult.SUCCESS, runner.generate(result.modelSource()).status());
        assertRejected(runner, result.modelSource() + "\n!category(laptop, electronics).\n");
    }

    @Test
    void twoCategoriesAreRejectedAndNextValidRunStillWorks() throws IOException {
        var runner = runner();
        var contradictory = CatalogExamples.load("contradictory");

        assertRejected(runner, contradictory);
        assertEquals(GeneratorResult.SUCCESS, runner.generate(CatalogExamples.load("valid")).status());
    }

    @Test
    void malformedModelIsReportedAsAnInputError() {
        assertThrows(InvalidProblemException.class, () -> runner().generate("class {."));
    }

    @Test
    void productCannotUseAnotherCatalogsCategory() throws IOException {
        try (var input = getClass().getResourceAsStream("/metamodels/catalog.problem")) {
            var metamodel = new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8);
            assertRejected(runner(), metamodel + """

                    Catalog(firstShop).
                    Catalog(secondShop).
                    Product(laptop).
                    Category(electronics).
                    products(firstShop, laptop).
                    categories(secondShop, electronics).
                    category(laptop, electronics).
                    scope Catalog = 2, Product = 1, Category = 1.
                    """);
        }
    }

    @Test
    void unknownExampleCannotSelectArbitraryResources() {
        assertThrows(IllegalArgumentException.class, () -> CatalogExamples.load("../metamodel"));
    }

    private RefineryRunner runner() {
        return new RefineryRunner(problemLoader, generatorFactory);
    }

    private void assertRejected(RefineryRunner runner, String source) throws IOException {
        problemLoader.loadString(source); // Rejecting bad syntax would not prove a model contradiction.
        try {
            var result = runner.generate(source);
            assertEquals(GeneratorResult.UNSATISFIABLE, result.status());
            assertEquals("", result.modelSource());
        } catch (InvalidProblemException expected) {
            // Refinery may detect the contradiction during initialization, before search starts.
        }
    }
}
