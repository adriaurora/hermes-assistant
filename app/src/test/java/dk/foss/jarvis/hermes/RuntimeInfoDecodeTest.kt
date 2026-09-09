package dk.foss.jarvis.hermes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Verifies RuntimeInfo decodes route_source values and handles unknown/missing fields.
 * Fixture-style JSON tests similar to HermesWireDecodeTest.
 */
class RuntimeInfoDecodeTest {

    private val json = HermesJson

    // 1. All route_source values
    @Test
    fun `route_source global`() {
        val info = json.decodeFromString(RuntimeInfo.serializer(), """{"model":"m","route_source":"global"}""")
        assertEquals("m", info.model)
        assertEquals("global", info.route_source)
    }

    @Test
    fun `route_source raw_request`() {
        val info = json.decodeFromString(RuntimeInfo.serializer(), """{"model":"m","route_source":"raw_request"}""")
        assertEquals("raw_request", info.route_source)
    }

    @Test
    fun `route_source model_routes`() {
        val info = json.decodeFromString(RuntimeInfo.serializer(), """{"model":"m","route_source":"model_routes"}""")
        assertEquals("model_routes", info.route_source)
    }

    @Test
    fun `route_source session_model_lock`() {
        val info = json.decodeFromString(RuntimeInfo.serializer(), """{"model":"m","route_source":"session_model_lock"}""")
        assertEquals("session_model_lock", info.route_source)
    }

    // 2. Unknown keys ignored
    @Test
    fun `unknown keys ignored`() {
        val info = json.decodeFromString(RuntimeInfo.serializer(), """{"model":"m","route_source":"global","unknown_field":123}""")
        assertEquals("m", info.model)
        assertEquals("global", info.route_source)
    }

    // 3. Missing fields → null
    @Test
    fun `missing fields are null`() {
        val info = json.decodeFromString(RuntimeInfo.serializer(), """{}""")
        assertNull(info.model)
        assertNull(info.route_source)
        assertNull(info.provider)
        assertNull(info.model_lock)
    }

    // 4. Full fixture
    @Test
    fun `full runtime fixture`() {
        val info = json.decodeFromString(RuntimeInfo.serializer(), """{"provider":"custom","model":"hermes-agent","route_source":"session_model_lock","model_lock":"accepted"}""")
        assertEquals("custom", info.provider)
        assertEquals("hermes-agent", info.model)
        assertEquals("session_model_lock", info.route_source)
        assertEquals("accepted", info.model_lock)
    }
}