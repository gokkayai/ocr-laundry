package dev.ig.ocrlaundry.api;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Locale;

import dev.ig.ocrlaundry.core.SchedulerEngine;
import dev.ig.ocrlaundry.docs.DocumentCatalog;
import dev.ig.ocrlaundry.docs.PdfInspector;
import dev.ig.ocrlaundry.docs.SampleDocuments;
import dev.ig.ocrlaundry.model.SchedulingPolicy;
import dev.ig.ocrlaundry.ocr.TesseractOcrRunner;

public final class ServerLauncher {
    private static final int DEFAULT_PORT = 8080;

    private ServerLauncher() {
    }

    public static void main(String[] args) throws Exception {
        int port = args.length >= 1 ? port(args[0]) : DEFAULT_PORT;
        Path sampleDirectory = args.length >= 2 ? Path.of(args[1]) : Path.of("samples");
        SchedulingPolicy policy = args.length >= 3
                ? policy(args[2])
                : SchedulingPolicy.FIFO;
        int workers = args.length >= 4
                ? positiveInteger(args[3], "workers")
                : SchedulerEngine.DEFAULT_WORKER_COUNT;

        DocumentCatalog catalog = new DocumentCatalog(
                SampleDocuments.paths(sampleDirectory),
                new PdfInspector());
        SchedulerEngine scheduler = new SchedulerEngine(
                catalog,
                new TesseractOcrRunner(),
                policy,
                workers);
        OcrLaundryServer server;
        try {
            server = new OcrLaundryServer(
                    new InetSocketAddress("127.0.0.1", port),
                    catalog,
                    scheduler,
                    TesseractHealthCheck::isAvailable);
        } catch (Exception exception) {
            scheduler.close();
            throw exception;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "ocr-laundry-shutdown"));
        server.start();
        System.out.printf(
                "ocr-laundry listening on http://127.0.0.1:%d (%s, %d workers)%n",
                server.port(),
                policy,
                workers);
    }

    private static int port(String value) {
        int port = Integer.parseInt(value);
        if (port < 0 || port > 65_535) {
            throw new IllegalArgumentException("port must be between 0 and 65535");
        }
        return port;
    }

    private static SchedulingPolicy policy(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "fifo" -> SchedulingPolicy.FIFO;
            case "sjf", "estimated-sjf" -> SchedulingPolicy.ESTIMATED_SJF;
            default -> throw new IllegalArgumentException("policy must be fifo or sjf");
        };
    }

    private static int positiveInteger(String value, String name) {
        int parsed = Integer.parseInt(value);
        if (parsed <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return parsed;
    }
}
