package com.wireautomessenger.api

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Wire API Manager for sending broadcast messages using Wire's REST API
 * 
 * Prerequisites:
 * 1. Get App ID and Token from Wire Team Settings → Apps → Create New App
 * 2. Configure these in the app settings
 */
class WireApiManager(private val context: Context) {
    
    companion object {
        private const val TAG = "WireApiManager"
        private const val WIRE_API_BASE_URL = "https://prod-nginz-https.wire.com" // Production API
        // Alternative: "https://staging-nginz-https.zinfra.io" for staging
        
        // API Endpoints
        private const val ENDPOINT_CONVERSATIONS = "/conversations"
        private const val ENDPOINT_MESSAGES = "/messages"
        private const val ENDPOINT_USERS = "/users"
        
        // Headers
        private const val HEADER_AUTHORIZATION = "Authorization"
        private const val HEADER_CONTENT_TYPE = "Content-Type"
        private const val CONTENT_TYPE_JSON = "application/json"
    }
    
    private val client = OkHttpClient.Builder()
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        })
        .build()
    
    private val gson = Gson()
    
    /**
     * Send a message to a single user
     */
    suspend fun sendMessageToUser(
        appId: String,
        apiToken: String,
        userId: String,
        message: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            // Step 1: Get or create conversation with the user
            val conversationId = getOrCreateConversation(appId, apiToken, userId)
                ?: return@withContext Result.failure(Exception("Failed to get/create conversation"))
            
            // Step 2: Send message to the conversation
            val messageId = sendMessageToConversation(appId, apiToken, conversationId, message)
                ?: return@withContext Result.failure(Exception("Failed to send message"))
            
            Log.i(TAG, "Message sent successfully to user $userId: $messageId")
            Result.success(messageId)
        } catch (e: Exception) {
            Log.e(TAG, "Error sending message to user $userId", e)
            Result.failure(e)
        }
    }
    
    /**
     * Send broadcast message to multiple users
     */
    suspend fun sendBroadcastMessage(
        appId: String,
        apiToken: String,
        userIds: List<String>,
        message: String,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): Result<BroadcastResult> = withContext(Dispatchers.IO) {
        var successCount = 0
        var failureCount = 0
        val errors = mutableListOf<String>()
        
        userIds.forEachIndexed { index, userId ->
            onProgress(index + 1, userIds.size)
            
            when (val result = sendMessageToUser(appId, apiToken, userId, message)) {
                is Result.Success -> {
                    successCount++
                    Log.d(TAG, "Sent to user ${index + 1}/${userIds.size}: $userId")
                }
                is Result.Failure -> {
                    failureCount++
                    val error = "User $userId: ${result.exception.message}"
                    errors.add(error)
                    Log.w(TAG, "Failed to send to user $userId: ${result.exception.message}")
                }
            }
            
            // Small delay to avoid rate limiting
            kotlinx.coroutines.delay(500)
        }
        
        val result = BroadcastResult(
            total = userIds.size,
            success = successCount,
            failed = failureCount,
            errors = errors
        )
        
        Result.success(result)
    }
    
    /**
     * Get or create a conversation with a user
     */
    private suspend fun getOrCreateConversation(
        appId: String,
        apiToken: String,
        userId: String
    ): String? = withContext(Dispatchers.IO) {
        try {
            // Try to find existing conversation first
            // Note: Wire API may require listing conversations or creating new one
            // This is a simplified version - actual implementation may vary
            
            val requestBody = CreateConversationRequest(
                users = listOf(userId),
                name = null,
                access = listOf("private")
            )
            
            val json = gson.toJson(requestBody)
            val body = json.toRequestBody(CONTENT_TYPE_JSON.toMediaType())
            
            val request = Request.Builder()
                .url("$WIRE_API_BASE_URL$ENDPOINT_CONVERSATIONS")
                .addHeader(HEADER_AUTHORIZATION, "Bearer $apiToken")
                .addHeader(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
                .post(body)
                .build()
            
            val response = client.newCall(request).execute()
            
            if (response.isSuccessful) {
                val responseBody = response.body?.string()
                val conversation = gson.fromJson(responseBody, ConversationResponse::class.java)
                conversation.id
            } else {
                Log.e(TAG, "Failed to create conversation: ${response.code} - ${response.message}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating conversation", e)
            null
        }
    }
    
    /**
     * Send a message to a conversation
     */
    private suspend fun sendMessageToConversation(
        appId: String,
        apiToken: String,
        conversationId: String,
        message: String
    ): String? = withContext(Dispatchers.IO) {
        try {
            val requestBody = SendMessageRequest(
                content = message,
                type = "text"
            )
            
            val json = gson.toJson(requestBody)
            val body = json.toRequestBody(CONTENT_TYPE_JSON.toMediaType())
            
            val request = Request.Builder()
                .url("$WIRE_API_BASE_URL$ENDPOINT_CONVERSATIONS/$conversationId$ENDPOINT_MESSAGES")
                .addHeader(HEADER_AUTHORIZATION, "Bearer $apiToken")
                .addHeader(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
                .post(body)
                .build()
            
            val response = client.newCall(request).execute()
            
            if (response.isSuccessful) {
                val responseBody = response.body?.string()
                val messageResponse = gson.fromJson(responseBody, MessageResponse::class.java)
                messageResponse.id
            } else {
                val errorBody = response.body?.string()
                Log.e(TAG, "Failed to send message: ${response.code} - $errorBody")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending message", e)
            null
        }
    }
    
    /**
     * Get list of team members/users
     */
    suspend fun getTeamMembers(
        appId: String,
        apiToken: String
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$WIRE_API_BASE_URL$ENDPOINT_USERS")
                .addHeader(HEADER_AUTHORIZATION, "Bearer $apiToken")
                .get()
                .build()
            
            val response = client.newCall(request).execute()
            
            if (response.isSuccessful) {
                val responseBody = response.body?.string()
                val usersResponse = gson.fromJson(responseBody, UsersResponse::class.java)
                val userIds = usersResponse.users?.map { it.id } ?: emptyList()
                Result.success(userIds)
            } else {
                val errorBody = response.body?.string()
                Log.e(TAG, "Failed to get team members: ${response.code} - $errorBody")
                Result.failure(Exception("Failed to get team members: ${response.code}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting team members", e)
            Result.failure(e)
        }
    }
    
    // Data classes for API requests/responses
    private data class CreateConversationRequest(
        val users: List<String>,
        val name: String?,
        val access: List<String>
    )
    
    private data class SendMessageRequest(
        val content: String,
        val type: String
    )
    
    private data class ConversationResponse(
        val id: String,
        val name: String? = null
    )
    
    private data class MessageResponse(
        val id: String,
        val time: String? = null
    )
    
    private data class UsersResponse(
        val users: List<User>? = null
    )
    
    private data class User(
        val id: String,
        val name: String? = null
    )
    
    data class BroadcastResult(
        val total: Int,
        val success: Int,
        val failed: Int,
        val errors: List<String>
    )
}

