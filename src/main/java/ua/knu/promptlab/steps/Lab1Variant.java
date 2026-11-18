package ua.knu.promptlab.steps;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import ua.knu.promptlab.Config;
import ua.knu.promptlab.LabVariant;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Лабораторна 1. Дані одного варіанта: тексти запитів кроків 1–6.
 *
 * <p>Що варіюється: місто (кроки 1–3 і 6), абзац UA/EN у кроці 4 (той самий зміст двома мовами), що називаємо в кроці 5
 * (кав'ярня, книгарня…) і вимоги формату кроку 6, які перевіряє {@link Step6PromptVariants#checkFormat(String, Format)}.
 * Ціни й моделі однакові для всіх.
 *
 * <p>Дані лежать у {@code src/main/resources/lab1/variants/V01.json … V08.json} — їх можна відкрити й прочитати.
 * Не змінюйте їх: інакше ваші числа не порівняти з однокурсниками того самого варіанта.
 *
 * <p>Номер варіанта — у .env: {@code LAB_VARIANT=N}, де N = ((номер у журналі − 1) mod 8) + 1 (як у лабораторній 3).
 * Без LAB_VARIANT береться варіант 1 — це дані, з якими лабораторну виконували до появи варіантів.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Lab1Variant(int variant, String title, String city, String cityGenitive, String object,
                          Prompt step1, Prompt step2, Prompt step3, Step4 step4, Step5 step5, Step6 step6) {

    /** Пара «system + user» (system може бути null). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Prompt(String system, String user) {
    }

    /** Крок 4: один і той самий зміст англійською та українською. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Step4(String en, String ua, String topic) {
    }

    /** Крок 5: що називаємо і повний текст запиту. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Step5(String object, String user) {
    }

    /** Крок 6: промпт A, завдання B, приклад для C (дописується до B) і вимоги формату для автоперевірки. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Step6(String promptA, String task, String example, String formatText, Format format) {
    }

    /**
     * Вимоги формату кроку 6.
     *
     * @param bullets   скільки пунктів має бути рівно
     * @param maxWords  найбільше слів у пункті
     * @param numbered  true — нумерований список «1. …», false — маркований «- …» (також «• » і «* »)
     * @param extra     додаткова вимога до кожного пункту або null: digit | no_city | colon
     * @param extraText людський опис додаткової вимоги
     * @param cityStem  для no_city: основа назви міста (нижній регістр), яку не можна згадувати
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Format(int bullets, int maxWords, boolean numbered, String extra, String extraText, String cityStem) {
    }

    /** Вимоги формату варіанта 1 (і всієї лабораторної до появи варіантів): рівно 3 пункти «- », кожен до 15 слів. */
    public static final Format LEGACY_FORMAT = new Format(3, 15, false, null, null, null);

    private static final ObjectMapper JSON = new ObjectMapper();
    private static volatile Lab1Variant current;

    /** Завантажує варіант n (1–8) з ресурсів. */
    public static Lab1Variant load(int n) {
        if (n < 1 || n > LabVariant.COUNT) throw new IllegalArgumentException("варіант має бути від 1 до " + LabVariant.COUNT + ", а не " + n);
        String path = String.format("/lab1/variants/V%02d.json", n);
        try (InputStream in = Lab1Variant.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("Не знайдено ресурс " + path + " (src/main/resources). Maven → Reload і перезапустіть.");
            return JSON.readValue(in, Lab1Variant.class);
        } catch (IOException e) {
            throw new IllegalStateException("Не вдалося прочитати " + path + ": " + e.getMessage(), e);
        }
    }

    public static List<Lab1Variant> loadAll() {
        List<Lab1Variant> all = new ArrayList<>();
        for (int n = 1; n <= LabVariant.COUNT; n++) all.add(load(n));
        return all;
    }

    /** Варіант з LAB_VARIANT (не задано → 1 з попередженням; некоректно → IllegalArgumentException з підказкою). */
    public static Lab1Variant current() {
        if (current == null) current = load(LabVariant.fromSettings());
        return current;
    }

    /** Для main: як {@link #current()}, але некоректний LAB_VARIANT — повідомлення і вихід (без стек-трейсу). */
    static Lab1Variant currentOrExit() {
        try {
            Lab1Variant v = current();
            // без LAB_VARIANT — лише одне речення-підказка (LabVariant.MISSING_WARNING), решта виводу як до появи варіантів
            if (LabVariant.isSet(Config.setting("LAB_VARIANT"))) System.out.println("Варіант " + v.variant() + ": " + v.title());
            return v;
        } catch (IllegalArgumentException e) {
            System.out.println(e.getMessage());
            System.exit(2);
            return null;
        }
    }

    /** Підпис для промпта A в консолі: «A: «Напиши про Львів»». */
    public String labelA() {
        return "A: «" + step6.promptA() + "»";
    }
}
