package ua.knu.promptlab;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/** POST із JSON-тілом і повторами при 429 / 5xx — спільне для обох провайдерів (і для ембедингів лабораторної 4). */
public final class HttpJson {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    private final int maxRetries;
    private final long baseDelayMs;

    public HttpJson(int maxRetries, long baseDelayMs) {
        this.maxRetries = maxRetries;
        this.baseDelayMs = baseDelayMs;
    }

    /** true, якщо рядок можна покласти в HTTP-заголовок: лише видимі ASCII-символи і пробіл. */
    public static boolean isAscii(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c > 0x7E) return false;
        }
        return true;
    }

    public JsonNode post(String url, Map<String, String> headers, JsonNode body) {
        String payload;
        try {
            payload = JSON.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new ApiException("Не вдалося серіалізувати запит", e);
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(90))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
        headers.forEach((k, v) -> {
            if (!isAscii(k) || !isAscii(v)) {
                // HttpClient відкидає такий заголовок з малозрозумілим «invalid header value»; найчастіше — кирилиця чи пробіл у ключі в .env
                throw new ApiException(-1, "Заголовок " + k + " містить символи поза ASCII (кирилицю, «розумні» лапки тощо). "
                        + (k.toLowerCase(java.util.Locale.ROOT).contains("key") ? "Перевірте ключ у .env: лише латиниця, цифри, - і _, без лапок і пробілів." : ""));
            }
            builder.header(k, v);
        });
        HttpRequest request = builder.build();

        for (int attempt = 0; ; attempt++) {
            try {
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                int status = response.statusCode();
                if (status / 100 == 2) {
                    return JSON.readTree(response.body());
                }
                if (status == 429 && isDailyQuota(response.body())) {
                    // добовий ліміт: повтори сьогодні не допоможуть — одразу помилка з позначкою
                    throw new ApiException(status, extractMessage(response.body()), true);
                }
                boolean retryable = status == 429 || status == 500 || status == 503 || status == 529;
                if (retryable && attempt < maxRetries) {
                    long wait = retryAfterMs(response).orElse(retryDelayMs(response.body()).orElse(baseDelayMs * (1L << attempt)));
                    System.err.println("  [HTTP " + status + "] повтор " + (attempt + 1) + "/" + maxRetries
                            + " через " + (wait / 1000.0) + " с");
                    Thread.sleep(wait);
                    continue;
                }
                throw new ApiException(status, extractMessage(response.body()));
            } catch (IOException e) {
                throw new ApiException("Мережева помилка: " + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ApiException("Запит перервано", e);
            }
        }
    }

    private static java.util.OptionalLong retryAfterMs(HttpResponse<String> response) {
        return response.headers().firstValue("retry-after").map(v -> {
            try {
                return java.util.OptionalLong.of(Math.min(60, Long.parseLong(v.strip())) * 1000L);
            } catch (NumberFormatException e) {
                return java.util.OptionalLong.empty();
            }
        }).orElse(java.util.OptionalLong.empty());
    }

    private static final java.util.regex.Pattern RETRY_DELAY = java.util.regex.Pattern.compile("\"retryDelay\"\\s*:\\s*\"(\\d+(?:\\.\\d+)?)s\"");

    /**
     * Gemini у тілі 429 пише, скільки чекати: {"error": {…, "details": [{"@type": "…RetryInfo", "retryDelay": "37s"}]}}.
     * Повертає мілісекунди (не більше 120 с) або порожньо.
     */
    public static java.util.OptionalLong retryDelayMs(String body) {
        if (body == null) return java.util.OptionalLong.empty();
        java.util.regex.Matcher m = RETRY_DELAY.matcher(body);
        if (!m.find()) return java.util.OptionalLong.empty();
        return java.util.OptionalLong.of(Math.min(120_000L, Math.round(Double.parseDouble(m.group(1)) * 1000) + 500));
    }

    /** Чи 429 означає добовий ліміт: у quotaId / повідомленні є «PerDay» або «per day». */
    public static boolean isDailyQuota(String body) {
        if (body == null) return false;
        String b = body.toLowerCase(java.util.Locale.ROOT);
        return b.contains("perday") || b.contains("per day") || b.contains("per_day");
    }

    private static String extractMessage(String body) {
        try {
            JsonNode msg = JSON.readTree(body).path("error").path("message");
            if (!msg.isMissingNode()) return msg.asText();
        } catch (IOException ignored) {
            // тіло не JSON — повернемо як є
        }
        return body;
    }
}
