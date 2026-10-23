package kb.app

/** processFlag가 정상(A)이 아닌 응답. */
class KBApiException(
    @JvmField val trCode: String,
    @JvmField val code: String,
    message: String,
) : RuntimeException("[$trCode] $code $message")
