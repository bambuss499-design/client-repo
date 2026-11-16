# Cloud Nine — Fabric client selector (prototype)

A celestial, restart-based client library for **Minecraft Java 1.21.11 / Fabric**. Press **Left Alt** (rebindable in Controls) to open it. Cloud Nine shows all seven filenames from the confirmed Drive folder, pairs them with matching compiled Fabric JARs, and stages one for the next launch. The names are a snapshot, not a live Drive connection.

![Cloud Nine interface, shown with a test fixture](docs/preview.png)

> **Prototype / source-folder status:** only Dgclient has a configured public-release download, referenced by its source ZIP; the other requested Drive clients are not included or claimed to be compatible. Source ZIPs are not executable mods: they must be inspected and built separately for 1.21.11. A checksum verifies the downloaded file matches the pinned release, not that third-party code is safe.

## Install

1. Install Minecraft Java **1.21.11**, Fabric Loader **0.19.3+**, and a matching **Fabric API**. Java 21 is required.
2. Put the Cloud Nine JAR in that installation's `mods` folder. Start Minecraft once.
3. Press **Left Alt**. All seven Drive filenames appear automatically. **Dgclient.zip** can download the public Aether release linked in its source README when you select **DOWNLOAD & USE**; its exact size and SHA-256 are checked before staging. The other clients are not included yet. For those, use **OPEN FOLDER**, put a trusted, compiled **Fabric 1.21.11** JAR with the matching basename in the folder (for example `Anubis.jar`), and press **REFRESH**. A ZIP or catalog name without a matching JAR is shown as unavailable. Cloud Nine never executes or builds source archives. Standalone JARs and other source ZIPs also appear in the library.
4. Select a ready client, press **USE FOR NEXT LAUNCH**, and wait for confirmation. Quit Minecraft, wait at least five seconds, then start the same installation again. Cloud Nine moves its own selector JAR out of `mods` after exit; Fabric loads the selected client and a small return helper instead. Cloud Nine does **not** install the client's dependencies.
5. Quit the selected-client session. The return helper restores Cloud Nine and removes the unchanged selected copy after Minecraft exits. Wait at least five seconds before launching again. The original library JAR is never removed.

Cloud Nine **does not hot-load or unload Fabric or running mods** and does not restart the game itself. Fabric discovers mods at startup. Metadata checks are only a preliminary filter: compatibility, dependencies, licensing, and code safety are **not guaranteed**. A mod can run arbitrary code, so use only files you trust.

Other mods already in `mods` are deliberately **left untouched** and remain loaded. For an experience with only Cloud Nine, Fabric API, and the selected client, use a **separate Minecraft launcher installation/game directory** containing only those baseline mods and the necessary dependencies. Do not run multiple Minecraft instances against that same directory while switching.

### Recovery

If Fabric fails before the return helper starts, or the launcher kills the helper, the automatic return may not run. Close Minecraft and the launcher. In that profile's `mods` folder remove only `cloudnine-once-<UUID>.jar` and `cloudnine-return-<UUID>.jar` for the affected selection. In `cloudnine/handoff`, move `disabled-<UUID>.jar.off` back to `mods` and rename it `cloudnine-restored.jar` **only if there is no other Cloud Nine JAR there**. The original filename is recorded as `mainName` in `cloudnine/handoff/next.properties`. Keep your other mods and the original `cloudnine/clients` JAR untouched. If files have been changed or you are unsure, do not overwrite them; check `cloudnine/handoff.log` first.

## Build and test

```sh
./gradlew build
```

The GitHub Actions workflow runs unit tests, builds the mod, then launches a **real Minecraft client in a production-like Fabric environment** under Xvfb/software OpenGL, using the remapped distributable JAR. After the loading overlay clears, it sends Left Alt and mouse events through Minecraft's GLFW callbacks, checks the seven catalog names, selects a synthetic fixture and stages it through the UI. It verifies that the selected fixture loads **without Cloud Nine** in the second launch and that Cloud Nine returns without the fixture in a third launch. The screenshot shows the test fixture, which is not distributed in the mod. Logs and a screenshot are uploaded as workflow artifacts.

## Privacy and storage

This GitHub repository is public. Do not commit private source archives or clients without checking you have permission to publish them. GitHub repository, Git LFS, release, and Actions artifact limits are separate; using the command line does not create a guaranteed 100 GB repository allowance.
