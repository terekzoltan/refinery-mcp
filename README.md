# Refinery MCP

A local Java MCP server for Refinery 0.3.0. Clients can retrieve the bundled catalog metamodel and check complete instance models against it. The solver runs on your machine. Your MCP client manages access to the AI model.

## Run in VS Code

Install JDK 25 and set `JAVA_HOME` to the JDK root directory in the environment you use to launch VS Code. If you just set the variable, quit VS Code and reopen it from that environment. The repository includes the Gradle Wrapper.

```powershell
git clone https://github.com/terekzoltan/refinery-mcp.git
cd refinery-mcp
.\gradlew.bat test installDist
code .
```

On Linux and macOS, use `./gradlew test installDist` for the build. The [VS Code configuration](.vscode/mcp.json) launches the server from the built distribution. In the Command Palette, select **MCP: List Servers → refineryMcp → Start Server**. Enable the server’s tools in Copilot Agent mode. After rebuilding, restart the server to use the new build.

## Tools

See the [one-page tool reference](TOOLS.md) for the four MCP tools, their inputs and outputs, and a worked repair example.

## Try it in Copilot

Open a new Agent chat and send this prompt:

> Use `get_metamodel` to retrieve the metamodel. Build a complete JSON instance from this inventory. The catalog `shop` contains two products (`laptop` and `book`) and two categories (`electronics` and `books`). The laptop belongs to both categories. The book belongs to `books`. Submit this initial state to `check_instance`. Use the diagnostic to correct the laptop’s classification so it belongs only to `electronics`. Preserve all objects, the book’s category, and every other link. Submit the complete corrected instance to `check_instance` and report both statuses and the change you made. Use only the two named MCP tools, without reading files or running terminal commands.

The first check should return `INVALID` with `Product::category::invalidMultiplicity(laptop)` in the diagnostic. Removing only the link from `laptop` to `books` should give `{"status":"VALID","diagnostics":[]}` on the second check. You can repeat the checks with the [original JSON](examples/check-instance/contradictory.json) and the [corrected JSON](examples/check-instance/valid.json).

## License

The project’s own source code is licensed under [EPL-2.0](LICENSE). The Gradle Wrapper scripts and JAR are licensed under [Apache-2.0](licenses/Apache-2.0.txt). Downloaded dependencies are subject to their respective licenses.
