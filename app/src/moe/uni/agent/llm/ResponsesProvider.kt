package moe.uni.agent.llm

import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.CancellationException
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException
import moe.uni.agent.AgentFailure
import moe.uni.agent.settings.SettingsStore
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** OpenAI Responses 协议（ADR-0003）：store=false，每轮发送完整历史，以 SSE 流式接收。不记录请求、密钥和响应。 */
class ResponsesProvider(private val config: SettingsStore.Config, private val apiKey: String) : Provider {
    @Volatile private var cancelled = false
    @Volatile private var connection: HttpsURLConnection? = null

    override fun cancel() {
        cancelled = true
        connection?.disconnect()
    }

    override fun respond(input: JSONArray, tools: JSONArray, instructions: String, onText: (String) -> Unit): Reply =
        request(input, tools, instructions, onText, allowTools = true)

    override fun respondText(input: JSONArray, tools: JSONArray, instructions: String): Reply =
        request(input, tools, instructions, {}, allowTools = false)

    private fun request(input: JSONArray, tools: JSONArray, instructions: String, onText: (String) -> Unit, allowTools: Boolean): Reply {
        val request = JSONObject().put(Responses.MODEL, config.model).put(Responses.STORE, false).put(Responses.STREAM, true)
            .put(Responses.INPUT, input).put(Responses.TOOLS, tools).put(Responses.PARALLEL_TOOL_CALLS, false)
            .put(Responses.INSTRUCTIONS, instructions)
        if (!allowTools) request.put(Responses.TOOL_CHOICE, Responses.TOOL_CHOICE_NONE)
        if (config.reasoning) request.put(Responses.INCLUDE, JSONArray().put(Responses.INCLUDE_ENCRYPTED_REASONING))
        if (config.effort.isNotEmpty()) request.put(Responses.REASONING, JSONObject().put(Responses.EFFORT, config.effort))

        val conn = URL(config.baseUrl + PATH).openConnection() as HttpsURLConnection
        connection = conn
        try {
            if (cancelled) throw CancellationException()
            conn.requestMethod = "POST"
            conn.instanceFollowRedirects = false
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            // 流式时是两次收到数据之间的最长间隔。
            conn.readTimeout = READ_TIMEOUT_MS
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", CONTENT_TYPE_JSON)
            conn.setRequestProperty("Accept", CONTENT_TYPE_SSE)
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.outputStream.use { it.write(request.toString().toByteArray()) }
            val status = conn.responseCode
            if (status !in HTTP_OK) throw httpFailure(status, serverError(conn) ?: JSONObject())
            return conn.inputStream.use { body ->
                // 不支持流式的端点会忽略 stream，直接返回完整的 JSON。
                if (conn.contentType.orEmpty().startsWith(CONTENT_TYPE_SSE)) stream(body, onText)
                else reply(JSONObject(body.readBytes().toString(Charsets.UTF_8)), JSONArray())
            }
        } catch (e: Exception) {
            if (cancelled) throw CancellationException()
            throw if (e is AgentFailure) e else failure(e, conn.url.host)
        } finally {
            connection = null
            conn.disconnect()
        }
    }

    private fun stream(body: InputStream, onText: (String) -> Unit): Reply {
        // 有的端点在终止事件里不带 output，就用逐项完成的 item 拼出来。
        val done = sortedMapOf<Int, JSONObject>()
        for (data in sse(body)) {
            if (data == Responses.SSE_DONE) continue
            val event = JSONObject(data)
            when (event.optString(Responses.TYPE)) {
                Responses.EVENT_TEXT_DELTA -> onText(event.optString(Responses.DELTA))
                Responses.EVENT_ITEM_DONE -> done[event.optInt(Responses.OUTPUT_INDEX, done.size)] = event.getJSONObject(Responses.ITEM)
                Responses.EVENT_COMPLETED, Responses.EVENT_INCOMPLETE, Responses.EVENT_FAILED ->
                    return reply(event.getJSONObject(Responses.RESPONSE), JSONArray(done.values))
                Responses.EVENT_ERROR -> throw modelFailure(event.optJSONObject(Responses.ERROR) ?: event)
            }
        }
        // 连接在回复完成前关闭。
        throw AgentFailure(AgentFailure.Reason.CONNECTION_LOST)
    }

