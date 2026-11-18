package ua.knu.promptlab.lab2;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Ворота валідації для {@link Ticket}: три шари.
 *
 * <ol>
 *   <li>{@link #parse(String)} — форма: чи це рівно один JSON-об'єкт з усіма п'ятьма полями і чи лягає він у record (Jackson);</li>
 *   <li>{@link #normalize(Ticket)} — карти нормалізації: «critical» → «p0», «authentication» → «login»
 *       (дрейф міток між моделями та версіями; потрібно для рівнів 2 і 3, коли схеми в запиті немає);</li>
 *   <li>{@link #validate(Ticket)} — зміст: правила, яких провайдер не знає.</li>
 * </ol>
 *
 * Кожен метод повертає не boolean, а ОДНЕ РЕЧЕННЯ з причиною: саме його ми віддаємо моделі при повторі.
 * Виняткам тут не місце: невалідна відповідь моделі — очікувана подія, а не збій програми.
 */
public final class TicketValidator {

    /** Строгий розбір: текст після JSON-об'єкта — помилка (за замовчуванням Jackson його мовчки ігнорує). */
    private static final ObjectMapper STRICT = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    /** Лише щоб відрізнити «зламаний JSON» від «JSON + зайвий текст» у реченні-причині. */
    private static final ObjectMapper LENIENT = new ObjectMapper();
    /** Усі п'ять полів обов'язкові — як required у схемі. */
    static final List<String> FIELDS = List.of("category", "priority", "sentiment", "summary", "steps");

    /** Дрейф міток пріоритету: різні моделі пишуть одне й те саме по-різному. Строго: невідоме → помилка. */
    static final Map<String, String> PRIORITY_MAP = Map.ofEntries(
            Map.entry("p0", "p0"), Map.entry("critical", "p0"), Map.entry("blocker", "p0"),
            Map.entry("p1", "p1"), Map.entry("urgent", "p1"), Map.entry("high", "p1"),
            Map.entry("p2", "p2"), Map.entry("medium", "p2"), Map.entry("normal", "p2"),
            Map.entry("p3", "p3"), Map.entry("low", "p3"));
    static final Map<String, String> CATEGORY_MAP = Map.ofEntries(
            Map.entry("login", "login"), Map.entry("authentication", "login"), Map.entry("auth", "login"), Map.entry("account access", "login"),
            Map.entry("billing", "billing"), Map.entry("payment", "billing"),
            Map.entry("bug", "bug"), Map.entry("error", "bug"),
            Map.entry("feature_request", "feature_request"), Map.entry("feature", "feature_request"),
            Map.entry("other", "other"));

    private TicketValidator() {
    }

    /** Результат воріт: або тікет, або одне речення з причиною. */
    public record Result(Ticket ticket, String error) {
        public boolean ok() {
            return error == null;
        }

        static Result fail(String why) {
            return new Result(null, why);
        }
    }

    /** Усі три шари разом — те, що викликає цикл повтору. */
    public static Result check(String rawText) {
        Result parsed = parse(rawText);
        if (!parsed.ok()) return parsed;
        Result normalized = normalize(parsed.ticket());
        if (!normalized.ok()) return normalized;
        Optional<String> problem = validate(normalized.ticket());
        return problem.map(Result::fail).orElse(normalized);
    }

    /**
     * Шар 1: форма. Знімаємо лише обгортку навколо JSON — вступ на кшталт «Ось ваш JSON:» і markdown-огорожу
     * ```json … ``` на початку й у кінці (типовий збій рівня 3). Усе інше — строго:
     * текст ПІСЛЯ JSON-об'єкта (FAIL_ON_TRAILING_TOKENS) і відсутнє поле — це помилка, а не «якось розберемо».
     */
    public static Result parse(String rawText) {
        if (rawText == null || rawText.isBlank()) return Result.fail("відповідь порожня");
        return parseExact(stripFences(rawText.strip()));
    }

    /**
     * Строгий розбір без зняття обгортки: рівно один JSON-об'єкт (пробіли навколо дозволені) з усіма п'ятьма полями.
     * Саме так крок 7.1 рахує «валідний JSON з першого разу».
     */
    public static Result parseExact(String text) {
        if (text == null || text.isBlank()) return Result.fail("відповідь порожня");
        JsonNode tree;
        try {
            tree = STRICT.readTree(text);
        } catch (JsonProcessingException e) {
            if (hasTrailingText(text)) {
                return Result.fail("після JSON-об'єкта є зайвий текст — поверни лише JSON без пояснень");
            }
            return Result.fail("відповідь не є валідним JSON за схемою тікета: " + clip(e.getOriginalMessage()));
        }
        if (tree == null || !tree.isObject()) return Result.fail("відповідь має бути JSON-об'єктом тікета, а не " + kind(tree));
        List<String> missing = new ArrayList<>();
        for (String f : FIELDS) if (!tree.hasNonNull(f)) missing.add(f);
        if (missing.size() == 1) return Result.fail("бракує поля " + missing.get(0));
        if (!missing.isEmpty()) return Result.fail("бракує полів " + String.join(", ", missing));
        try {
            return new Result(STRICT.treeToValue(tree, Ticket.class), null);
        } catch (JsonProcessingException e) {
            return Result.fail("відповідь не є валідним JSON за схемою тікета: " + clip(e.getOriginalMessage()));
        }
    }

    /** Шар 2: нормалізація міток. Пріоритет — строго, категорія — строго, настрій — з типовим значенням. */
    public static Result normalize(Ticket t) {
        String priority = t.priority() == null ? null : PRIORITY_MAP.get(t.priority().toLowerCase(Locale.ROOT).strip());
        if (priority == null) return Result.fail("поле priority має бути одним з p0, p1, p2, p3, а не «" + t.priority() + "»");
        String category = t.category() == null ? null : CATEGORY_MAP.get(t.category().toLowerCase(Locale.ROOT).strip());
        if (category == null) return Result.fail("поле category має бути одним з " + Ticket.CATEGORIES + ", а не «" + t.category() + "»");
        String sentiment = t.sentiment() == null ? "frustrated"
                : Ticket.SENTIMENTS.contains(t.sentiment().toLowerCase(Locale.ROOT)) ? t.sentiment().toLowerCase(Locale.ROOT) : "frustrated";
        return new Result(new Ticket(category, priority, sentiment, t.summary(), t.steps()), null);
    }

    /**
     * Шар 3: зміст. Правила, яких немає у схемі (або які провайдер не застосовує). Повертає Optional.empty(),
     * якщо все гаразд, інакше — одне речення, яке модель зможе виправити.
     *
     * <p>Уже наявні правила (6):
     * <ol>
     *   <li>summary не порожнє;</li>
     *   <li>summary не довше 200 символів;</li>
     *   <li>steps не порожній;</li>
     *   <li>steps не більше 5 кроків;</li>
     *   <li>у steps немає порожніх рядків;</li>
     *   <li>feature_request не може мати пріоритет p0.</li>
     * </ol>
     * Правила 3 і 4 потрібні для відповідей без схеми (рівень 3) і для Claude, якому клієнт не надсилає maxItems
     * (див. ClaudeClient.toClaudeSchema); для Gemini зі схемою це гарантує minItems/maxItems.
     *
     * <p>СТУДЕНТАМ (крок 3): додайте сюди ЧОТИРИ правила:
     * <ol>
     *   <li>ОБОВ'ЯЗКОВЕ правило ВАШОГО ВАРІАНТА — у пакеті вхідних даних вашого варіанта і в
     *       src/main/resources/lab2/variants/V0N.json (поле rule.text; Step7 друкує його на початку). Варіант 1 (і всі, хто здавав
     *       до появи варіантів): summary не довше 80 символів. Без жорсткого правила на справжніх моделях часто p = 1 і v у кроці 4
     *       не визначене;</li>
     *   <li>ТРИ ВЛАСНІ — ІНШІ, ніж шість вище й обов'язкове, і не дублюють схему (enum, required).
     *       Наприклад: зв'язок полів, слова-ознаки в тексті.</li>
     * </ol>
     * Кожне правило повертає одне речення-причину; на кожне — тест у Lab2Test.
     */
    public static Optional<String> validate(Ticket t) {
        if (t == null) return Optional.of("тікет порожній");
        // 1–2: summary
        if (t.summary() == null || t.summary().isBlank()) return Optional.of("поле summary порожнє, потрібне одне речення для менеджера");
        if (t.summary().length() > 200) return Optional.of("поле summary задовге (" + t.summary().length() + " символів), потрібно до 200");
        // 3–4: для відповідей без схеми (рівень 3); зі схемою це гарантує maxItems (Gemini). Claude maxItems не приймає — тому правило лишається тут
        if (t.steps() == null || t.steps().isEmpty()) return Optional.of("поле steps порожнє, потрібен хоча б один крок");
        if (t.steps().size() > 5) return Optional.of("поле steps містить " + t.steps().size() + " кроків, дозволено не більше 5");
        // 5: порожній крок
        if (t.steps().stream().anyMatch(s -> s == null || s.isBlank())) return Optional.of("у steps є порожній крок");
        // 6: зв'язок двох полів — схема такого не виразить
        if ("p0".equals(t.priority()) && "feature_request".equals(t.category()))
            return Optional.of("побажання (feature_request) не може мати пріоритет p0");

        // --- СТУДЕНТАМ: обов'язкове правило вашого варіанта (варіант 1: «summary ≤ 80 символів») + ваші три правила (інші, ніж 1–6) ---

        return Optional.empty();
    }

    /** Знімає обгортку: вступний текст до першої «{» і markdown-огорожу ``` у самому кінці. Текст після JSON лишається — його ловить строгий розбір. */
    static String stripFences(String s) {
        String t = s.strip();
        int start = t.indexOf('{');
        if (start > 0) t = t.substring(start);               // «Ось ваш JSON:\n```json\n{...» → «{...»
        if (t.endsWith("```")) t = t.substring(0, t.length() - 3).stripTrailing(); // «...}\n```» → «...}»
        return t;
    }

    /** Чи розбирається перший JSON-об'єкт, а ламає все лише хвіст після нього. */
    private static boolean hasTrailingText(String text) {
        try {
            LENIENT.readTree(text);
            return true;
        } catch (JsonProcessingException e) {
            return false;
        }
    }

    private static String kind(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) return "null";
        if (n.isArray()) return "масивом";
        if (n.isTextual()) return "рядком";
        return n.getNodeType().toString().toLowerCase(Locale.ROOT);
    }

    private static String clip(String s) {
        if (s == null) return "";
        String flat = s.replace('\n', ' ');
        return flat.length() > 90 ? flat.substring(0, 87) + "..." : flat;
    }
}
