package com.yizhaoqi.smartpai.evaluation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class SciFactRetrievalMetricsTest {

    @Test
    void shouldCalculateStandardBinaryRetrievalMetricsForMultipleRelevantDocuments() {
        SciFactRetrievalIT.MethodAccumulator accumulator = new SciFactRetrievalIT.MethodAccumulator();
        accumulator.add(new SciFactRetrievalIT.ClaimResult(
                1L,
                "claim",
                Set.of("doc-a", "doc-b"),
                List.of("irrelevant-1", "doc-a", "irrelevant-2", "doc-b", "irrelevant-3"),
                2));

        SciFactRetrievalIT.MethodReport report = accumulator.toReport();

        assertThat(report.hitRateAt1()).isZero();
        assertThat(report.hitRateAt3()).isEqualTo(1D);
        assertThat(report.recallAt3()).isEqualTo(0.5D);
        assertThat(report.recallAt5()).isEqualTo(1D);
        assertThat(report.precisionAt3()).isCloseTo(1D / 3D, within(1e-12));
        assertThat(report.precisionAt5()).isEqualTo(0.4D);

        double idealDcg = 1D + discount(2);
        double actualDcgAt3 = discount(2);
        double actualDcgAt5 = discount(2) + discount(4);
        assertThat(report.ndcgAt3()).isCloseTo(actualDcgAt3 / idealDcg, within(1e-12));
        assertThat(report.ndcgAt5()).isCloseTo(actualDcgAt5 / idealDcg, within(1e-12));
        assertThat(report.mrrAt10()).isEqualTo(0.5D);
    }

    private static double discount(int rank) {
        return 1D / (Math.log(rank + 1D) / Math.log(2D));
    }
}
