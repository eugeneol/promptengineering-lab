package ua.knu.promptlab.lab3;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ua.knu.promptlab.LlmClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Лабораторна 3. Кеш відповідей моделі на диску: {@code target/lab3-cache.json}.
 *
 * <p>Навіщо: вимірювання — це багато однакових запитів. Повторний прогін (щоб переглянути вивід, після 429 чи падіння
 * посередині, після власної правки промпта в кроці 3) бере готові відповіді з кешу й не витрачає квоту безкоштовного рівня;
 * нові запити йдуть лише для того, що змінилося.
 *
 * <p><b>Ключ</b> = SHA-256 від: провайдер + модель + MOCK чи ні + мітка вибірки + параметри запиту
 * (системний промпт з прикладами, текст звернення, схема, ліміт токенів, temperature, рівень міркування effort) — див. Classifier.keyMaterial.
 * Змінили хоч кому в промпті — новий ключ.
 * Мітка вибірки (s1, s2, s3) потрібна для повторів (шум zero-shot, голосування): однакові запити мають дати РІЗНІ відповіді,
 * а без мітки кеш повернув би одну й ту саму тричі.
 *
 * <p>LAB_NO_CACHE=1 — не читати й не писати файл (у пам'яті однакові запити в межах одного прогону все одно не дублюються).
 * Щоб почати вимірювання з нуля, просто видаліть target/lab3-cache.json.
 */
public final class AnswerCache {

    /** Що зберігаємо про одну відповідь: текст і лічильники, щоб таблиця токенів і затримки не залежала від кешу. */
    public record Entry(String text, int inputTokens, int outputTokens, int thinkingTokens, long latencyMs,
                        String stopReason, String rawStopReason) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path file;
    private final boolean disk;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private boolean loadWarning;

    public AnswerCache(Path file, boolean disk) {
        this.file = file;
        this.disk = disk;
        if (disk && Files.isRegularFile(file)) {
            try {
                entries.putAll(JSON.readValue(file.toFile(), new TypeReference<Map<String, Entry>>() {
                }));
            } catch (IOException e) {
                loadWarning = true; // зіпсований файл — просто починаємо з порожнього кешу
            }
        }
    }

    /**
     * Кеш за налаштуваннями: target/lab3-cache.json у поточній теці (корінь проєкту в IDEA), LAB_NO_CACHE=1 вимикає диск.
     * У режимі MOCK — окремий файл target/lab3-cache-mock.json: імітовані відповіді не потрапляють у кеш, який здають зі звітом.
     */
    public static AnswerCache fromSettings(boolean noCache, boolean mock) {
        return new AnswerCache(Path.of("target", mock ? "lab3-cache-mock.json" : "lab3-cache.json"), !noCache);
    }

    public boolean diskEnabled() {
        return disk;
    }

    public Path file() {
        return file;
    }

    public int size() {
        return entries.size();
    }

    /** true, якщо файл кешу був, але не читався (тоді його буде перезаписано). */
    public boolean hadLoadWarning() {
        return loadWarning;
    }

    public static String key(LlmClient client, JsonNode request, String sampleTag) {
        String raw = client.provider() + "|" + client.model() + "|" + (client.isMock() ? "mock" : "real") + "|" + sampleTag + "|" + request;
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public Optional<Entry> get(String key) {
        return Optional.ofNullable(entries.get(key));
    }

    /** Додає відповідь і (якщо диск увімкнено) одразу зберігає файл — прогрес не губиться, навіть якщо прогін упаде на 429. */
    public void put(String key, Entry entry) {
        entries.put(key, entry);
        if (!disk) return;
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            JSON.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), entries);
        } catch (IOException e) {
            System.err.println("  [кеш не збережено: " + e.getMessage() + "]");
        }
    }
}
