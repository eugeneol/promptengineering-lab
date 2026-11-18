package ua.knu.promptlab;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Налаштування беруться з файлу .env у корені проєкту та зі змінних середовища
 * (змінні середовища мають пріоритет). Ключ у коді й у git не зберігаємо.
 *
 * <pre>
 * LAB_PROVIDER=gemini               gemini | claude (якщо не задано — за тим, який ключ є)
 * GEMINI_API_KEY=...                ключ з aistudio.google.com/apikey
 * ANTHROPIC_API_KEY=sk-ant-...      ключ з console.anthropic.com
 * LAB_MODEL=...                     (необов'язково) модель; є значення за замовчуванням для кожного провайдера
 * LAB_MOCK=1                        (необов'язково) імітація без реальних запитів
 * LAB_DELAY_MS=4000                 пауза між запитами, мс, щоб не впертися в ~15 запитів/хв безкоштовного рівня
 * LAB_THINKING_LEVEL=low            (необов'язково, лише Gemini, лаби 1–2) рівень «міркування» моделі
 * LAB_THINKING_BUDGET=4096          (необов'язково, лабораторна 3) бюджет effort high у токенах для моделей без effort:
 *                                   Gemini 2.x thinkingBudget, Claude 4.x thinking.budget_tokens (мінімум 1024)
 * LAB_VARIANT=3                     (лабораторна 3) номер варіанта 1–8 — див. {@link #setting(String)}
 * LAB_NO_CACHE=1                    (лабораторна 3) не читати й не писати кеш відповідей target/lab3-cache.json
 * LAB_MOCK_CLAUDE_GEN=4             (лише MOCK) покоління імітованого Claude: 5 (output_config.effort) або 4 (budget_tokens)
 * LAB_MOCK_NO_TEMPERATURE=1         (лише MOCK) імітація відповідає 400 на будь-яку temperature (обидва провайдери)
 * </pre>
 */
public record Config(Provider provider, String apiKey, String model, String baseUrl,
                     boolean mock, long delayMs, String thinkingLevel, Integer thinkingBudget) {

    /** Конструктор лабораторних 1–2 (без бюджету міркування) — лишається, щоб старий код і тести не змінювались. */
    public Config(Provider provider, String apiKey, String model, String baseUrl, boolean mock, long delayMs, String thinkingLevel) {
        this(provider, apiKey, model, baseUrl, mock, delayMs, thinkingLevel, null);
    }

    public enum Provider {
        CLAUDE("Claude", "claude-sonnet-5-5", "https://api.anthropic.com"),
        GEMINI("Gemini", "gemini-3.5-flash-lite", "https://generativelanguage.googleapis.com");

        public final String title;
        public final String defaultModel;
        public final String defaultBaseUrl;

        Provider(String title, String defaultModel, String defaultBaseUrl) {
            this.title = title;
            this.defaultModel = defaultModel;
            this.defaultBaseUrl = defaultBaseUrl;
        }
    }

    public static Config load() {
        return load(Path.of(".env"), System.getenv());
    }

    static Config load(Path envFile, Map<String, String> env) {
        Map<String, String> values = new HashMap<>(readDotEnv(envFile));
        env.forEach((k, v) -> {
            if (v != null && !v.isBlank()) values.put(k, v);
        });

        boolean mock = isTrue(values.get("LAB_MOCK"));
        String anthropicKey = values.get("ANTHROPIC_API_KEY");
        String geminiKey = firstNonBlank(values.get("GEMINI_API_KEY"), values.get("GOOGLE_API_KEY"));
        Provider provider = chooseProvider(values.get("LAB_PROVIDER"), anthropicKey, geminiKey);

        String model = values.getOrDefault("LAB_MODEL", provider.defaultModel);
        String baseUrl = values.getOrDefault(
                provider == Provider.CLAUDE ? "ANTHROPIC_BASE_URL" : "GEMINI_BASE_URL", provider.defaultBaseUrl);
        long delay = parseLong(values.get("LAB_DELAY_MS"), 0);
        String thinking = values.get("LAB_THINKING_LEVEL");
        long budget = parseLong(values.get("LAB_THINKING_BUDGET"), -1);
        Integer thinkingBudget = budget >= 0 ? (int) budget : null;

        if (mock) {
            return new Config(provider, "mock-key", model, baseUrl, true, 0, thinking, thinkingBudget);
        }
        String key = provider == Provider.CLAUDE ? anthropicKey : geminiKey;
        if (key == null || key.isBlank()) {
            throw new IllegalStateException(missingKeyMessage(provider));
        }
        return new Config(provider, key.trim(), model, baseUrl, false, delay, thinking, thinkingBudget);
    }

    /**
     * Одне налаштування з .env або змінних середовища (змінні середовища мають пріоритет); null, якщо не задано.
     * Для параметрів лабораторної 3, які не потрібні клієнтам: LAB_VARIANT, LAB_NO_CACHE, LAB_SKIP_BONUS.
     */
    public static String setting(String key) {
        return setting(key, Path.of(".env"), System.getenv());
    }

    static String setting(String key, Path envFile, Map<String, String> env) {
        String v = env.get(key);
        if (v != null && !v.isBlank()) return v.strip();
        v = readDotEnv(envFile).get(key);
        return v == null || v.isBlank() ? null : v.strip();
    }

    /**
     * Лабораторна 4: ключ Gemini для ембедингів (GEMINI_API_KEY або GOOGLE_API_KEY) — потрібен НАВІТЬ тоді, коли відповіді
     * генерує Claude (LAB_PROVIDER=claude): ембединги в лабі рахує лише gemini-embedding-2. null — ключа немає.
     */
    public static String geminiKey() {
        String k = setting("GEMINI_API_KEY");
        return k != null ? k : setting("GOOGLE_API_KEY");
    }

    /** Лабораторна 4: адреса Gemini API для ембедингів (GEMINI_BASE_URL або стандартна). */
    public static String geminiBaseUrl() {
        String u = setting("GEMINI_BASE_URL");
        return u != null ? u : Provider.GEMINI.defaultBaseUrl;
    }

    /** "1" або "true" (без урахування регістру). */
    public static boolean isTrueSetting(String key) {
        return isTrue(setting(key));
    }

    private static Provider chooseProvider(String explicit, String anthropicKey, String geminiKey) {
        if (explicit != null && !explicit.isBlank()) {
            String p = explicit.strip().toLowerCase(Locale.ROOT);
            return switch (p) {
                case "claude", "anthropic" -> Provider.CLAUDE;
                case "gemini", "google" -> Provider.GEMINI;
                default -> throw new IllegalStateException(
                        "Невідомий LAB_PROVIDER='" + explicit + "'. Допустимо: gemini або claude.");
            };
        }
        if (anthropicKey != null && !anthropicKey.isBlank()) return Provider.CLAUDE;
        if (geminiKey != null && !geminiKey.isBlank()) return Provider.GEMINI;
        return Provider.GEMINI; // за замовчуванням для студентів (і для LAB_MOCK без налаштувань)
    }

    private static String missingKeyMessage(Provider p) {
        if (p == Provider.GEMINI) {
            return """

                    Не знайдено GEMINI_API_KEY.
                      1) створіть ключ на https://aistudio.google.com/apikey (безкоштовно);
                      2) скопіюйте .env.example у .env і впишіть ключ після GEMINI_API_KEY=;
                      3) або запустіть у режимі імітації: змінна середовища LAB_MOCK=1.
                    """;
        }
        return """

                Не знайдено ANTHROPIC_API_KEY.
                  1) скопіюйте .env.example у .env і впишіть ключ з console.anthropic.com
                     (підписка Claude Pro/Max — це НЕ API-ключ);
                  2) або запустіть у режимі імітації: змінна середовища LAB_MOCK=1.
                """;
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    private static long parseLong(String v, long fallback) {
        try {
            return v == null ? fallback : Long.parseLong(v.strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean isTrue(String v) {
        return v != null && (v.equals("1") || v.equalsIgnoreCase("true"));
    }

    private static Map<String, String> readDotEnv(Path file) {
        Map<String, String> map = new HashMap<>();
        if (!Files.isRegularFile(file)) return map;
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (String raw : lines) {
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String k = line.substring(0, eq).strip();
                String v = line.substring(eq + 1).strip();
                if (v.length() >= 2 && (v.startsWith("\"") && v.endsWith("\"") || v.startsWith("'") && v.endsWith("'"))) {
                    v = v.substring(1, v.length() - 1);
                }
                if (!v.isEmpty()) map.put(k, v);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Не вдалося прочитати " + file + ": " + e.getMessage(), e);
        }
        return map;
    }
}
