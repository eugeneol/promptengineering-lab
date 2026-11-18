package ua.knu.promptlab.lab4;

/**
 * Лабораторна 4. Шматок (чанк) документа і його службові поля (metadata): з якого положення, розділ, сторінка.
 *
 * @param id       мітка шматка, яку бачить модель у кроці 5 і яку перевіряє валідатор (наприклад, «osv_proces-8.3-07»)
 * @param section  назва розділу (для нарізки «за пунктами»; у фіксованій нарізці — порожньо)
 * @param page     сторінка PDF, на якій шматок починається
 * @param text     текст шматка без заголовка
 * @param strategy якою нарізкою отримано
 */
public record Chunk(String id, String docId, String docTitle, String section, int page, String text, Chunker.Strategy strategy) {

    /** Текст, з якого рахуємо ембединг: префікс документа gemini-embedding-2 «title: … | text: …» (слайд 9). */
    public String embedText() {
        return Prefixes.document(strategy.withHeader() ? docTitle : null, bodyWithSection());
    }

    /**
     * Текст для BM25: для нарізки із заголовком — назва розділу + текст, інакше лише текст. Назву документа в BM25 не додаємо:
     * вона однакова для всіх шматків положення і лише «розмазує» бали.
     */
    public String searchText() {
        return bodyWithSection();
    }

    /** Текст шматка; для нарізки із заголовком він починається з назви розділу. */
    public String bodyWithSection() {
        return strategy.withHeader() && section != null && !section.isBlank() ? "Розділ: " + section + ".\n" + text : text;
    }

    public int length() {
        return text.length();
    }
}
