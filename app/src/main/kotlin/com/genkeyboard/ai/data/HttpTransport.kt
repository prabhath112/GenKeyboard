/*
 * Copyright (C) 2026 GenKeyboard authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.genkeyboard.ai.data

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class HttpResponse(val code: Int, val body: String)

/** Minimal HTTP seam so the repository is unit-testable without a network. */
interface HttpTransport {
    @Throws(IOException::class)
    suspend fun post(url: String, headers: Map<String, String>, body: String): HttpResponse
}

/**
 * ponytail: HttpURLConnection + kotlinx.serialization. Zero new dependencies.
 * Upgrade path: OkHttp if we need connection pooling, streaming or certificate pinning.
 */
class UrlConnectionTransport(
    private val timeoutMs: Int = 25_000,
    private val allowCleartext: Boolean = false,
) : HttpTransport {

    override suspend fun post(url: String, headers: Map<String, String>, body: String): HttpResponse =
        withContext(Dispatchers.IO) {
            require(allowCleartext || url.startsWith("https://")) { "AI backend URL must use https" }
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setRequestProperty("Accept", "application/json")
                headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

                val code = conn.responseCode
                val stream = if (code >= 400) conn.errorStream else conn.inputStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                HttpResponse(code, text)
            } finally {
                conn.disconnect()
            }
        }
}
