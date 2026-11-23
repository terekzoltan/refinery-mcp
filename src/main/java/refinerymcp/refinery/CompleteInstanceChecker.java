package refinerymcp.refinery;

import tools.refinery.generator.ModelFacadeResult;
import tools.refinery.generator.standalone.StandaloneRefinery;
import tools.refinery.language.model.problem.ClassDeclaration;
import tools.refinery.language.model.problem.PredicateDefinition;
import tools.refinery.language.model.problem.PredicateKind;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Checks a closed, typed graph against the server-owned, flat, reference-only metamodel. */
public final class CompleteInstanceChecker {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,63}");
    private static final int MAX_OBJECTS = 64;
    private static final int MAX_LINKS = 256;
    private static final String NODE_PREFIX = "ci_";

    public record Result(String status, List<String> diagnostics) {}
    private record Relation(String source, String target) {}
    private record Link(String source, String reference, String target) {}

    public static final class InvalidInput extends IllegalArgumentException {
        InvalidInput(String message) { super(message); }
    }

    private final String metamodel;
    private final Set<String> types = new LinkedHashSet<>();
    private final Map<String, Relation> references = new LinkedHashMap<>();

    public CompleteInstanceChecker(String metamodel) throws IOException {
        this.metamodel = metamodel;
        var problem = StandaloneRefinery.getProblemLoader().loadString(metamodel);
        for (var statement : problem.getStatements()) {
            if (statement instanceof PredicateDefinition predicate && predicate.getKind() == PredicateKind.ERROR) {
                continue;
            }
            if (!(statement instanceof ClassDeclaration type) || type.isAbstract() || !type.getSuperTypes().isEmpty()) {
                throw new IOException("Complete instance checking requires concrete, flat classes and error predicates.");
            }
            types.add(type.getName());
            for (var reference : type.getFeatureDeclarations()) {
                if (!(reference.getReferenceType() instanceof ClassDeclaration target)) {
                    throw new IOException("Complete instance checking currently supports object references only.");
                }
                if (references.putIfAbsent(reference.getName(), new Relation(type.getName(), target.getName())) != null) {
                    throw new IOException("Ambiguous reference name: " + reference.getName());
                }
            }
        }
    }

    public static Map<String, Object> inputSchema() {
        return Map.of("type", "object", "properties", Map.of(
                        "objects", rowsSchema(MAX_OBJECTS, "id", "type"),
                        "links", rowsSchema(MAX_LINKS, "source", "reference", "target")),
                "required", List.of("objects", "links"), "additionalProperties", false);
    }

    private static Map<String, Object> rowsSchema(int limit, String... fields) {
        var properties = new LinkedHashMap<String, Object>();
        for (var field : fields) {
            properties.put(field, Map.of("type", "string", "pattern", "^" + IDENTIFIER + "$", "maxLength", 64));
        }
        return Map.of("type", "array", "maxItems", limit, "items", Map.of(
                "type", "object", "properties", properties, "required", List.of(fields), "additionalProperties", false));
    }

    public Result check(Map<String, Object> arguments) throws IOException {
        var source = encode(arguments);
        var problem = StandaloneRefinery.getProblemLoader().loadString(source);
        try (var semantics = StandaloneRefinery.getSemanticsFactory().concretize(true).tryCreateSemantics(problem)) {
            if (semantics.getInitializationResult() instanceof ModelFacadeResult.Rejected rejected) {
                return new Result("INVALID", List.of(displayMessage(rejected.formatMessage())));
            }
            var consistency = semantics.checkConsistency();
            if (consistency.isConsistent()) {
                return new Result("VALID", List.of());
            }
            return new Result("INVALID", List.of(displayMessage(consistency.formatMessage())));
        }
    }

    // Nodes use a private identifier prefix so client IDs may even be keywords like "class".
    private static String displayMessage(String text) {
        return text.replaceAll("\\b" + NODE_PREFIX + "([A-Za-z][A-Za-z0-9_]*)", "$1");
    }

    private String encode(Map<String, Object> arguments) {
        if (arguments == null || !arguments.keySet().equals(Set.of("objects", "links"))) {
            throw new InvalidInput("Expected exactly objects and links.");
        }
        var objects = new LinkedHashMap<String, String>();
        for (var item : rows(arguments, "objects", MAX_OBJECTS)) {
            var row = fields(item, Set.of("id", "type"));
            var id = (String) row.get("id");
            var type = (String) row.get("type");
            if (!types.contains(type)) throw new InvalidInput("Unknown type: " + type);
            if (objects.putIfAbsent(id, type) != null) throw new InvalidInput("Duplicate object ID: " + id);
        }

        var links = new LinkedHashSet<Link>();
        for (var item : rows(arguments, "links", MAX_LINKS)) {
            var row = fields(item, Set.of("source", "reference", "target"));
            var link = new Link((String) row.get("source"), (String) row.get("reference"), (String) row.get("target"));
            var relation = references.get(link.reference());
            if (relation == null) throw new InvalidInput("Unknown reference: " + link.reference());
            requireEndpoint(objects, link.source(), relation.source());
            requireEndpoint(objects, link.target(), relation.target());
            if (!links.add(link)) throw new InvalidInput("Duplicate link: " + link);
        }

        var source = new StringBuilder(metamodel).append('\n');
        // A complete snapshot permits exactly its listed nodes, class memberships and edges.
        source.append("scope node = ").append(objects.size()).append(".\n");
        for (var type : types) {
            source.append("!exists(").append(type).append("::new).\n");
            source.append("default !").append(type).append("(*).\n");
        }
        for (var reference : references.keySet()) {
            source.append("default !").append(reference).append("(*, *).\n");
        }
        objects.forEach((id, type) -> source.append(type).append('(').append(NODE_PREFIX).append(id).append(").\n"));
        for (var link : links) {
            source.append(link.reference()).append('(').append(NODE_PREFIX).append(link.source())
                    .append(", ").append(NODE_PREFIX).append(link.target()).append(").\n");
        }
        return source.toString();
    }

    private static List<?> rows(Map<String, Object> arguments, String key, int limit) {
        if (!(arguments.get(key) instanceof List<?> rows) || rows.size() > limit) {
            throw new InvalidInput(key + " must be an array with at most " + limit + " items.");
        }
        return rows;
    }

    private static Map<?, ?> fields(Object value, Set<String> expected) {
        if (!(value instanceof Map<?, ?> fields) || !fields.keySet().equals(expected)) {
            throw new InvalidInput("Expected fields: " + expected);
        }
        for (var field : fields.values()) {
            if (!(field instanceof String name) || !IDENTIFIER.matcher(name).matches()) {
                throw new InvalidInput("Expected a simple identifier in " + expected + ".");
            }
        }
        return fields;
    }

    private static void requireEndpoint(Map<String, String> objects, String id, String requiredType) {
        if (!requiredType.equals(objects.get(id))) {
            throw new InvalidInput("Expected declared object " + id + " of type " + requiredType + ".");
        }
    }
}
