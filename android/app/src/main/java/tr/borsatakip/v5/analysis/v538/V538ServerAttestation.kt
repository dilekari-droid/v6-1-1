package tr.borsatakip.v5.analysis.v538

import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** Public-key binding is provider-specific and generation-specific. */
data class V538TrustedKey(
    val providerId: String,
    val keyId: String,
    val generation: Long,
    val publicKey: PublicKey
)

/** V5.3.8 signed envelope. */
data class V538AttestationEnvelope(
    val snapshotHash: String,
    val requestId: String,
    val snapshotId: String,
    val providerId: String,
    val attestationKeyId: String,
    val attestationKeyGeneration: Long,
    val serverEpoch: String,
    val serverEpochCreatedAt: Long,
    val serverTime: Long,
    val generatedAt: Long,
    val serverSequence: Long,
    val nonce: String,
    val calculationEngineVersion: String,
    val issuedAt: Long,
    val expiresAt: Long
) {
    fun canonical(): String = buildString {
        append("schema=V538_ATTESTATION_V1\n")
        append("snapshotHash=").append(snapshotHash.trim().lowercase()).append('\n')
        append("requestId=").append(requestId.trim()).append('\n')
        append("snapshotId=").append(snapshotId.trim()).append('\n')
        append("providerId=").append(providerId.trim()).append('\n')
        append("attestationKeyId=").append(attestationKeyId.trim()).append('\n')
        append("attestationKeyGeneration=").append(attestationKeyGeneration).append('\n')
        append("serverEpoch=").append(serverEpoch.trim()).append('\n')
        append("serverEpochCreatedAt=").append(serverEpochCreatedAt).append('\n')
        append("serverTime=").append(serverTime).append('\n')
        append("generatedAt=").append(generatedAt).append('\n')
        append("serverSequence=").append(serverSequence).append('\n')
        append("nonce=").append(nonce.trim()).append('\n')
        append("engine=").append(calculationEngineVersion.trim()).append('\n')
        append("issuedAt=").append(issuedAt).append('\n')
        append("expiresAt=").append(expiresAt)
    }
}

enum class V538AttestationStatus { NOT_CONFIGURED, VERIFIED, REJECTED }
data class V538AttestationResult(val status: V538AttestationStatus, val reason: String)

class V538TrustedKeyRegistry private constructor(private val keys: Map<String, V538TrustedKey>) {
    private fun mapKey(providerId: String, keyId: String, generation: Long): String =
        "${providerId.trim().lowercase()}|${keyId.trim()}|$generation"

    fun get(providerId: String, keyId: String, generation: Long): V538TrustedKey? =
        keys[mapKey(providerId, keyId, generation)]

    fun isConfigured(): Boolean = keys.isNotEmpty()

    companion object {
        /** Semicolon-separated: providerId:keyId:keyGeneration:base64(X.509 EC public key). */
        fun parse(raw: String): V538TrustedKeyRegistry {
            val parsed = linkedMapOf<String, V538TrustedKey>()
            raw.split(';').map { it.trim() }.filter { it.isNotBlank() }.forEach { entry ->
                val parts = entry.split(':', limit = 4)
                if (parts.size != 4) return@forEach
                val providerId = parts[0].trim()
                val keyId = parts[1].trim()
                val generation = parts[2].trim().toLongOrNull() ?: return@forEach
                val b64 = parts[3].trim()
                if (providerId.isBlank() || keyId.isBlank() || generation <= 0L) return@forEach
                val publicKey = runCatching {
                    val bytes = Base64.getDecoder().decode(b64)
                    KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))
                }.getOrNull() ?: return@forEach
                val trusted = V538TrustedKey(providerId, keyId, generation, publicKey)
                parsed["${providerId.lowercase()}|$keyId|$generation"] = trusted
            }
            return V538TrustedKeyRegistry(parsed)
        }
    }
}

object V538ServerAttestationVerifier {
    const val ALGORITHM = "SHA256withECDSA"
    const val MAX_SIGNATURE_LIFETIME_MS = 120_000L
    private val EPOCH_ID = Regex("[A-Za-z0-9_-]{22,128}")

