package dev.ig.ocrlaundry;

import java.util.Arrays;

import dev.ig.ocrlaundry.api.ServerLauncher;
import dev.ig.ocrlaundry.experiment.ExperimentRunner;
import dev.ig.ocrlaundry.experiment.SampleDocumentGenerator;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        if (args.length == 0) {
            printUsage();
            return;
        }

        String[] commandArguments = Arrays.copyOfRange(args, 1, args.length);
        switch (args[0]) {
            case "generate-samples" -> SampleDocumentGenerator.main(commandArguments);
            case "experiment" -> ExperimentRunner.main(commandArguments);
            case "serve" -> ServerLauncher.main(commandArguments);
            default -> {
                System.err.println("Unknown command: " + args[0]);
                printUsage();
                System.exit(2);
            }
        }
    }

    private static void printUsage() {
        System.out.println("ocr-laundry commands:");
        System.out.println("  generate-samples [sample-directory]");
        System.out.println("  experiment [sample-directory] [result-directory] [repetitions]");
        System.out.println("  serve [port] [sample-directory] [fifo|sjf] [workers]");
    }
}
