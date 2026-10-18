package dev.ig.ocrlaundry.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class ExperimentRunnerTest {
    @Test
    void percentileUsesNearestRank() {
        List<Double> values = List.of(10.0, 20.0, 30.0, 40.0, 50.0);

        assertEquals(30.0, ExperimentRunner.percentile(values, 0.50));
        assertEquals(50.0, ExperimentRunner.percentile(values, 0.95));
    }

    @Test
    void percentileOfEmptySampleIsNotANumber() {
        assertEquals(Double.NaN, ExperimentRunner.percentile(List.of(), 0.95));
    }
}
