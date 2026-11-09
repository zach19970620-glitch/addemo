package com.haokai.addemo.device

data class DeviceSession(
    val smartDeviceId: String,
    val deviceToken: String,
    val tokenExpiresAtEpochMs: Long,
)

data class CredentialVersion(
    val version: Int,
    val apiKey: String,
    val expiresAt: String? = null,
)

data class AsrCredentialBundle(
    val status: String,
    val websocketUrl: String,
    val resourceId: String,
    val recognitionMode: String? = null,
    val current: CredentialVersion? = null,
    val next: CredentialVersion? = null,
    val retryAfterMs: Long? = null,
    val errorCode: String? = null,
)

data class VoiceTaskCreated(
    val voiceTaskId: String,
    val clientRequestId: String,
    val providerRequestId: String,
    val catalogVersion: String? = null,
    val asrFinalDueAt: String? = null,
)

data class InteractionDecision(
    val matchState: String? = null,
    val intentCode: String? = null,
)

data class InteractionFeedback(
    val messageKey: String? = null,
    val retryAllowed: Boolean = false,
)

data class MediaPayload(
    val assetId: String? = null,
    val url: String? = null,
    val expiresAt: String? = null,
    val mimeType: String? = null,
)

data class PresentationDirective(
    val directiveId: String? = null,
    val type: String? = null,
    val executionMode: String? = null,
    val sequence: Int = 0,
    val payload: MediaPayload? = null,
)

data class AvailableOperation(
    val operationId: String? = null,
    val type: String? = null,
    val requiresConfirmation: Boolean = false,
    val payload: Map<String, Any?>? = null,
)

data class InteractionPlan(
    val schemaVersion: String? = null,
    val voiceTaskId: String? = null,
    val presentationDirectives: List<PresentationDirective> = emptyList(),
    val availableOperations: List<AvailableOperation> = emptyList(),
)

data class InteractionResult(
    val schemaVersion: String? = null,
    val voiceTaskId: String? = null,
    val catalogVersion: String? = null,
    val resultStatus: String? = null,
    val decision: InteractionDecision? = null,
    val feedback: InteractionFeedback? = null,
    val interactionPlan: InteractionPlan? = null,
)

sealed class DeviceApiException(message: String) : Exception(message) {
    class Http(val httpCode: Int, val businessCode: String?, message: String) :
        DeviceApiException("HTTP $httpCode code=$businessCode msg=$message")

    class Protocol(message: String) : DeviceApiException(message)
}
