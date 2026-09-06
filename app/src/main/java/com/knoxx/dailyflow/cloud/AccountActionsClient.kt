package com.knoxx.dailyflow.cloud

import com.knoxx.dailyflow.BuildConfig
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Calls the owner-only account-action endpoints on mcp-auth (rotate the
 * connector password, revoke every issued MCP session, delete the synced
 * cloud copy of memories). Same OkHttp conventions as GeminiExtractionClient
 * — this is the only other place the app makes a raw HTTPS call outside the
 * Supabase SDK, since these endpoints live on mcp-auth (Deno Deploy), not
 * Supabase itself.
 */
object AccountActionsClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun rotatePassword(oldPassword: String, newPassword: String): Result<Int> =
        post("/account/rotate-password", RotateBody(oldPassword, newPassword)) { it.revokedSessions ?: 0 }

    suspend fun revokeAccess(password: String): Result<Int> =
        post("/account/revoke", PasswordBody(password)) { it.revokedSessions ?: 0 }

    suspend fun deleteCloudData(password: String): Result<Long> =
        post("/account/delete-data", PasswordBody(password)) { it.deletedMemories ?: 0L }

    private suspend fun <T> post(path: String, body: Any, extract: (ActionResponse) -> T): Result<T> =
        withContext(Dispatchers.IO) {
            try {
                val bodyJson = when (body) {
                    is RotateBody -> json.encodeToString(RotateBody.serializer(), body)
                    is PasswordBody -> json.encodeToString(PasswordBody.serializer(), body)
                    else -> error("Unsupported body type")
                }
                val request = Request.Builder()
                    .url("${BuildConfig.MCP_AUTH_URL}$path")
                    .post(bodyJson.toRequestBody("application/json".toMediaType()))
                    .build()

                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val message = runCatching { json.decodeFromString<ErrorResponse>(responseBody) }
                            .getOrNull()?.errorDescription ?: "Request failed (${response.code})"
                        return@withContext Result.failure(IOException(message))
                    }
                    val parsed = json.decodeFromString<ActionResponse>(responseBody)
                    Result.success(extract(parsed))
                }
            } catch (e: IOException) {
                Result.failure(e)
            }
        }
}

@Serializable
private data class RotateBody(
    @kotlinx.serialization.SerialName("old_password") val oldPassword: String,
    @kotlinx.serialization.SerialName("new_password") val newPassword: String
)

@Serializable
private data class PasswordBody(val password: String)

@Serializable
private data class ActionResponse(
    val ok: Boolean = false,
    @kotlinx.serialization.SerialName("revoked_sessions") val revokedSessions: Int? = null,
    @kotlinx.serialization.SerialName("deleted_memories") val deletedMemories: Long? = null
)

@Serializable
private data class ErrorResponse(
    val error: String? = null,
    @kotlinx.serialization.SerialName("error_description") val errorDescription: String? = null
)
