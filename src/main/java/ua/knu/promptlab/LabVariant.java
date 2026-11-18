package ua.knu.promptlab;

/**
 * Номер варіанта для лабораторних 1 і 2 (лабораторна 3 має свій, суворіший {@code lab3.Variant.resolve}).
 *
 * <p>У .env: {@code LAB_VARIANT=N}, де N = ((номер у журналі − 1) mod 8) + 1 — той самий номер, що й у лабораторній 3.
 *
 * <p>Сумісність: частина групи здала лабораторні 1–2 ще без варіантів. Тому якщо LAB_VARIANT не задано, береться
 * варіант 1 (це саме ті дані, що були до появи варіантів) і друкується ОДИН рядок-попередження. Некоректне значення
 * (не число, 0, 9…) — помилка з підказкою, а не тихий варіант 1.
 */
public final class LabVariant {

    /** Скільки всього варіантів (однаково для лабораторних 1–3). */
    public static final int COUNT = 8;

    /** Рядок-попередження, коли LAB_VARIANT не задано. */
    public static final String MISSING_WARNING = "  Увага: LAB_VARIANT не задано — беремо варіант 1 (дані до появи варіантів). "
            + "Ваш варіант: N = ((номер у журналі − 1) mod " + COUNT + ") + 1, рядок LAB_VARIANT=N у файлі .env.";

    private static volatile boolean warned;

    private LabVariant() {
    }

    /** Чи задано LAB_VARIANT (непорожній рядок). */
    public static boolean isSet(String raw) {
        return raw != null && !raw.isBlank();
    }

    /**
     * Номер варіанта для лабораторних 1–2: не задано → 1; ціле 1–8 → воно; інакше IllegalArgumentException
     * з повідомленням для студента (main друкує його без стек-трейсу).
     */
    public static int resolveCompat(String raw) {
        if (!isSet(raw)) return 1;
        try {
            int n = Integer.parseInt(raw.strip());
            if (n >= 1 && n <= COUNT) return n;
        } catch (NumberFormatException ignored) {
            // нижче — та сама підказка
        }
        throw new IllegalArgumentException(howToChoose("LAB_VARIANT='" + raw.strip() + "' — має бути ціле число від 1 до " + COUNT + "."));
    }

    /** Номер із .env / змінних середовища; якщо не задано — 1 і (один раз за запуск) рядок-попередження. */
    public static int fromSettings() {
        String raw = Config.setting("LAB_VARIANT");
        int n = resolveCompat(raw);
        if (!isSet(raw) && !warned) {
            warned = true;
            System.out.println(MISSING_WARNING);
        }
        return n;
    }

    /** Підказка, як обрати варіант. */
    public static String howToChoose(String problem) {
        return "\n" + problem + "\n"
                + "  Ваш варіант: N = ((номер у журналі − 1) mod " + COUNT + ") + 1.\n"
                + "  Приклади: №1 → 1, №8 → 8, №9 → 1, №15 → 7, №23 → 7.\n"
                + "  Допишіть у файл .env (у корені проєкту) рядок LAB_VARIANT=N, наприклад LAB_VARIANT=3, і запустіть ще раз.\n";
    }
}
