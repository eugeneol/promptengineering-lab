package ua.knu.promptlab.lab4;

/**
 * Лабораторна 4. Префікси gemini-embedding-2 (слайд 9 лекції 4; ai.google.dev/gemini-api/docs/embeddings, звірено 12.11.2026).
 * У gemini-embedding-2 немає параметра task_type — роль тексту задає префікс у самому тексті:
 * <ul>
 *   <li>питання: {@code task: search result | query: {текст}};</li>
 *   <li>шматок документа: {@code title: {назва або none} | text: {текст}}.</li>
 * </ul>
 * Формат має бути однаковим для всіх шматків і всіх питань одного прогону.
 */
public final class Prefixes {

    private Prefixes() {
    }

    public static String query(String question) {
        return "task: search result | query: " + question;
    }

    public static String document(String title, String text) {
        return "title: " + (title == null || title.isBlank() ? "none" : title) + " | text: " + text;
    }
}
