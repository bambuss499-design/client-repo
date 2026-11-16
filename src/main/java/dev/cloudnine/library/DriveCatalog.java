package dev.cloudnine.library;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Names in the confirmed Drive folder and build recipes for inspected source snapshots. */
public final class DriveCatalog {
    private DriveCatalog() { }

    public static final List<String> NAMES = List.of(
            "67Client.zip", "Anubis.zip", "CorzClient.zip", "Dgclient.zip",
            "Gooba.zip", "Krispy.zip", "Waterv7.zip");

    // Pin both the source archive and Gradle distribution. A digest establishes
    // identity, not safety or compatibility. Project build scripts still execute code.
    record Archive(long size, String sha256, String root, URI download) { }
    record Source(String label, String jarName, String modId, String entrypointClass,
                  String gradleVersion, String gradleSha256, List<List<String>> tasks,
                  String output, List<Archive> archives) {
        Optional<Archive> download() { return archives.stream().filter(a -> a.download() != null).findFirst(); }
    }

    private static final String GRADLE_91 = "a17ddd85a26b6a7f5ddb71ff8b05fc5104c0202c6e64782429790c933686c806";
    private static final String GRADLE_941 = "2ab2958f2a1e51120c326cad6f385153bb11ee93b3c216c5fccebfdfbb7ec6cb";
    private static final String GRADLE_951 = "bafc141b619ad6350fd975fc903156dd5c151998cc8b058e8c1044ab5f7b031f";
    private static final String GRADLE_971 = "acd53f1edaf02f1a8ff99879f8a34b302661a057d9b063ae9e35b552f804d20a";

    static final Source ANUBIS = new Source("Anubis.zip", "Anubis.jar", "anubisplus",
            "net/anubis/client/AnubisClientMod.class", "9.4.1", GRADLE_941,
            List.of(List.of(":remapJar")), "build/libs/anubis-1.0+mc1.21.11.jar",
            List.of(new Archive(10_994_713L, "a8bce468071eee48b830ef0d39c999cfd729f2ca01a4209cb32fc0a105667139",
                    "anubis-1.0-src", null)));

    static final Source CORZ = new Source("CorzClient.zip", "CorzClient.jar", "corzclient",
            "com/corz/client/CorzClient.class", "9.7.1", GRADLE_971,
            List.of(List.of(":remapJar")), "build/libs/corz-client-2.0.1.jar",
            List.of(new Archive(46_862_263L, "05aa98d4f0c8d0dcdcca0888434f955bd88b80edf8d7af39984476e7bef5db3a",
                    "CorzClient-SourcedByDexter", null)));

    static final Source DGCLIENT = new Source("Dgclient.zip", "Dgclient.jar", "aetherclient",
            "dev/aether/AetherClient.class", "9.1.0", GRADLE_91,
            // The source README builds the bridge and port before configuring the main mod.
            List.of(List.of(":corz-api:jar", ":corzport:remapJar"), List.of(":remapJar")),
            "build/libs/aetherclient-2.1.0.jar",
            List.of(
                    new Archive(72_440_953L, "fee3298dce069a342faf46aeb4d6bee4eb4f7a4eb7fdba540afa0b367045aac7",
                            "dgclient-2.1.0-pinkbloom", null),
                    // Every file in this public commit was SHA-256 compared with the Drive ZIP.
                    new Archive(72_553_453L, "e6863b0bee5133331f54279bf8f35f1527d3d8b6d2f2eead3fc6c84fdf42d257",
                            "dgclient-a4814302cea629a89ffe9b06f121af200df43821",
                            URI.create("https://codeload.github.com/nananqj96-cloud/dgclient/zip/a4814302cea629a89ffe9b06f121af200df43821"))));

    static final Source GOOBA = new Source("Gooba.zip", "Gooba.jar", "goobaa",
            "dev/gooba/client/Gooba.class", "9.5.1", GRADLE_951,
            List.of(List.of(":remapJar")), "build/libs/goobaa-client-deobf-built.jar",
            List.of(new Archive(14_492_289L, "d7fc755533b177fc0303538c076ec36f852c09248c47e9cfc1cab60a9b0d7000",
                    "", null)));

    static final Source KRISPY = new Source("Krispy.zip", "Krispy.jar", "krispy",
            "krispy/Main.class", "9.5.1", GRADLE_951,
            List.of(List.of(":remapJar")), "build/libs/krispy-source-built-1.0.0+1.21.11.jar",
            List.of(new Archive(10_428_167L, "417d0e279570b1833f61da956abaae189dcb7d7d0928e411a88f7384a31848aa",
                    "", null)));

    private static final Map<String, Source> SOURCES = Map.of(
            ANUBIS.label(), ANUBIS, CORZ.label(), CORZ, DGCLIENT.label(), DGCLIENT,
            GOOBA.label(), GOOBA, KRISPY.label(), KRISPY);

    static Optional<Source> sourceFor(String label) {
        return SOURCES.values().stream().filter(source -> source.label().equalsIgnoreCase(label)).findFirst();
    }
}
