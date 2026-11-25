# Publishing Checklist

## Before You Push

1. `libs/recaf.jar` is tracked (pinned Recaf 4 release, used only for building).
   If you build against another release, replace it and commit the new hash in the
   commit message, or point `RECAF_JAR` at a local jar.
2. Run `.\gradlew.bat jar` (Windows) or `./gradlew jar` (Linux/macOS).
3. Start Recaf and confirm the plugin logs `http://127.0.0.1:8750`.
4. Run `pip install -r mcp-server/requirements.txt`.
5. Start `python mcp-server/recaf_mcp_server.py --http --port 8751`.
6. Confirm the Python server reports `Health check result: ok`.
7. On Linux, run `./scripts/linux/smoke_test.sh --with-mcp` to check headless mode
   (no display, no Xvfb) end to end, and `./test.sh --headless` for the quick variant.

## What Should Be In GitHub

- Java plugin source under `src/`
- Python MCP server under `mcp-server/`
- root `recaf_mcp_server.py`
- root `requirements.txt`
- `scripts/linux/` (headless launchers, installer, self test)
- `contrib/systemd/`, `docs/linux-headless.md`
- `libs/recaf.jar` — the pinned Recaf 4 release the plugin compiles against, so a
  clone builds without hunting for a jar. Swap it for another release/architecture
  when needed (`RECAF_JAR` still overrides it for a normal build).
- `README.md`
- `LICENSE`
- `.gitignore`
- `.mcp.json`

## What Should Not Be In GitHub

- `build/`
- `.gradle/`
- `.idea/`
- `__pycache__/`
- local logs

## Recommended First Commit

```powershell
git init
git add .
git commit -m "Initial Recaf MCP repository"
```

## Create The Remote

Using GitHub CLI:

```powershell
gh repo create recaf-mcp --public --source . --remote origin --push
```

Or create an empty repository on GitHub first, then:

```powershell
git remote add origin <your-repo-url>
git branch -M main
git push -u origin main
```

## First Release

Attach this file to the first GitHub Release:

```text
build/libs/recaf-mcp-plugin-0.1.0.jar
```
