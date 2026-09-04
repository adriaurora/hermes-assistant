package dk.foss.jarvis.hermes

import java.io.IOException
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Test

class EventFetchOutcomeTest {
    @Test fun outcomesAreNonSensitiveAndDistinct() {
        assertEquals(FetchFailureKind.NETWORK, EventFetchException(FetchFailureKind.NETWORK, cause = IOException()).kind)
        assertEquals(FetchFailureKind.SERIALIZATION, EventFetchException(FetchFailureKind.SERIALIZATION, cause = SerializationException("bad payload")).kind)
        assertEquals(FetchFailureKind.HTTP, EventFetchException(FetchFailureKind.HTTP, 503).kind)
    }
}
