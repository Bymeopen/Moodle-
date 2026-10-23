package id.pribadi.minilms

import org.json.JSONObject
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/** Server HTTP/1.1 minimal untuk tes: balasan berurutan per permintaan (yang terakhir diulang). Mencatat path, header, dan body. */
class FakeHttp(vararg replies: Pair<Int, String>) : AutoCloseable {
    data class Req(val path: String, val headers: Map<String, String>, val body: JSONObject)
    val seen = mutableListOf<Req>()
    private val ss = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
    val url = "http://127.0.0.1:${ss.localPort}"

    init {
        var n = 0
        thread(isDaemon = true) {
            while (!ss.isClosed) {
                val sock = try { ss.accept() } catch (e: Exception) { break }
                sock.use {
                    val inp = it.getInputStream().buffered()
                    fun line(): String { val sb = StringBuilder(); while (true) { val c = inp.read(); if (c < 0 || c == '\n'.code) break; if (c != '\r'.code) sb.append(c.toChar()) }; return sb.toString() }
                    val path = line().split(' ').getOrElse(1) { "" }
                    val headers = mutableMapOf<String, String>()
                    while (true) { val l = line(); if (l.isEmpty()) break; headers[l.substringBefore(':').lowercase()] = l.substringAfter(':').trim() }
                    val body = ByteArray(headers["content-length"]!!.toInt()).also { buf -> var off = 0; while (off < buf.size) off += inp.read(buf, off, buf.size - off) }
                    seen += Req(path, headers, JSONObject(body.decodeToString()))
                    val (code, text) = replies[minOf(n++, replies.size - 1)]
                    val bytes = text.toByteArray()
                    it.getOutputStream().apply { write("HTTP/1.1 $code X\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(bytes); flush() }
                }
            }
        }
    }
    override fun close() = ss.close()
}
