package ua.knu.promptlab;

/** Помилка відповіді API (HTTP не 2xx) або мережі. */
public class ApiException extends RuntimeException {

    private final int status;
    private final boolean dailyQuota;

    public ApiException(int status, String message) {
        this(status, message, false);
    }

    /** dailyQuota = true — HTTP 429 через добовий ліміт (повтори сьогодні не допоможуть). */
    public ApiException(int status, String message, boolean dailyQuota) {
        super(message);
        this.status = status;
        this.dailyQuota = dailyQuota;
    }

    /** Чи це вичерпаний добовий ліміт (а не хвилинний). */
    public boolean dailyQuota() {
        return dailyQuota;
    }

    public ApiException(String message, Throwable cause) {
        super(message, cause);
        this.status = -1;
        this.dailyQuota = false;
    }

    public int status() {
        return status;
    }

    @Override
    public String toString() {
        return "ApiException[HTTP " + status + "]: " + getMessage();
    }
}
