package io.stridehub.app.nativeui

import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Validate the API boundary before business parsing; never display an upstream page or URL. */
object NativeApiResponse {
    fun read(status: Int, contentType: String?, body: String, login: Boolean = false, expectJson: Boolean = true): String {
        if (status in 300..399) {
            throw ApiFailure(status, "服务器返回重定向（HTTP $status）。请使用最终服务器地址，或检查公网代理配置。")
        }
        if (status == 204 && body.isBlank()) return ""
        val mediaType = contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        val jsonType = mediaType == "application/json" || (mediaType.startsWith("application/") && mediaType.endsWith("+json"))
        val successful = status in 200..299
        if (!expectJson && successful) {
            if (mediaType == "text/html" || mediaType == "application/xhtml+xml" || body.trimStart().startsWith("<!doctype html", true) || body.trimStart().startsWith("<html", true)) {
                throw unexpected(status)
            }
            return body
        }
        val json = if (jsonType) runCatching {
            val reader = JSONTokener(body)
            val parsed = reader.nextValue()
            require(parsed is JSONObject && reader.nextClean() == '\u0000')
            parsed
        }.getOrNull() else null
        if (json == null) throw unexpected(status)
        if (!successful) {
            val source = json.optJSONObject("error")?.opt("message") ?: json.opt("message")
            val message = if (source is String) safeMessage(source) else ""
            throw ApiFailure(status, when (status) {
                401 -> if (login) "用户名或密码错误，请核对后重试。" else "登录已过期，请重新连接。"
                404 -> if (login) "服务器未提供移动登录接口（HTTP 404）。请检查公网代理转发、服务器地址和后端版本。" else message.ifBlank { "服务器缺少此移动接口（HTTP 404），请检查地址或后端版本。" }
                409, 428 -> message.ifBlank { "记录已改变。草稿已保留，请核对服务器最新内容后再保存。" }
                429 -> "请求过于频繁（HTTP 429），请稍后重试。"
                else -> "服务器返回 HTTP $status。" + message.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
            })
        }
        return body
    }

    fun networkFailure(error: IOException): IOException = IOException(when (error) {
        is UnknownHostException -> "无法解析服务器域名，请检查服务器地址与网络连接。"
        is SSLException -> "HTTPS 握手或证书验证失败，请检查服务器证书、域名与完整证书链。"
        is SocketTimeoutException -> "连接或读取服务器超时，请检查网络后重试。"
        is ConnectException -> "无法连接服务器，请检查公网端口与网络连接。"
        else -> "网络请求失败，请检查连接后重试。"
    })

    private fun unexpected(status: Int): ApiFailure = ApiFailure(status,
        "服务器返回的不是有效 JSON（HTTP $status），可能是网页或代理错误页，未到达移动接口。请检查公网代理的移动接口转发与服务器地址。")

    private fun safeMessage(input: String): String {
        // Even a JSON error can contain a proxy HTML page or sensitive diagnostic context.
        if (input.contains('<') && input.contains('>')) return ""
        return input
            .replace(Regex("(?i)https?://[^\\s<>\"']+"), "[地址已隐藏]")
            .replace(Regex("(?i)Bearer\\s+[a-z0-9_.+~/-]+=*"), "Bearer [凭据已隐藏]")
            .replace(Regex("(?:shmobile_|shdevice_|drv_)[A-Za-z0-9_.+~/-]+=*"), "[凭据已隐藏]")
            .replace(Regex("(?i)(password|token|api[_-]?key|secret|authorization)\\s*[:=]\\s*[^\\s,;]+"), "$1=[凭据已隐藏]")
            .replace(Regex("\\b(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?::[0-9]{1,5})?\\b"), "[地址已隐藏]")
            .replace(Regex("(?i)\\[[a-f0-9:]+\\](?::[0-9]{1,5})?"), "[地址已隐藏]")
            .replace(Regex("(?i)\\b(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.)+[a-z]{2,63}(?::[0-9]{1,5})?\\b"), "[地址已隐藏]")
            .replace(Regex("[\\x00-\\x1f\\x7f]"), " ").trim().take(600)
    }
}
