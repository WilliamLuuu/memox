package cool.hin.memox.data.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM tests for the Agent API's pre-auth bounds and endpoint formatting. */
class AgentApiHttpTest {

    @Test
    fun `endpoint host wraps IPv6 literals in brackets`() {
        assertEquals("[::1]", AgentApiHttp.formatEndpointHost("::1"))
        assertEquals("[fe80::1]", AgentApiHttp.formatEndpointHost(" fe80::1 "))
    }

    @Test
    fun `endpoint host leaves IPv4 and hostnames untouched`() {
        assertEquals("127.0.0.1", AgentApiHttp.formatEndpointHost("127.0.0.1"))
        assertEquals("0.0.0.0", AgentApiHttp.formatEndpointHost("0.0.0.0"))
        assertEquals("example.local", AgentApiHttp.formatEndpointHost("example.local"))
    }

    @Test
    fun `empty endpoint host falls back to loopback`() {
        assertEquals("127.0.0.1", AgentApiHttp.formatEndpointHost("   "))
    }

    @Test
    fun `endpoint url is a valid authority for IPv4 and IPv6`() {
        assertEquals(
            "http://127.0.0.1:47823",
            AgentApiHttp.formatEndpoint("127.0.0.1"),
        )
        assertEquals(
            "http://[::1]:47823",
            AgentApiHttp.formatEndpoint("::1"),
        )
        assertEquals(
            "http://[::1]:9999",
            AgentApiHttp.formatEndpoint("::1", 9999),
        )
    }

    @Test
    fun `header line allowance is bounded on both ends`() {
        assertTrue(AgentApiHttp.isAllowedHeaderLine(0))
        assertTrue(AgentApiHttp.isAllowedHeaderLine(AgentApiHttp.MAX_LINE_BYTES))
        assertFalse(AgentApiHttp.isAllowedHeaderLine(AgentApiHttp.MAX_LINE_BYTES + 1))
        assertFalse(AgentApiHttp.isAllowedHeaderLine(-1))
    }
}
