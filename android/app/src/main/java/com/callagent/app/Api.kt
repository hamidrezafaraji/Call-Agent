package com.callagent.app

import android.content.ContentResolver
import android.net.Uri
import android.os.Build
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/** The server answered with an error status (as opposed to being unreachable). */
class ApiException(val code: Int, message: String) : IOException(message)

data class ActivationResult(val deviceId: String, val name: String, val token: String)

/** Contents of the activation QR code shown on the server's /admin page. */
data class QrPayload(val server: String, val code: String) {
    companion object {
        fun parse(text: String): QrPayload? = try {
            val j = JSONObject(text)
            val server = j.getString("server").trimEnd('/')
            val code = j.getString("code")
            if (j.optInt("v") == 1 && server.startsWith("http")) QrPayload(server, code) else null
        } catch (e: Exception) {
            null
        }
    }
}

class Api(baseUrl: String, private val token: String? = null) {
    private val base = baseUrl.trimEnd('/')

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.MINUTES) // long recordings on slow Wi-Fi
                .build()
        }

        private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")

        /** ISO 8601 with the phone's UTC offset, e.g. 2026-10-09T10:30:00+03:30 */
        fun isoTime(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
            Instant.ofEpochMilli(millis).atZone(zone).format(ISO)
    }

    private fun Request.Builder.auth(): Request.Builder =
        if (token != null) header("Authorization", "Bearer $token") else this

    private fun execute(request: Request): String =
        client.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val detail = try {
                    JSONObject(body).optString("detail", body)
                } catch (e: Exception) {
                    body
                }
                throw ApiException(resp.code, detail.take(300).ifBlank { "HTTP ${resp.code}" })
            }
            body
        }

    fun activate(code: String): ActivationResult {
        val payload = JSONObject()
            .put("code", code.trim())
            .put("brand", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .put("android_version", Build.VERSION.RELEASE)
            .put("app_version", BuildConfig.VERSION_NAME)
        val req = Request.Builder()
            .url("$base/api/devices/activate")
            .post(payload.toString().toRequestBody(JSON))
            .build()
        val j = JSONObject(execute(req))
        return ActivationResult(j.getString("device_id"), j.getString("name"), j.getString("token"))
    }

    fun uploadCall(call: PendingCall, resolver: ContentResolver) {
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("id", call.id)
            .addFormDataPart("direction", call.direction)
            .addFormDataPart("phone_number", call.number)
            .addFormDataPart("started_at", isoTime(call.startedAt))
            .addFormDataPart("duration_sec", call.durationSec.toString())
        if (call.audioUri != null) {
            val name = call.audioName ?: "recording.m4a"
            form.addFormDataPart("audio", name, UriBody(resolver, Uri.parse(call.audioUri), call.audioSize))
        }
        val req = Request.Builder().url("$base/api/calls").auth().post(form.build()).build()
        execute(req)
    }
}

/** Streams a content:// file into the request without loading it into memory. */
private class UriBody(
    private val resolver: ContentResolver,
    private val uri: Uri,
    private val size: Long,
) : RequestBody() {
    override fun contentType() = "application/octet-stream".toMediaType()
    override fun contentLength() = if (size > 0) size else -1L
    override fun writeTo(sink: BufferedSink) {
        val input = resolver.openInputStream(uri) ?: throw IOException("cannot open recording")
        input.source().use { sink.writeAll(it) }
    }
}
