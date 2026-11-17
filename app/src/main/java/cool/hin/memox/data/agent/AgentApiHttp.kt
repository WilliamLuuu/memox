package cool.hin.memox.data.agent

/**
 * Pure HTTP helpers for the Agent API that have no Android dependencies and are safe to exercise
 * from JVM unit tests. Keeping the pre-auth parsing limits and URL formatting here makes the
 * security invariants directly testable without starting the server.
 */
object AgentApiHttp {
    const val DEFAULT_HOST = "127.0.0.1"
    const val PORT = 47823

    /** Maximum bytes in a single request/header line (request-line included). */
    const val MAX_LINE_BYTES = 8192

    /** Maximum total bytes across all header lines, including the terminating empty line. */
    const val MAX_HEADER_BYTES = 32 * 1024

    /** Maximum number of header lines, including the terminating empty line. */
    const val MAX_HEADER_COUNT = 100

    /** Maximum wall-clock time to read one whole request, including headers and body. */
    const val REQUEST_DEADLINE_MS = 30_000L

    /** Formats a bind host for use as an HTTP URL authority (IPv6 needs brackets). */
    fun formatEndpointHost(host: String): String {
        val trimmed = host.trim().ifEmpty { DEFAULT_HOST }
        return if (trimmed.contains(':')) "[$trimmed]" else trimmed
    }

    /** Builds the full base URL advertised in the pairing dialog. */
    fun formatEndpoint(host: String, port: Int = PORT): String =
        "http://${formatEndpointHost(host)}:$port"

    /** Whether [lineLength] is within the per-line pre-auth allowance. */
    fun isAllowedHeaderLine(lineLength: Int): Boolean = lineLength in 0..MAX_LINE_BYTES
}
