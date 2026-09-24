package tr.borsatakip.v5.analysis.v536

import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

data class V536AttestationEnvelope(
    val snapshotHash: String,
    val requestId: String,
    val snapshotId: String,
    val providerId: String,
    val serverTime: Long,
    val generatedAt: Long,
    val serverSequence: Long,
    val nonce: String,
    val calculationEngineVersion: String,
    val issuedAt: Long,
    val expiresAt: Long
) {
    fun canonical(): String = buildString {
        append("schema=V536_ATTESTATION_V1\n")
        append("snapshotHash=").append(snapshotHash.trim().lowercase()).append('\n')
        append("requestId=").append(requestId.trim()).append('\n')
        append("snapshotId=").append(snapshotId.trim()).append('\n')
        append("providerId=").append(providerId.trim()).append('\n')
        append("serverTime=").append(serverTime).append('\n')
        append("generatedAt=").append(generatedAt).append('\n')
        append("serverSequence=").append(serverSequence).append('\n')
        append("nonce=").append(nonce.trim()).append('\n')
        append("engine=").append(calculationEngineVersion.trim()).append('\n')
        append("issuedAt=").append(issuedAt).append('\n')
        append("expiresAt=").append(expiresAt)
    }
}

enum class V536AttestationStatus { NOT_CONFIGURED, VERIFIED, REJECTED }

data class V536AttestationResult(val status: V536AttestationStatus, val reason: String)

class V536TrustedKeyRegistry private constructor(private val keys: Map<String, PublicKey>) {
    fun get(keyId: String): PublicKey? = keys[keyId]
    fun isConfigured(): Boolean = keys.isNotEmpty()

    companion object {
        /** Semicolon separated `keyId:base64(X.509 EC public key)` values. */
        fun parse(raw: String): V536TrustedKeyRegistry {
            val parsed = linkedMapOf<String, PublicKey>()
            raw.split(';').map { it.trim() }.filter { it.isNotBlank() }.forEach { entry ->
                val idx = entry.indexOf(':')
                if (idx <= 0 || idx == entry.lastIndex) return@forEach
                val keyId = entry.substring(0, idx).trim()
                val b64 = entry.substring(idx + 1).trim()
                val key = runCatching {
                    val bytes = Base64.getDecoder().decode(b64)
                    KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))
                }.getOrNull()
                if (keyId.isNotBlank() && key != null) parsed[keyId] = key
            }
            return V536TrustedKeyRegistry(parsed)
        }
    }
}

object V536ServerAttestationVerifier {
    const val ALGORITHM = "SHA256withECDSA"
    const val MAX_SIGNATURE_LIFETIME_MS = 120_000L

    fun verify(
        registry: V536TrustedKeyRegistry,
        envelope: V536AttestationEnvelope,
        keyId: String,
        algorithm: String,
        signatureBase64: String
    ): V536AttestationResult {
        if (!registry.isConfigured()) return V536AttestationResult(V536AttestationStatus.NOT_CONFIGURED, "NO_TRUSTED_PUBLIC_KEY")
        if (algorithm != ALGORITHM) return V536AttestationResult(V536AttestationStatus.REJECTED, "ALGORITHM_MISMATCH")
        if (keyId.isBlank()) return V536AttestationResult(V536AttestationStatus.REJECTED, "KEY_ID_REQUIRED")
        val key = registry.get(keyId) ?: return V536AttestationResult(V536AttestationStatus.REJECTED, "UNKNOWN_KEY_ID")
        if (envelope.issuedAt <= 0L || envelope.expiresAt <= envelope.issuedAt) return V536AttestationResult(V536AttestationStatus.REJECTED, "SIGNATURE_WINDOW_INVALID")
        if (envelope.expiresAt - envelope.issuedAt > MAX_SIGNATURE_LIFETIME_MS) return V536AttestationResult(V536AttestationStatus.REJECTED, "SIGNATURE_WINDOW_TOO_WIDE")
        if (envelope.serverTime !in envelope.issuedAt..envelope.expiresAt) return V536AttestationResult(V536AttestationStatus.REJECTED, "SIGNATURE_EXPIRED_OR_NOT_YET_VALID")
        val sigBytes = runCatching { Base64.getDecoder().decode(signatureBase64) }.getOrNull()
            ?: return V536AttestationResult(V536AttestationStatus.REJECTED, "SIGNATURE_BASE64_INVALID")
        val verified = runCatching {
            Signature.getInstance(ALGORITHM).run {
                initVerify(key)
                update(envelope.canonical().toByteArray(Charsets.UTF_8))
                verify(sigBytes)
            }
        }.getOrDefault(false)
        return if (verified) V536AttestationResult(V536AttestationStatus.VERIFIED, "VERIFIED")
        else V536AttestationResult(V536AttestationStatus.REJECTED, "SIGNATURE_INVALID")
    }
}
