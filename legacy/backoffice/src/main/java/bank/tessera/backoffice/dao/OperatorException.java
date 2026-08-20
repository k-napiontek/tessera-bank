package bank.tessera.backoffice.dao;

/** An action the customer master refused, carrying the code it refused with. */
public final class OperatorException extends Exception {

    private static final long serialVersionUID = 1L;

    private final int errorCode;

    OperatorException(int errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /** The Oracle application error number, so a caller can tell a refusal from a failure. */
    public int getErrorCode() {
        return errorCode;
    }

    /** True when the database refused on a business rule rather than failing. */
    public boolean isRefusal() {
        return errorCode == OperatorDao.TIMING_NOT_ACTIONABLE
                || errorCode == OperatorDao.NOTE_REQUIRED;
    }
}
