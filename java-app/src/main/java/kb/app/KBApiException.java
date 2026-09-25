package kb.app;

/** processFlag가 정상(A)이 아닌 응답. */
public class KBApiException extends RuntimeException {

    public final String trCode;
    public final String code;

    public KBApiException(String trCode, String code, String message) {
        super("[" + trCode + "] " + code + " " + message);
        this.trCode = trCode;
        this.code = code;
    }
}
