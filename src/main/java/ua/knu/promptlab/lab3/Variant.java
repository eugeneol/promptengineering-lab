package ua.knu.promptlab.lab3;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Лабораторна 3. Один варіант: 15 звернень студентів з очікуваними мітками (5 з них — пастки), 3 приклади few-shot
 * і особливість варіанта — пара категорій, яку модель найчастіше плутає.
 *
 * <p>Дані лежать у {@code src/main/resources/lab3/variants/V01.json … V08.json} — їх можна відкрити й прочитати.
 * Очікувані мітки задала людина за правилами з {@link Lab3Prompts#SYSTEM}. Для кількох спірних звернень допустимі ДВІ відповіді
 * (поле {@code alt}, наприклад {"urgency": ["normal", "low"]}) — обидві рахуються як збіг.
 * Якщо не згодні з міткою — аргументуйте у звіті, але НЕ змінюйте JSON (інакше числа не порівняти з однокурсниками).
 *
 * <p>Номер варіанта задається в .env: {@code LAB_VARIANT=N}, де N = ((номер у журналі − 1) mod 8) + 1.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Variant(int variant, String title, List<String> pair, String pairNote, List<Inquiry> requests, List<Example> examples,
                      List<String> hardestFallback) {

    /** Скільки всього варіантів. */
    public static final int COUNT = 8;
    /** Скільки прикладів few-shot іде в промпт (однаково для всіх варіантів). */
    public static final int FEW_SHOT_K = 3;

    public static final List<String> CATEGORIES = List.of("study", "electives", "schedule", "scholarship", "dorm", "access", "other");
    public static final List<String> URGENCIES = List.of("low", "normal", "high");
    public static final List<String> FIELDS = List.of("category", "urgency", "needs_human");

    /** Пастки: код у JSON → назва для консолі. */
    public static final Map<String, String> TRAPS = Map.ofEntries(
            Map.entry("sarcasm", "сарказм"), Map.entry("two_topics", "дві теми"), Map.entry("surzhyk", "суржик"),
            Map.entry("off_topic", "поза темою"), Map.entry("urgent_no_substance", "«терміново» без суті"),
            Map.entry("translit", "трансліт"), Map.entry("foreign_language", "інша мова"), Map.entry("emotional", "емоційне"),
            Map.entry("misleading_keyword", "оманливе слово"), Map.entry("hidden_urgency", "прихована терміновість"),
            Map.entry("third_party", "пише не студент"), Map.entry("complaint", "скарга"), Map.entry("negation", "заперечення"),
            Map.entry("rambling", "довге й плутане"));

    /**
     * Звернення студента з очікуваною відповіддю.
     *
     * @param trap       null — звичайне звернення; інакше код пастки (див. {@link #TRAPS})
     * @param trapField  яке поле пастка «атакує»: category | urgency | needs_human
     * @param distractor найправдоподібніша НЕПРАВИЛЬНА категорія
     * @param why        коротке «чому» (для пасток, спірних міток і звернень на межі пари)
     * @param alt        допустимі відповіді для спірних міток: поле → список значень (основне значення теж у списку); може бути null
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Inquiry(String id, String text, String category, String urgency,
                          @JsonProperty("needs_human") boolean needsHuman, String trap,
                          @JsonProperty("trap_field") String trapField, String distractor, String why,
                          Map<String, List<Object>> alt) {
        public boolean isTrap() {
            return trap != null && !trap.isBlank();
        }

        /** Короткий номер для консолі: «03» замість «V01-03». */
        public String shortId() {
            return id.substring(id.indexOf('-') + 1);
        }

        /** Основні очікувані мітки (без альтернатив). */
        public Labels expected() {
            return new Labels(category, urgency, needsHuman, null);
        }

        /** Допустимі значення поля як рядки: основне + alt (якщо є). */
        public List<String> accepted(String field) {
            List<Object> a = alt == null ? null : alt.get(field);
            if (a != null && !a.isEmpty()) return a.stream().map(x -> String.valueOf(x).toLowerCase(Locale.ROOT)).toList();
            return List.of(switch (field) {
                case "category" -> category;
                case "urgency" -> urgency;
                case "needs_human" -> String.valueOf(needsHuman);
                default -> throw new IllegalArgumentException("невідоме поле " + field);
            });
        }

        /** Чи збігається одне поле відповіді з допустимими значеннями. */
        public boolean fieldOk(String field, Labels got) {
            if (got == null || !got.parsed()) return false;
            String v = switch (field) {
                case "category" -> got.category();
                case "urgency" -> got.urgency();
                case "needs_human" -> String.valueOf(got.needsHuman());
                default -> throw new IllegalArgumentException("невідоме поле " + field);
            };
            return accepted(field).contains(v);
        }

        /** Правильна відповідь = збіглися ВСІ ТРИ мітки (з урахуванням допустимих альтернатив). */
        public boolean accepts(Labels got) {
            for (String f : FIELDS) if (!fieldOk(f, got)) return false;
            return true;
        }

        public boolean hasAlt() {
            return alt != null && !alt.isEmpty();
        }
    }

    /** Приклад для few-shot: НЕ схожий на жодне тестове звернення (перевіряє Lab3Test). pair — межа категорій, яку він показує (або null). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Example(String id, String text, String category, String urgency,
                          @JsonProperty("needs_human") boolean needsHuman, List<String> pair, String note) {
        public Labels labels() {
            return new Labels(category, urgency, needsHuman, null);
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Завантажує варіант n (1–8) з ресурсів. */
    public static Variant load(int n) {
        if (n < 1 || n > COUNT) throw new IllegalArgumentException("варіант має бути від 1 до " + COUNT + ", а не " + n);
        String path = String.format("/lab3/variants/V%02d.json", n);
        try (InputStream in = Variant.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("Не знайдено ресурс " + path + " (src/main/resources). Maven → Reload і перезапустіть.");
            return JSON.readValue(in, Variant.class);
        } catch (IOException e) {
            throw new IllegalStateException("Не вдалося прочитати " + path + ": " + e.getMessage(), e);
        }
    }

    public static List<Variant> loadAll() {
        List<Variant> all = new ArrayList<>();
        for (int n = 1; n <= COUNT; n++) all.add(load(n));
        return all;
    }

    /**
     * Номер варіанта з налаштування LAB_VARIANT. Повертає номер або кидає IllegalArgumentException
     * з ПОВІДОМЛЕННЯМ ДЛЯ СТУДЕНТА (без стек-трейсу його друкує Lab3Run). У режимі MOCK без LAB_VARIANT — варіант 1.
     */
    public static int resolve(String raw, boolean mock) {
        if (raw == null || raw.isBlank()) {
            if (mock) return 1;
            throw new IllegalArgumentException(howToChoose("Не задано LAB_VARIANT."));
        }
        try {
            int n = Integer.parseInt(raw.strip());
            if (n >= 1 && n <= COUNT) return n;
        } catch (NumberFormatException ignored) {
            // нижче — та сама підказка
        }
        throw new IllegalArgumentException(howToChoose("LAB_VARIANT='" + raw.strip() + "' — має бути ціле число від 1 до " + COUNT + "."));
    }

    static String howToChoose(String problem) {
        return "\n" + problem + "\n"
                + "  Ваш варіант: N = ((номер у журналі − 1) mod " + COUNT + ") + 1.\n"
                + "  Приклади: №1 → 1, №8 → 8, №9 → 1, №15 → 7, №23 → 7.\n"
                + "  Допишіть у файл .env (у корені проєкту) рядок LAB_VARIANT=N, наприклад LAB_VARIANT=3, і запустіть ще раз.\n"
                + "  Без ключа можна перевірити запуск конфігурацією «Лаба 3 (MOCK)» — там підійде будь-який варіант.\n";
    }

    public Inquiry byId(String id) {
        return requests.stream().filter(r -> r.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalStateException("немає звернення " + id + " у варіанті " + variant));
    }

    /** Приклади few-shot, що йдуть у промпт (перші {@link #FEW_SHOT_K}). */
    public List<Example> fewShot() {
        return fewShot(FEW_SHOT_K);
    }

    public List<Example> fewShot(int k) {
        return examples.subList(0, Math.min(Math.max(k, 0), examples.size()));
    }

    /** Пошук звернення за точним текстом — для імітації (FakeLlmServer). */
    public Optional<Inquiry> findByText(String text) {
        return requests.stream().filter(r -> r.text().equals(text)).findFirst();
    }

    /** Найсхожіше тестове звернення цього варіанта до тексту (для перевірки ваших прикладів): [id, схожість]. */
    public Optional<Map.Entry<Inquiry, Double>> mostSimilar(String text) {
        Inquiry best = null;
        double bs = -1;
        for (Inquiry q : requests) {
            double s = jaccard(text, q.text());
            if (s > bs) {
                bs = s;
                best = q;
            }
        }
        return best == null ? Optional.empty() : Optional.of(Map.entry(best, bs));
    }

    // ------------------------------------------------------------------ схожість текстів

    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}'’]+");

    /** Множина слів тексту (малими літерами). */
    public static Set<String> words(String text) {
        Set<String> w = new HashSet<>();
        Matcher m = WORD.matcher(text.toLowerCase(Locale.ROOT));
        while (m.find()) w.add(m.group());
        return w;
    }

    /** Схожість Жаккара за словами: |спільні| / |усі|. 1.0 — той самий набір слів; ≥ 0.5 — приклад майже копіює звернення. */
    public static double jaccard(String a, String b) {
        Set<String> wa = words(a), wb = words(b);
        Set<String> union = new HashSet<>(wa);
        union.addAll(wb);
        if (union.isEmpty()) return 0;
        Set<String> inter = new HashSet<>(wa);
        inter.retainAll(wb);
        return (double) inter.size() / union.size();
    }
}
