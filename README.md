# Cloud Nine — Fabric client selector (prototype)

A restart-based library for **Minecraft Java 1.21.11 / Fabric**. Press **Left Alt** (rebindable in Controls) to open it. The selector lists the seven names in the confirmed `67port` Drive folder and can **attempt to build the selected client from source**; you do not need to supply a compiled client. Fabric still needs the resulting JAR at launch.

![Cloud Nine interface, shown with a synthetic test fixture](docs/preview.png)

> **Not a complete seven-client bundle.** Dgclient's public source snapshot is downloadable from the selector; the other accessible archives are private Drive files, not included in this public repository. Recipes exist for the inspected Anubis, CorzClient, Gooba, and Krispy ZIP snapshots, but a recipe is only a build attempt, not proof that the source compiles or runs. `Waterv7.zip` contains a Java source tree without a Gradle build project, and Google denied access to `67Client.zip` as malware/spam, so neither has a source-build recipe. Dgclient previously crashed on Loader 0.19.3; Loader 0.19.5 includes a fix for the observed MixinExtras exception, but a source-built Dgclient must still pass a real launch before it can be called compatible.

## Safety first

**Building a source ZIP executes its Gradle scripts and downloaded dependencies with your operating-system permissions. The build is not sandboxed.** A project can read or modify files, contact the network, and potentially access credentials. The resulting client can do the same when Minecraft launches. Only confirm a build and run a client if you trust its source and dependencies. Several supplied archives describe recovered/cracked code; no independent safety audit is claimed. A checksum confirms a file's identity, **not** its safety or license. Review your rights before using or redistributing third-party code.

The selector asks for confirmation before building, uses its own Gradle wrapper with a pinned distribution hash, checks the inspected source ZIP's size and SHA-256, extracts it with path/size limits, and clears most inherited environment variables. These precautions **do not** confine malicious build scripts. Use an isolated OS account or virtual machine if you need isolation; a separate Minecraft game directory alone is not a security boundary.

## Install and select

1. Install Minecraft Java **1.21.11**, Fabric Loader **0.19.5+**, and a matching **Fabric API**. Install a **Java 21 JDK** (not just a Java runtime) and set `JAVA_HOME` to that JDK before starting the launcher. Cloud Nine also checks the Java used by Minecraft and JDKs on `PATH`.
2. Put the Cloud Nine JAR in that installation's `mods` folder. Start Minecraft and press **Left Alt**. All seven names appear automatically; this catalog is a snapshot, not an authenticated connection to your Drive.
3. For **Dgclient.zip**, select **BUILD & USE**, read the warning, and click **CONFIRM & BUILD**. If the ZIP is absent, Cloud Nine downloads a pinned public GitHub source snapshot. All 2,073 files in that snapshot were SHA-256 compared with the accessible Drive ZIP; the archives themselves have different ZIP encoding. The verified source ZIP is cached locally for retries. No published Dgclient binary is used as a fallback.
4. For **Anubis.zip**, **CorzClient.zip**, **Gooba.zip**, or **Krispy.zip**, obtain the existing source ZIP from your [67port Drive folder](https://drive.google.com/drive/folders/1zb-GWd4J4TXYjo1j2CLBG-6OjpiaIliF), put it in **OPEN FOLDER**, press **REFRESH**, and select **BUILD & USE** followed by **CONFIRM & BUILD**. These recipes target the exact inspected ZIP snapshots; changed or repackaged archives are rejected rather than executed. CorzClient is built from its project, not imported from its embedded precompiled JAR. Their successful compilation and compatibility are **not yet verified**.
5. The first build downloads Gradle, Minecraft mappings, and other dependencies and can take several minutes and substantial memory/disk space. The exact Gradle versions and tasks are specified in `DriveCatalog.java`. Cloud Nine does not overwrite an existing library JAR on failure; third-party build scripts can still modify accessible files. Errors are reported in `cloudnine/build-logs/` in the game directory. A build times out after 20 minutes per Gradle invocation. Cloud Nine validates the generated JAR's Fabric metadata, mod ID, and expected client class, then caches it as `<client>.jar` in `cloudnine/clients`. These checks cannot prove runtime compatibility or safety. To rebuild, close Minecraft and remove that cached JAR; your source ZIP remains.
6. After staging succeeds, quit Minecraft, wait at least five seconds, then start the same installation again. Cloud Nine moves its selector JAR out of `mods` after exit; Fabric loads the selected client and a small return helper instead. Dependencies are **not** automatically installed. Quit that session and wait at least five seconds before starting again: the helper restores Cloud Nine and removes the unchanged selected copy. The cached library JAR and source ZIP are retained.

`67Client.zip` remains unavailable because Drive denied its content request. The inspected Water archive has no build project or Fabric metadata, so it is not presented as buildable. Unknown ZIPs are likewise not executed. Existing matching compiled Fabric JARs, if already in the library, may still be selected, but they are not required for the source-build flow.

Cloud Nine **does not hot-load or unload Fabric or running mods** and does not restart the game itself. Fabric discovers mods at startup. Other mods already in `mods` remain untouched and loaded. To keep the game setup separate, use a **separate Minecraft launcher installation/game directory** with just the baseline mods and required dependencies. Do not run multiple Minecraft instances against that same directory while switching.

### Recovery

If Fabric fails before the return helper starts, or the launcher kills the helper, automatic return may not run. Close Minecraft and the launcher. In that profile's `mods` folder remove only `cloudnine-once-<UUID>.jar` and `cloudnine-return-<UUID>.jar` for the affected selection. In `cloudnine/handoff`, move `disabled-<UUID>.jar.off` back to `mods` and rename it `cloudnine-restored.jar` **only if there is no other Cloud Nine JAR there**. The original filename is recorded as `mainName` in `cloudnine/handoff/next.properties`. Keep your other mods and the original library files untouched. If files have changed or you are unsure, do not overwrite them; check `cloudnine/handoff.log` first. Interrupted builds may also leave temporary copies in `cloudnine/build-work/`; remove them only after all build processes have stopped.

## Build and test

```sh
./gradlew build
```

The normal GitHub Actions workflow runs unit tests and a production-like Minecraft smoke test with a **synthetic fixture**, including Left Alt, selection, selector removal, and restoration across three launches. It does not establish that a real Drive client works. The separately dispatched Dgclient source workflow runs the actual source-build code inside a container with no repository checkout, private Drive archives, or credentials mounted, then attempts a network-isolated real Minecraft launch of the resulting client. Build logs, not the third-party source or JAR, are retained as evidence.

## Privacy and storage

This GitHub repository is public. Private source ZIPs and third-party clients are not committed. Cloud Nine does not embed the Arena Drive connection or any Drive credentials; keep your source files local and check redistribution rights before sharing them. GitHub repository, Git LFS, release, and Actions artifact limits are separate; using the command line does not create a guaranteed 100 GB repository allowance.
