package tr.borsatakip.v5.analysis.v535

/**
 * V5.3.5 attestation boundary.
 *
 * Hashes prove payload consistency, not backend identity. Production server
 * attestation is intentionally NOT faked in the client. A future backend must
 * provide a public-key signature (preferred over a shared HMAC secret embedded
 * in the APK) over this canonical envelope. Until a trusted public key and
 * backend signature endpoint exist, attestation remains NOT_CONFIGURED.
 */
data class V535AttestationEnvelope(
    val snapshotHash: String,
    val requestId: String,
    val serverTime: Long,
    val calculationEngineVersion: String
)

enum class V535AttestationStatus { NOT_CONFIGURED, VERIFIED, REJECTED }

interface V535ServerAttestationVerifier {
    fun verify(envelope: V535AttestationEnvelope, keyId: String, signatureBase64: String): V535AttestationStatus
}
