package ua.knu.promptlab.lab2;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Лабораторна 2. Тікет підтримки — те, що ми хочемо отримати від моделі замість вільного тексту.
 *
 * Форму (назви полів, типи, enum) гарантує схема у запиті, {@link TicketSchema}.
 * Зміст (чи пріоритет відповідає тексту, чи не забагато кроків) перевіряє {@link TicketValidator}.
 *
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)} — щоб зайве поле від моделі не ламало розбір:
 * це теж рішення про надійність (краще прочитати й перевірити, ніж впасти на парсингу).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Ticket(
        String category,     // login | billing | bug | feature_request | other
        String priority,     // p0 | p1 | p2 | p3
        String sentiment,    // calm | frustrated | angry
        String summary,      // одне речення для менеджера
        List<String> steps   // 1–5 кроків для інженера підтримки
) {
    public static final List<String> CATEGORIES = List.of("login", "billing", "bug", "feature_request", "other");
    public static final List<String> PRIORITIES = List.of("p0", "p1", "p2", "p3");
    public static final List<String> SENTIMENTS = List.of("calm", "frustrated", "angry");
}
