package ua.knu.promptlab.lab2;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import ua.knu.promptlab.LabVariant;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Лабораторна 2. Дані одного варіанта: домен тікетів (банк, провайдер, доставка…), системний промпт, десять повідомлень
 * клієнтів з очікуваними category / priority і ОБОВ'ЯЗКОВЕ правило для {@link TicketValidator#validate(Ticket)}.
 *
 * <p>Схема тікета ({@link TicketSchema}), enum категорій і визначення пріоритетів p0–p3 однакові для всіх варіантів;
 * у системному промпті змінюються лише назва продукту і приклади «критичного часу».
 *
 * <p>Дані лежать у {@code src/main/resources/lab2/variants/V01.json … V08.json}. Поле {@code note} пояснює очікування
 * для спірних кейсів. Не згодні з очікуванням — аргументуйте у звіті, але JSON не змінюйте.
 *
 * <p>Номер варіанта — у .env: {@code LAB_VARIANT=N}, N = ((номер у журналі − 1) mod 8) + 1. Без LAB_VARIANT береться
 * варіант 1 — це {@link TicketData} (дані, з якими лабораторну виконували до появи варіантів).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Lab2Variant(int variant, String title, String domain, String product, String system, Rule rule, List<Item> cases) {

    /**
     * Обов'язкове правило варіанта. Його пише СТУДЕНТ у {@link TicketValidator#validate(Ticket)} (разом із трьома власними).
     *
     * @param code   код правила: summary_max_len, summary_no_numbers, steps_infinitive, summary_mentions_product,
     *               steps_distinct_first_word, p3_max_steps, summary_no_latin
     * @param max    число для summary_max_len і p3_max_steps (інакше null)
     * @param text   формулювання правила
     * @param reason приклад речення-причини, яке validate() поверне моделі
     * @param why    навіщо таке правило
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Rule(String code, Integer max, String text, String reason, String why) {
    }

    /** Повідомлення клієнта з очікуваною відповіддю; note — пояснення для спірних кейсів (може бути порожнім). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(int id, String message, String category, String priority, String note) {
        public TicketData.Case toCase() {
            return new TicketData.Case(id, message, category, priority);
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static volatile Lab2Variant current;

    /** Завантажує варіант n (1–8) з ресурсів. */
    public static Lab2Variant load(int n) {
        if (n < 1 || n > LabVariant.COUNT) throw new IllegalArgumentException("варіант має бути від 1 до " + LabVariant.COUNT + ", а не " + n);
        String path = String.format("/lab2/variants/V%02d.json", n);
        try (InputStream in = Lab2Variant.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("Не знайдено ресурс " + path + " (src/main/resources). Maven → Reload і перезапустіть.");
            return JSON.readValue(in, Lab2Variant.class);
        } catch (IOException e) {
            throw new IllegalStateException("Не вдалося прочитати " + path + ": " + e.getMessage(), e);
        }
    }

    public static List<Lab2Variant> loadAll() {
        List<Lab2Variant> all = new ArrayList<>();
        for (int n = 1; n <= LabVariant.COUNT; n++) all.add(load(n));
        return all;
    }

    /** Варіант з LAB_VARIANT (не задано → 1 з попередженням; некоректно → IllegalArgumentException з підказкою). */
    public static Lab2Variant current() {
        if (current == null) current = load(LabVariant.fromSettings());
        return current;
    }

    /** Для main: як {@link #current()}, але некоректний LAB_VARIANT — повідомлення і вихід (без стек-трейсу). */
    static Lab2Variant currentOrExit() {
        try {
            return current();
        } catch (IllegalArgumentException e) {
            System.out.println(e.getMessage());
            System.exit(2);
            return null;
        }
    }

    /** Кейси у форматі {@link TicketData.Case} (для варіанта 1 — побайтово {@link TicketData#CASES}). */
    public List<TicketData.Case> ticketCases() {
        return cases.stream().map(Item::toCase).toList();
    }

    /** Пошук кейсу за точним текстом повідомлення — для імітації (FakeLlmServer). */
    public Optional<Item> findByMessage(String message) {
        return cases.stream().filter(c -> c.message().equals(message)).findFirst();
    }
}
