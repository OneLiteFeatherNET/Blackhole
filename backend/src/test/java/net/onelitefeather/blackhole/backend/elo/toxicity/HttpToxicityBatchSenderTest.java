package net.onelitefeather.blackhole.backend.elo.toxicity;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HttpToxicityBatchSenderTest {

    @Test
    void theOverallScoresAreReadInOrder() {
        String body = "{\"model_version\":\"v1\",\"results\":[{\"overall\":0.25},{\"overall\":0.75}]}";

        assertEquals(List.of(0.25, 0.75), HttpToxicityBatchSender.parseScores(body, 2), "scores must keep the request order");
    }

    @Test
    void aResponseWithTheWrongNumberOfResultsIsRejected() {
        String body = "{\"model_version\":\"v1\",\"results\":[{\"overall\":0.25}]}";

        assertThrows(IllegalStateException.class, () -> HttpToxicityBatchSender.parseScores(body, 2),
                "one result for two texts must not be accepted");
    }

    @Test
    void aScoreOutsideTheUnitIntervalIsRejected() {
        String body = "{\"model_version\":\"v1\",\"results\":[{\"overall\":1.5}]}";

        assertThrows(IllegalStateException.class, () -> HttpToxicityBatchSender.parseScores(body, 1),
                "a score outside [0, 1] must not reach the ELO delta");
    }
}
