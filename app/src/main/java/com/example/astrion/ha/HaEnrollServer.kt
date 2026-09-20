package com.example.astrion.ha

import com.example.astrion.utils.getLocalIpAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 临时网页配对服务（原版接入思路）：面板屏幕太小没法输入 180 字符的长寿命
 * 访问令牌，所以在面板上临时开一个 HTTP 服务，用户拿手机/电脑浏览器打开
 * `http://<面板IP>:8917`，在表单里粘贴 HA 地址与令牌提交，面板保存后立即重连。
 *
 * 生命周期：未配置时由 [com.example.astrion.services.PanelService] 自动开启，
 * 配置成功自动关闭；连接设置页也可以手动开关（重新配对用）。只在可信局域网使用。
 */
@Singleton
class HaEnrollServer @Inject constructor(
    private val settingsStore: HaConnectionSettingsStore,
    private val panelBridge: HaPanelBridge,
) {
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private var scope: CoroutineScope? = null
    private var serverSocket: ServerSocket? = null

    fun start() {
        if (_running.value) return
        val socket = runCatching {
            ServerSocket(PORT).apply { reuseAddress = true }
        }.getOrElse {
            Timber.w(it, "Enroll server bind failed on port %d", PORT)
            return
        }
        serverSocket = socket
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = newScope
        _running.value = true
        newScope.launch {
            while (isActive) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                launch {
                    runCatching { handleClient(client) }
                        .onFailure { Timber.d(it, "Enroll request failed") }
                }
            }
        }
        Timber.i("Enroll server started at http://%s:%d", getLocalIpAddress() ?: "?", PORT)
    }

    fun stop() {
        scope?.cancel()
        scope = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        _running.value = false
    }

    private suspend fun handleClient(socket: Socket) {
        socket.use { sock ->
            sock.soTimeout = 8_000
            val request = readRequest(sock) ?: return
            Timber.d("Enroll request: %s %s", request.method, request.path)
            when {
                request.method == "GET" ->
                    writeResponse(sock, 200, formPage())

                request.method == "POST" && request.path.startsWith("/save") -> {
                    // 先验证，验证通过才写入配置（写错配置面板就连不上 HA）
                    val form = parseForm(request.body)
                    val candidate = mergeForm(form)
                    val probe = when {
                        candidate.host.isBlank() || candidate.token.isBlank() ->
                            HaConnectionProbe.Result.Failure("地址与令牌都不能为空（已有令牌可留空）")

                        else -> HaConnectionProbe.test(
                            host = candidate.host,
                            port = candidate.port,
                            token = candidate.token,
                            useSsl = candidate.useSsl
                        )
                    }
                    when (probe) {
                        is HaConnectionProbe.Result.Ok -> {
                            settingsStore.update(
                                candidate.name, candidate.host, candidate.port,
                                candidate.token, candidate.useSsl
                            )
                            panelBridge.reconnect()
                            writeResponse(sock, 200, resultOkPage())
                            // 保存成功 = 配对完成，服务自停（重配对时可再手动开启）
                            stop()
                        }

                        is HaConnectionProbe.Result.Failure ->
                            writeResponse(sock, 200, resultFailPage(probe.reason))
                    }
                }

                else -> writeResponse(sock, 404, notFoundPage())
            }
        }
    }

    /** 表单 + 现有配置合成候选配置（令牌留空 = 保持现有令牌）。 */
    private suspend fun mergeForm(form: Map<String, String>): HaConnectionSettings {
        val current = settingsStore.get()
        return HaConnectionSettings(
            name = form["name"]?.trim().orEmpty().ifBlank { current.name },
            host = form["host"]?.trim().orEmpty(),
            port = form["port"]?.trim()?.toIntOrNull()?.coerceIn(1, 65535) ?: current.port,
            token = form["token"]?.trim().orEmpty().ifBlank { current.token },
            useSsl = form.containsKey("useSsl"),
            serialNumber = current.serialNumber
        )
    }

    // ── 最小 HTTP/1.1 解析 ──────────────────────────────────────────────

    private data class HttpRequest(val method: String, val path: String, val body: String)

    private fun readRequest(socket: Socket): HttpRequest? {
        val input = socket.getInputStream()
        val buffer = ByteArray(8192)
        var received = ByteArray(0)
        var headerEnd = -1
        while (headerEnd < 0 && received.size < 64_000) {
            val n = input.read(buffer)
            if (n < 0) break
            received += buffer.copyOf(n)
            headerEnd = findHeaderEnd(received)
        }
        if (headerEnd < 0) return null
        val headerText = String(received, 0, headerEnd, Charsets.ISO_8859_1)
        val lines = headerText.split("\r\n")
        val requestLine = lines.firstOrNull()?.split(" ") ?: return null
        if (requestLine.size < 2) return null
        val contentLength = lines.firstOrNull {
            it.lowercase().startsWith("content-length:")
        }?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
        var body = ByteArray(0)
        val bodyStart = headerEnd + 4
        if (received.size > bodyStart) body = received.copyOfRange(bodyStart, received.size)
        while (body.size < contentLength && body.size < 200_000) {
            val n = input.read(buffer)
            if (n < 0) break
            body += buffer.copyOf(n)
        }
        return HttpRequest(requestLine[0], requestLine[1], String(body, Charsets.UTF_8))
    }

    private fun findHeaderEnd(bytes: ByteArray): Int {
        for (i in 0 until bytes.size - 3) {
            if (bytes[i] == 13.toByte() && bytes[i + 1] == 10.toByte() &&
                bytes[i + 2] == 13.toByte() && bytes[i + 3] == 10.toByte()
            ) return i
        }
        return -1
    }

    private fun parseForm(body: String): Map<String, String> = buildMap {
        for (pair in body.split('&')) {
            if (!pair.contains('=')) continue
            val key = URLDecoder.decode(pair.substringBefore('='), Charsets.UTF_8.name())
            val value = URLDecoder.decode(pair.substringAfter('='), Charsets.UTF_8.name())
            put(key, value)
        }
    }

    private fun writeResponse(socket: Socket, code: Int, html: String) {
        val bytes = html.toByteArray(Charsets.UTF_8)
        val status = when (code) {
            200 -> "OK"
            404 -> "Not Found"
            else -> "Error"
        }
        val head = "HTTP/1.1 $code $status\r\n" +
                "Content-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n\r\n"
        socket.getOutputStream().apply {
            write(head.toByteArray(Charsets.ISO_8859_1))
            write(bytes)
            flush()
        }
    }

    // ── 配对页面 ────────────────────────────────────────────────────────

    private suspend fun formPage(): String {
        val settings = runCatching { settingsStore.get() }
            .getOrDefault(HaConnectionSettings())
        return """
            <!doctype html><html lang="zh"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>Astrion 面板 · 网页配对</title>
            <style>body{font-family:sans-serif;background:#141414;color:#eee;max-width:520px;
            margin:0 auto;padding:16px}label{display:block;font-size:13px;color:#aaa;margin-top:10px}
            input,textarea{width:100%;box-sizing:border-box;padding:10px;margin:4px 0 4px;background:#222;
            color:#eee;border:1px solid #444;border-radius:8px;font-size:15px}
            textarea{resize:vertical}button{width:100%;padding:13px;margin-top:16px;background:#4a7dff;
            color:#fff;border:0;border-radius:10px;font-size:16px}p{color:#aaa;font-size:13px}
            .chk{display:flex;align-items:center;gap:8px;margin-top:10px;color:#ccc;font-size:14px}
            .chk input{width:auto;margin:0}</style></head><body>
            <h2>Astrion 面板 · 网页配对</h2>
            <p>填写 Home Assistant 连接信息，提交后面板立即连接。令牌在 HA
            个人资料页 → 安全 → 长寿命访问令牌 创建。</p>
            <form method="post" action="/save">
            <label>设备名称</label>
            <input name="name" value="${escape(settings.name)}">
            <label>Home Assistant 地址</label>
            <input name="host" placeholder="172.16.1.1" value="${escape(settings.host)}">
            <label>端口</label>
            <input name="port" inputmode="numeric" value="${if (settings.port == 0) 8123 else settings.port}">
            <label>长寿命访问令牌${if (settings.token.isNotBlank()) "（留空 = 保持现有令牌）" else ""}</label>
            <textarea name="token" rows="4" placeholder="eyJhbGciOi..."></textarea>
            <div class="chk"><input type="checkbox" name="useSsl"${if (settings.useSsl) " checked" else ""}>
            使用 SSL (wss://)</div>
            <button type="submit">保存并连接</button>
            </form></body></html>
        """.trimIndent()
    }

    private fun resultOkPage(): String = """
        <!doctype html><html lang="zh"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1"><title>已保存</title>
        <style>body{font-family:sans-serif;background:#141414;color:#eee;max-width:520px;
        margin:0 auto;padding:16px}a{color:#4a7dff}</style></head><body>
        <h2>✅ 验证通过，已保存</h2><p>面板正在连接 Home Assistant，几秒后可在面板屏幕上
        查看连接状态（下拉快捷面板也能看到）。配对服务已自动关闭。</p>
        <p><a href="/">返回</a></p></body></html>
    """.trimIndent()

    private fun resultFailPage(reason: String): String = """
        <!doctype html><html lang="zh"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1"><title>验证失败</title>
        <style>body{font-family:sans-serif;background:#141414;color:#eee;max-width:520px;
        margin:0 auto;padding:16px}a{color:#4a7dff}p{color:#ccc}</style></head><body>
        <h2>❌ 验证失败（未保存）</h2><p>${escape(reason)}</p>
        <p>请检查 Home Assistant 地址、端口（默认 8123）与长寿命访问令牌。</p>
        <p><a href="/">返回重填</a></p></body></html>
    """.trimIndent()

    private fun notFoundPage(): String = """
        <!doctype html><html lang="zh"><head><meta charset="utf-8"><title>404</title></head>
        <body><p>Not found. <a href="/">返回配对页</a></p></body></html>
    """.trimIndent()

    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    companion object {
        /** 配对服务端口（面板自身，非 HA 端口）。 */
        const val PORT = 8917
    }
}
