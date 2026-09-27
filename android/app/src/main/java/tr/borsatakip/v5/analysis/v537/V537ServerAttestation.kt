package tr.borsatakip.v5.analysis.v537

import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** Signed by the backend private key. Private keys are never shipped in Android. */
data class V537AttestationEnvelope(
    val snapshotHash: String,
    val requestId: String,
    val snapshotId: String,
    val providerId: String,
    val serverEpoch: String,
    val serverTime: Long,
    val generatedAt: Long,
    val serverSequence: Long,
    val nonce: String,
    val calculationEngineVersion: String,
    val issuedAt: Long,
    val expiresAt: Long
) {
    fun canonical(): String = buildString {
        append("schema=V537_ATTESTATION_V1\n")
        append("snapshotHash=").append(snapshotHash.trim().lowercase()).append('\n')
        append("requestId=").append(requestId.trim()).append('\n')
        append("snapshotId=").append(snapshotId.trim()).append('\n')
        append("providerId=").append(providerId.trim()).append('\n')
        append("serverEpoch=").append(serverEpoch.trim()).append('\n')
        append("serverTime=").append(serverTime).append('\n')
        append("generatedAt=").append(generatedAt).append('\n')
        append("serverSequence=").append(serverSequence).append('\n')
        append("nonce=").append(nonce.trim()).append('\n')
        append("engine=").append(calculationEngineVersion.trim()).append('\n')
        append("issuedAt=").append(issuedAt).append('\n')
        append("expiresAt=").append(expiresAt)
    }
}

enum class V537AttestationStatus { NOT_CONFIGURED, VERIFIED, REJECTED }

data class V537AttestationResult(val status: V537AttestationStatus, val reason: String)

class V537TrustedKeyRegistry private constructor(private val keys: Map<String, PublicKey>) {
    fun get(keyId: String): PublicKey? = keys[keyId]
    fun isConfigured(): Boolean = keys.isNotEmpty()

    companion object {
        /** Semicolon separated `keyId:base64(X.509 EC public key)` values. */
        fun parse(raw: String): V537TrustedKeyRegistry {
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
            return V537TrustedKeyRegistry(parsed)
        }
    }
}

object V537ServerAttestationVerifier {
    const val ALGORITHM = "SHA256withECDSA"
    const val MAX_SIGNATURE_LIFETIME_MS = 120_000L

    fun verify(
        registry: V537TrustedKeyRegistry,
        envelope: V537AttestationEnvelope,
        keyId: String,
        algorithm: String,
        signatureBase64: String,
        trustedNowMillis: Long,
        allowedClockSkewMs: Long
    ): V537AttestationResult {
        if (!registry.isConfigured()) return rejected(V537AttestationStatus.NOT_CONFIGURED, "NO_TRUSTED_PUBLIC_KEY")
        if (algorithm != ALGORITHM) return rejected(V537AttestationStatus.REJECTED, "ALGORITHM_MISMATCH")
        if (keyId.isBlank()) return rejected(V537AttestationStatus.REJECTED, "KEY_ID_REQUIRED")
        if (envelope.serverEpoch.isBlank()) return rejected(V537AttestationStatus.REJECTED, "SERVER_EPOCH_REQUIRED")
        if (trustedNowMillis <= 0L) return rejected(V537AttestationStatus.REJECTED, "TRUSTED_NOW_INVALID")
        if (allowedClockSkewMs !in 0L..30_000L) return rejected(V537AttestationStatus.REJECTED, "CLOCK_SKEW_INVALID")
        val key = registry.get(keyId) ?: return rejected(V537AttestationStatus.REJECTED, "UNKNOWN_KEY_ID")
        if (envelope.issuedAt <= 0L || envelope.expiresAt <= envelope.issuedAt) return rejected(V537AttestationStatus.REJECTED, "SIGNATURE_WINDOW_INVALID")
        if (envelope.expiresAt - envelope.issuedAt > MAX_SIGNATURE_LIFETIME_MS) return rejected(V537AttestationStatus.REJECTED, "SIGNATURE_WINDOW_TOO_WIDE")
        if (envelope.serverTime !in envelope.issuedAt..envelope.expiresAt) return rejected(V537AttestationStatus.REJECTED, "SERVER_TIME_OUTSIDE_SIGNATURE_WINDOW")
        if (envelope.issuedAt > trustedNowMillis + allowedClockSkewMs) return rejected(V537AttestationStatus.REJECTED, "SIGNATURE_NOT_YET_VALID")
        if (envelope.expiresAt < trustedNowMillis - allowedClockSkewMs) return rejected(V537AttestationStatus.REJECTED, "SIGNATURE_EXPIRED")
        if (envelope.serverTime > trustedNowMillis + allowedClockSkewMs) return rejected(V537AttestationStatus.REJECTED, "SERVER_TIME_IN_FUTURE")
        if (trustedNowMillis - envelope.serverTime > MAX_SIGNATURE_LIFETIME_MS + allowedClockSkewMs) return rejected(V537AttestationStatus.REJECTED, "SERVER_TIME_TOO_OLD")

        val sigBytes = runCatching { Base64.getDecoder().decode(signatureBase64) }.getOrNull()
            ?: return rejected(V537AttestationStatus.REJECTED, "SIGNATURE_BASE64_INVALID")
        val verified = runCatching {
            Signature.getInstance(ALGORITHM).run {
                initVerify(key)
                update(envelope.canonical().toByteArray(Charsets.UTF_8))
                verify(sigBytes)
            }
        }.getOrDefault(false)
        return if (verified) V537AttestationResult(V537AttestationStatus.VERIFIED, "VERIFIED")
        else rejected(V537AttestationStatus.REJECTED, "SIGNATURE_INVALID")
    }

    private fun rejected(status: V537AttestationStatus, reason: String) = V537AttestationResult(status, reason)
}