    fun verify(
        registry: V538TrustedKeyRegistry,
        envelope: V538AttestationEnvelope,
        keyId: String,
        keyGeneration: Long,
        algorithm: String,
        signatureBase64: String,
        trustedNowMillis: Long,
        allowedClockSkewMs: Long
    ): V538AttestationResult {
        if (!registry.isConfigured()) return result(V538AttestationStatus.NOT_CONFIGURED, "NO_TRUSTED_PUBLIC_KEY")
        if (algorithm != ALGORITHM) return rejected("ALGORITHM_MISMATCH")
        if (keyId.isBlank()) return rejected("KEY_ID_REQUIRED")
        if (keyGeneration <= 0L) return rejected("KEY_GENERATION_INVALID")
        if (envelope.attestationKeyId != keyId || envelope.attestationKeyGeneration != keyGeneration) return rejected("ENVELOPE_KEY_BINDING_MISMATCH")
        if (!EPOCH_ID.matches(envelope.serverEpoch)) return rejected("SERVER_EPOCH_FORMAT_INVALID")
        if (envelope.serverEpochCreatedAt <= 0L) return rejected("SERVER_EPOCH_CREATED_AT_INVALID")
        if (trustedNowMillis <= 0L) return rejected("TRUSTED_NOW_INVALID")
        if (allowedClockSkewMs !in 0L..30_000L) return rejected("CLOCK_SKEW_INVALID")
        val trusted = registry.get(envelope.providerId, keyId, keyGeneration) ?: return rejected("PROVIDER_KEY_BINDING_NOT_TRUSTED")
        if (!trusted.providerId.equals(envelope.providerId, ignoreCase = true)) return rejected("PROVIDER_KEY_BINDING_MISMATCH")
        if (envelope.issuedAt <= 0L || envelope.expiresAt <= envelope.issuedAt) return rejected("SIGNATURE_WINDOW_INVALID")
        if (envelope.expiresAt - envelope.issuedAt > MAX_SIGNATURE_LIFETIME_MS) return rejected("SIGNATURE_WINDOW_TOO_WIDE")
        if (envelope.serverTime !in envelope.issuedAt..envelope.expiresAt) return rejected("SERVER_TIME_OUTSIDE_SIGNATURE_WINDOW")
        if (envelope.serverEpochCreatedAt > envelope.issuedAt) return rejected("EPOCH_CREATED_AFTER_SIGNATURE_ISSUE")
        if (envelope.issuedAt > trustedNowMillis + allowedClockSkewMs) return rejected("SIGNATURE_NOT_YET_VALID")
        // Boundary policy is strict: expiry exactly at (trustedNow - skew) is expired.
        if (envelope.expiresAt <= trustedNowMillis - allowedClockSkewMs) return rejected("SIGNATURE_EXPIRED")
        if (envelope.serverTime > trustedNowMillis + allowedClockSkewMs) return rejected("SERVER_TIME_IN_FUTURE")
        if (trustedNowMillis - envelope.serverTime > MAX_SIGNATURE_LIFETIME_MS + allowedClockSkewMs) return rejected("SERVER_TIME_TOO_OLD")

        val sigBytes = runCatching { Base64.getDecoder().decode(signatureBase64) }.getOrNull()
            ?: return rejected("SIGNATURE_BASE64_INVALID")
        val verified = runCatching {
            Signature.getInstance(ALGORITHM).run {
                initVerify(trusted.publicKey)
                update(envelope.canonical().toByteArray(Charsets.UTF_8))
                verify(sigBytes)
            }
        }.getOrDefault(false)
        return if (verified) V538AttestationResult(V538AttestationStatus.VERIFIED, "VERIFIED") else rejected("SIGNATURE_INVALID")
    }

    private fun rejected(reason: String) = result(V538AttestationStatus.REJECTED, reason)
    private fun result(status: V538AttestationStatus, reason: String) = V538AttestationResult(status, reason)
}
