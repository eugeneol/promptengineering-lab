package ua.knu.promptlab.lab3;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Locale;

/**
 * Лабораторна 3. Відповідь класифікатора: три мітки (і, якщо в схемі було поле reasoning, — міркування моделі).
 * Порівнюємо лише мітки; reasoning — для читання людиною.
 *
 * @param needsHuman Boolean, а не boolean: null означає «відповідь не розібрано»
 */
public record Labels(String category, String urgency, Boolean needsHuman, String reasoning) {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Відповідь, яку не вдалося розібрати (обрізано, порожньо, не JSON) — рахується як помилка за всіма полями. */
    public static Labels unparsed(String why) {
        return new Labels("—", "—", null, why);
    }

    public boolean parsed() {
        return needsHuman != null;
    }

    /**
     * Розбір JSON-відповіді. Схема в запиті гарантує форму, тож тут лише нормалізація регістру й пробілів;
     * будь-що інше (обрізаний JSON, відсутнє поле) → {@link #unparsed(String)} з причиною.
     */
    public static Labels parse(String text) {
        if (text == null || text.isBlank()) return unparsed("порожня відповідь");
        try {
            JsonNode n = JSON.readTree(text.strip());
            if (n == null || !n.isObject()) return unparsed("не JSON-об'єкт");
            if (!n.hasNonNull("category") || !n.hasNonNull("urgency") || !n.path("needs_human").isBoolean()) {
                return unparsed("бракує category / urgency / needs_human");
            }
            String reasoning = n.hasNonNull("reasoning") ? n.get("reasoning").asText() : null;
            return new Labels(norm(n.get("category").asText()), norm(n.get("urgency").asText()), n.get("needs_human").asBoolean(), reasoning);
        } catch (Exception e) {
            return unparsed("не валідний JSON");
        }
    }

    private static String norm(String s) {
        return s.strip().toLowerCase(Locale.ROOT);
    }

    /** Ключ для голосування: дві відповіді «однакові», якщо збігаються всі три мітки (reasoning не враховується). */
    public String key() {
        return category + " / " + urgency + " / " + (needsHuman == null ? "—" : needsHuman ? "людина" : "бот");
    }

    /** Правильна відповідь = збіглися ВСІ ТРИ мітки. */
    public boolean matches(Labels expected) {
        return parsed() && category.equals(expected.category()) && urgency.equals(expected.urgency())
                && needsHuman.equals(expected.needsHuman());
    }

    /** JSON трьох міток — так виглядає «правильна відповідь» у прикладах few-shot. */
    public String toJson() {
        return "{\"category\": \"" + category + "\", \"urgency\": \"" + urgency + "\", \"needs_human\": " + needsHuman + "}";
    }
}
