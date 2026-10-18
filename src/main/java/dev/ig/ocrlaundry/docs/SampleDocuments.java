package dev.ig.ocrlaundry.docs;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class SampleDocuments {
    private SampleDocuments() {
    }

    public static Map<String, Path> paths(Path directory) {
        Map<String, Path> paths = new LinkedHashMap<>();
        paths.put("long", directory.resolve("scanned-long.pdf"));
        paths.put("medium", directory.resolve("scanned-medium.pdf"));
        paths.put("small", directory.resolve("scanned-small.pdf"));
        return paths;
    }
}
