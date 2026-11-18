package ua.knu.promptlab.lab2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ua.knu.promptlab.Console;
import ua.knu.promptlab.LlmClient;
import ua.knu.promptlab.LlmResponse;

import java.util.Iterator;
import java.util.Optional;

/**
 * Лабораторна 2, розділ 7 звіту: перевірка ВАШОЇ схеми ({@link MySchema}) на трьох входах кодом.
 * Для кожного входу: запит зі схемою (як крок 7.2) → чи розібрався JSON → ваші правила {@link MySchema#validate(JsonNode)}.
 * Запитів — 3 (з паузою LAB_DELAY_MS). Конфігурації: «Лаба 2 · 8 My schema» і «Лаба 2 · 8 My schema (MOCK)».
 */
public final class MySchemaRun {

    static final int MAX_TOKENS = 2048; // з запасом: «міркування» Gemini входить у ліміт
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Підсумок прогону: скільки відповідей розібрано як JSON і скільки пройшли ваші правила. */
    public record Summary(boolean schemaOk, int parsed, int passed, int total) {
    }

    private MySchemaRun() {
    }

    public static void main(String[] args) {
        Console.init();
        try (LlmClient client = LlmClient.create()) {
            run(client);
        }
    }

    public static Summary run(LlmClient client) {
        Console.banner("Лабораторна 2, розділ 7. Ваша схема на трьох входах (" + client.provider() + ", " + client.model() + ")");
        Console.kv("задача", MySchema.TASK);

        JsonNode schema;
        try {
            schema = JSON.readTree(MySchema.SCHEMA);
        } catch (Exception e) {
            System.out.println("  SCHEMA у MySchema.java — не валідний JSON: " + e.getMessage().lines().findFirst().orElse(""));
            System.out.println("  Виправте лапки, коми й дужки і запустіть ще раз. Запитів до API не було.");
            return new Summary(false, 0, 0, MySchema.INPUTS.size());
        }
        if (!schema.isObject() || !"object".equals(schema.path("type").asText())) {
            System.out.println("  SCHEMA має бути об'єктом з \"type\": \"object\". Запитів до API не було.");
            return new Summary(false, 0, 0, MySchema.INPUTS.size());
        }
        Console.kv("є поле з enum", has(schema, "enum") ? "так" : "НІ — додайте (вимога завдання)");
        Console.kv("є масив з maxItems", has(schema, "maxItems") ? "так" : "НІ — додайте (вимога завдання)");

        Console.section("Що ми відправляємо (перший вхід): " + client.endpointDescription());
        System.out.println(Console.indent(Console.pretty(client.buildJsonRequest(MySchema.SYSTEM, MySchema.INPUTS.get(0), schema, MAX_TOKENS))));

        int parsed = 0, passed = 0, n = 0;
        for (String input : MySchema.INPUTS) {
            if (n > 0) pause(client);
            n++;
            LlmResponse r = client.askJson(MySchema.SYSTEM, input, schema, MAX_TOKENS);
            Console.section("Вхід " + n + ": " + Console.clip(input, 90));
            System.out.println(Console.indent(r.text()));
            Console.kv("причина зупинки", r.stopReason() + "   (у відповіді API: " + r.rawStopReason() + ")");
            Console.kv("токени in / out", r.inputTokens() + " / " + r.outputTokens());
            JsonNode answer;
            try {
                answer = JSON.readTree(r.text().strip());
            } catch (Exception e) {
                answer = null;
            }
            if (answer == null || !answer.isObject()) {
                Console.kv("JSON", "НЕ розібрано" + ("max_tokens".equals(r.stopReason()) ? " (обрізано лімітом токенів)" : ""));
                continue;
            }
            parsed++;
            Optional<String> reason;
            try {
                reason = MySchema.validate(answer);
            } catch (RuntimeException e) {
                reason = Optional.of("validate() впав з помилкою: " + e);
            }
            Console.kv("JSON", "розібрано");
            Console.kv("ваші правила", reason.map(s -> "ПОРУШЕНО — " + s).orElse("усі виконано"));
            if (reason.isEmpty()) passed++;
        }

        Console.section("Підсумок");
        Console.kv("розібрано як JSON", parsed + " з " + n);
        Console.kv("пройшли ваші правила", passed + " з " + n);
        Console.discuss(
                "Яке з ваших правил не можна записати в JSON Schema і чому?",
                "Що зробила модель на «незручному» вході? Чи врятувала схема, чи спрацювало правило?");
        return new Summary(true, parsed, passed, n);
    }

    /** Чи є десь у схемі ключ key (enum, maxItems…). */
    static boolean has(JsonNode node, String key) {
        if (node.isObject()) {
            if (node.has(key)) return true;
            for (Iterator<JsonNode> it = node.elements(); it.hasNext(); ) {
                if (has(it.next(), key)) return true;
            }
        } else if (node.isArray()) {
            for (JsonNode x : node) {
                if (has(x, key)) return true;
            }
        }
        return false;
    }

    private static void pause(LlmClient client) {
        long ms = client.requestDelayMs();
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
