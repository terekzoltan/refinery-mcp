# Refinery MCP

Helyi Java MCP-szerver Refinery 0.3.0-hoz. A kliens lekérheti a csomagolt katalógus-metamodellt, és ellenőrizheti a hozzá készített teljes példánymodellt. A solver a gépeden fut. Az AI-modellhez tartozó hozzáférést a kliens kezeli.

## Indítás VS Code-ban

JDK 25 szükséges. A `JAVA_HOME` a JDK gyökérkönyvtárára mutasson a VS Code-ot indító környezetben. Ha most állítottad be, lépj ki a VS Code-ból, majd indítsd el újra ugyanabból a környezetből. A Gradle Wrappert a repository tartalmazza.

```powershell
git clone https://github.com/terekzoltan/refinery-mcp.git
cd refinery-mcp
.\gradlew.bat test installDist
code .
```

Linuxon és macOS-en a build parancsa `./gradlew test installDist`. A [VS Code konfigurációja](.vscode/mcp.json) a kész disztribúcióból indítja a szervert. A parancspalettán válaszd az **MCP: List Servers → refineryMcp → Start Server** műveletet. Copilot Agent módban engedélyezd a szerver eszközeit. Új build használatához indítsd újra a szervert.

## Eszközök

| Eszköz | Bemenet | Válasz |
| --- | --- | --- |
| `get_server_info` | `{}` | A szerver, Java, Refinery és az MCP SDK verziója, a diagnosztikai mód állapota. |
| `get_metamodel` | `{}` | `metamodel_id`, `metamodel_version`, teljes `metamodel_source` és a forrás `source_sha256` lenyomata. |
| `check_instance` | `objects` és `links` tömböket tartalmazó teljes példány. Egy objektum mezői `id`, `type`. Egy kapcsolaté `source`, `reference`, `target`. | `status` (`VALID` vagy `INVALID`) és a Refinery diagnosztikai üzeneteit tartalmazó `diagnostics` lista. Hibás bemeneti formátum esetén MCP-eszközhiba. |
| `run_refinery_diagnostic` | `{"case_id":"valid"}` vagy `{"case_id":"contradictory"}` | Generálási státusz és siker esetén `model_source`. A csomagolt példákat futtatja, a VS Code konfigurációban megadott `--diagnostics` kapcsolóval érhető el. |

A `check_instance` minden hívásban a felsorolt objektumok és kapcsolatok teljes halmazát vizsgálja. A hiányzó kapcsolat hamis. A javítás során a kliens újraküldi a teljes jelöltet. A szerver a [rögzített metamodellt](src/main/resources/metamodels/catalog.problem) használja. Legfeljebb 64 objektumot és 256 kapcsolatot fogad, az azonosítók angol betűvel kezdődnek és legfeljebb 64 betűből, számból vagy aláhúzásból állnak.

## Példa Copilotból

Csatold a [hibás példányt](examples/check-instance/contradictory.json) a chathez, majd küldd el ezt a kérést.

> Kérd le a metamodellt a `get_metamodel` eszközzel. A csatolt JSON teljes tartalmát küldd be a `check_instance` eszköznek. Mutasd meg a diagnosztikát. Javítsd a laptop kategóriáját úgy, hogy az electronics maradjon. Tartsd meg a könyv books besorolását, az összes objektumot és a többi kapcsolatot. Hívd meg ismét a `check_instance` eszközt a javított teljes példánnyal. Írd ki, melyik kapcsolat változott és mi lett az eredmény.

A laptop egyszerre az `electronics` és a `books` kategóriához tartozik, pedig pontosan egy kategória megengedett. Az első válasz `INVALID`, a diagnosztikában a `Product::category::invalidMultiplicity(laptop)` kifejezéssel. A [javított példány](examples/check-instance/valid.json) egyedül a laptop → books kapcsolatot törli. Az eredmény `{"status":"VALID","diagnostics":[]}`.

## Licenc

A saját forráskód licence [EPL-2.0](LICENSE). A Gradle Wrapper szkriptjei és JAR-ja az [Apache-2.0](licenses/Apache-2.0.txt) licenc alatt érhetők el. A letöltött függőségekre a saját licenceik vonatkoznak.