    private fun reply(response: JSONObject, streamed: JSONArray): Reply {
        if (response.optString(Responses.STATUS) == Responses.STATUS_FAILED) {
            throw modelFailure(response.optJSONObject(Responses.ERROR) ?: JSONObject())
        }
        val output = response.optJSONArray(Responses.OUTPUT)?.takeIf { it.length() > 0 } ?: streamed
        val incomplete = response.optString(Responses.STATUS) == Responses.STATUS_INCOMPLETE
        val maxTokens = response.optJSONObject(Responses.INCOMPLETE_DETAILS)?.optString(Responses.REASON) == Responses.REASON_MAX_OUTPUT_TOKENS
        val usage = response.optJSONObject(Responses.USAGE)
        val tokens = (usage?.optLong(Responses.INPUT_TOKENS) ?: 0) + (usage?.optLong(Responses.OUTPUT_TOKENS) ?: 0)
        return Reply(output, incomplete && maxTokens, incomplete && !maxTokens, tokens)
    }

    /** 逐个取出 SSE 事件的 data。type 已经在 data 的 JSON 里，event、id、retry 和注释行都用不上。 */
    private fun sse(body: InputStream): Sequence<String> = sequence {
        val reader = body.bufferedReader(Charsets.UTF_8)
        val data = StringBuilder()
        var hasData = false
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) {
                if (hasData) yield(data.toString())
                data.clear()
                hasData = false
            } else if (line.startsWith(Responses.SSE_DATA)) {
                if (hasData) data.append('\n')
                data.append(line.substring(Responses.SSE_DATA.length).removePrefix(" "))
                hasData = true
            }
        }
        if (hasData) yield(data.toString())
    }

    /** 服务端 JSON 里的 error 对象；取不到时为 null。 */
    private fun serverError(conn: HttpsURLConnection): JSONObject? = try {
        val text = conn.errorStream?.use { it.readBytes() }?.toString(Charsets.UTF_8).orEmpty()
        JSONObject(text).optJSONObject(Responses.ERROR)
    } catch (_: Exception) {
        null
    }

    /** 回复过程中的失败；上下文超长单独区分，loop 会先压缩再重发（ADR-0028 第 3 条）。 */
    private fun modelFailure(error: JSONObject): AgentFailure =
        if (error.optString(Responses.CODE) == Responses.CODE_CONTEXT_LENGTH_EXCEEDED) AgentFailure(AgentFailure.Reason.CONTEXT_OVERFLOW)
        else AgentFailure(AgentFailure.Reason.MODEL_FAILED, errorMessage(error))

    /** detail 是状态码加服务端的说明（error.message）。 */
    private fun httpFailure(status: Int, error: JSONObject): AgentFailure {
        if (error.optString(Responses.CODE) == Responses.CODE_CONTEXT_LENGTH_EXCEEDED) return AgentFailure(AgentFailure.Reason.CONTEXT_OVERFLOW)
        val message = errorMessage(error)
        val reason = when (status) {
            HttpURLConnection.HTTP_UNAUTHORIZED, HttpURLConnection.HTTP_FORBIDDEN -> AgentFailure.Reason.UNAUTHORIZED
            HttpURLConnection.HTTP_NOT_FOUND -> AgentFailure.Reason.NOT_FOUND
            HTTP_TOO_MANY_REQUESTS -> AgentFailure.Reason.RATE_LIMITED
            in HTTP_SERVER_ERROR -> AgentFailure.Reason.SERVER
            else -> AgentFailure.Reason.REJECTED
        }
        return AgentFailure(reason, listOf("HTTP $status", message).filter { it.isNotEmpty() }.joinToString(" · "))
    }

    /** 连接和解析时的异常换成给用户看的原因，原始异常作为 cause 留给日志。 */
    private fun failure(e: Exception, host: String): AgentFailure = when (e) {
        is UnknownHostException -> AgentFailure(AgentFailure.Reason.HOST_UNRESOLVED, host, e)
        is ConnectException, is NoRouteToHostException -> AgentFailure(AgentFailure.Reason.NETWORK, host, e)
        is SocketTimeoutException -> AgentFailure(AgentFailure.Reason.TIMEOUT, cause = e)
        is SSLException -> AgentFailure(AgentFailure.Reason.TLS, host, e)
        is IOException -> AgentFailure(AgentFailure.Reason.CONNECTION_LOST, cause = e)
        is JSONException -> AgentFailure(AgentFailure.Reason.BAD_RESPONSE, cause = e)
        else -> AgentFailure(AgentFailure.Reason.UNEXPECTED, cause = e)
    }

    private fun errorMessage(error: JSONObject) = error.optString(Responses.MESSAGE).take(ERROR_MESSAGE_MAX)

    companion object {
        private const val PATH = "/responses"
        private const val CONTENT_TYPE_JSON = "application/json"
        private const val CONTENT_TYPE_SSE = "text/event-stream"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 120_000
        private const val ERROR_MESSAGE_MAX = 200
        private val HTTP_OK = 200..299
        private val HTTP_SERVER_ERROR = 500..599
        private const val HTTP_TOO_MANY_REQUESTS = 429
    }
}
