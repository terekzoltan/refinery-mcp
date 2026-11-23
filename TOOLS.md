# Refinery MCP tool reference

An AI agent calls these tools through an MCP client. The Java server loads the catalog metamodel and uses Refinery to check the instance supplied by the agent.

| Tool | Input | Response |
| --- | --- | --- |
| `get_server_info` | `{}` | The server, Java, Refinery, and MCP SDK versions, plus whether diagnostic mode is enabled. |
| `get_metamodel` | `{}` | `metamodel_id`, `metamodel_version`, the full `metamodel_source`, and its `source_sha256` digest. |
| `check_instance` | A complete instance with two arrays: `objects` and `links`. Each object has an `id` and a `type`. Each link has a `source`, a `reference`, and a `target`. | A `status` of `VALID` or `INVALID` and a `diagnostics` list containing Refinery’s messages. Malformed input returns an MCP tool error. |
| `run_refinery_diagnostic` | `{"case_id":"valid"}` or `{"case_id":"contradictory"}` | Runs the generator on the selected bundled example and returns the generation status. On success, `model_source` contains the generated model. Available when the server starts with the `--diagnostics` flag, as in the VS Code configuration. |

Each `check_instance` call checks exactly the objects and links supplied. An omitted link is false. To check a repair, resubmit the complete instance. The server uses the [fixed catalog metamodel](src/main/resources/metamodels/catalog.problem) and accepts up to 64 objects and 256 links per call. Identifiers can contain up to 64 ASCII letters, digits, or underscores and must start with a letter.

## Example: checking and repairing a catalog

1. Call `get_metamodel` with `{}`. The response identifies `catalog` version `1.0.0`. Its source requires each product to have exactly one category.
2. Call `check_instance` with the [complete initial JSON](examples/check-instance/contradictory.json). The laptop belongs to both `electronics` and `books`. The result is `INVALID`. The diagnostics include `Product::category::invalidMultiplicity(laptop)`.
3. Remove the link `{"source":"laptop","reference":"category","target":"books"}`. Keep the book in `books` and resubmit the [complete corrected JSON](examples/check-instance/valid.json). The tool returns `{"status":"VALID","diagnostics":[]}`.

See the [README](README.md#try-it-in-copilot) for a prompt that asks Copilot to build both instances from a written inventory.
