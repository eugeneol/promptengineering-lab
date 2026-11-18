package ua.knu.promptlab.lab4;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Лабораторна 4. Варіант: тема і 12 питань студентів (9 з еталоном, 3 без відповіді в положеннях). Дані —
 * {@code src/main/resources/lab4/variants/V01.json … V08.json}; спільне питання кроку 1 — {@code lab4/common.json}.
 *
 * <p>Еталон питання з відповіддю — документ і 1–2 ДОСЛІВНІ фрагменти положення (а не номер шматка: номери різні для різних
 * нарізок), плюс 1–3 ключові факти (слова чи числа, які має містити правильна відповідь; «|» — допустимі варіанти).
 * Шматок «влучив», якщо містить будь-який фрагмент (після нормалізації {@link TextNorm}).
 * Номер варіанта: LAB_VARIANT = ((номер у журналі − 1) mod 8) + 1, як у лабах 1–3.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Lab4Variant(int variant, String title, List<String> docs, List<Question> questions) {

    public static final int COUNT = 8;
    /** Скільки питань варіанта йде в крок 1 (разом зі спільним — 5). */
    public static final int STEP1_OWN = 4;
    /** Назви типів питань (поле kind). */
    public static final String KIND_TWO = "два пункти";

    /**
     * Питання студента.
     *
     * @param kind      тип складності: «пряме», «синонім» (інші слова, ніж у тексті), «код» (номер, код, скорочення), «два пункти»
     * @param doc       id положення з відповіддю (для answerable = false — null)
     * @param fragments 1–2 дослівні фрагменти з відповіддю
     * @param keyFacts  1–3 ключові слова чи числа правильної відповіді («двох|2» — будь-який варіант)
     * @param step1     чи йде питання в крок 1 (без документів)
     * @param why       для питань без відповіді — чому відповіді немає; для інших — коментар (необов'язково)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Question(String id, String text, String kind, boolean answerable, String doc, List<String> fragments,
                          @JsonProperty("key_facts") List<String> keyFacts, boolean step1, String why) {
        public List<String> fragments() {
            return fragments == null ? List.of() : fragments;
        }

        public List<String> keyFacts() {
            return keyFacts == null ? List.of() : keyFacts;
        }

        /** Короткий номер для консолі: «03» замість «V01-03». */
        public String shortId() {
            int i = id.indexOf('-');
            return i < 0 ? id : id.substring(i + 1);
        }

        /** Тип «два пункти»: відповідь складається з двох місць, тож знайдено лише тоді, коли знайдено ВСІ фрагменти. */
        public boolean needsAll() {
            return KIND_TWO.equals(kind) && fragments().size() > 1;
        }

        /** Чи містить шматок хоч один еталонний фрагмент. */
        public boolean matches(Chunk c) {
            for (String f : fragments()) if (TextNorm.containsNormalized(c.text(), f)) return true;
            return false;
        }

        /** Чи покривають шматки еталон: для «двох пунктів» — кожен фрагмент є в якомусь шматку, інакше — хоч один фрагмент у хоч одному. */
        public boolean coveredBy(java.util.Collection<Chunk> cs) {
            if (!needsAll()) return cs.stream().anyMatch(this::matches);
            for (String f : fragments()) if (cs.stream().noneMatch(c -> TextNorm.containsNormalized(c.text(), f))) return false;
            return true;
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    public static Lab4Variant load(int n) {
        if (n < 1 || n > COUNT) throw new IllegalArgumentException("варіант має бути від 1 до " + COUNT + ", а не " + n);
        return read(String.format("/lab4/variants/V%02d.json", n), Lab4Variant.class);
    }

    /** Набір питань MOCK — до трьох вигаданих положень «Умовного університету» (однаковий для всіх варіантів). */
    public static Lab4Variant mock(int n) {
        Lab4Variant m = read("/lab4/mock/questions.json", Lab4Variant.class);
        Lab4Variant real = load(n);
        return new Lab4Variant(n, real.title() + " (MOCK: питання до вигаданих положень)", m.docs(), m.questions());
    }

    public static List<Lab4Variant> loadAll() {
        List<Lab4Variant> all = new ArrayList<>();
        for (int n = 1; n <= COUNT; n++) all.add(load(n));
        return all;
    }

    /** Спільне питання кроку 1 для всіх варіантів: «Скільки разів можна перескладати іспит?» (у MOCK — своє). */
    public static Question common(boolean mock) {
        return read(mock ? "/lab4/mock/common.json" : "/lab4/common.json", Question.class);
    }

    private static <T> T read(String path, Class<T> type) {
        try (InputStream in = Lab4Variant.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("Не знайдено ресурс " + path + " (src/main/resources). Maven → Reload і перезапустіть.");
            return JSON.readValue(in, type);
        } catch (IOException e) {
            throw new IllegalStateException("Не вдалося прочитати " + path + ": " + e.getMessage(), e);
        }
    }

    /** 9 питань з відповіддю — на них міряємо пошук (кроки 3–4). */
    public List<Question> answerable() {
        return questions.stream().filter(Question::answerable).toList();
    }

    /** Питання кроку 1: спільне + {@value #STEP1_OWN} позначені step1. */
    public List<Question> step1(Question common) {
        List<Question> out = new ArrayList<>();
        out.add(common);
        questions.stream().filter(Question::step1).limit(STEP1_OWN).forEach(out::add);
        return out;
    }

    /** Номер варіанта з LAB_VARIANT (у MOCK без нього — 1); інакше IllegalArgumentException з підказкою для студента. */
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
                + "  Ваш варіант: N = ((номер у журналі − 1) mod " + COUNT + ") + 1 — той самий, що в лабах 1–3.\n"
                + "  Допишіть у файл .env (у корені проєкту) рядок LAB_VARIANT=N, наприклад LAB_VARIANT=3, і запустіть ще раз.\n"
                + "  Без ключа можна перевірити запуск конфігурацією «Лаба 4 (MOCK)».\n";
    }
}
