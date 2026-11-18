package ua.knu.promptlab.steps;

import ua.knu.promptlab.ApiException;
import ua.knu.promptlab.Console;
import ua.knu.promptlab.LlmClient;
import ua.knu.promptlab.LlmResponse;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Крок 5. Недетермінізм: один запит × 10 при temperature 1.0 і 0.0.
 *
 * Мета: виміряйте розкид відповідей при T = 1.0 і T = 0.0. Ми НЕ припускаємо заздалегідь, що T = 0 дасть
 * одну відповідь — це питання до експерименту. Щоб «Кава на паузі» і «"Кава на паузі".» не рахувались як дві різні
 * назви, перед підрахунком відповіді нормалізуються (див. {@link #normalize(String)}); друкуються і сирі, і нормалізовані.
 *
 * <p>Деякі новіші моделі (наприклад, Claude Sonnet 5.5) відповідають 400 на будь-яку temperature. Тоді клієнт повторює запит
 * без неї, а крок чесно пише, що обидві серії йдуть з налаштуванням моделі за замовчуванням.
 */
public final class Step5NonDeterminism {

    private static final int RUNS = 10;
    // 2048: у Gemini «міркування» входить у ліміт; з малим лімітом частина відповідей була б порожньою
    private static final int MAX_TOKENS = 2048;

    private Step5NonDeterminism() {
    }

    public static void main(String[] args) {
        Console.init();
        Lab1Variant variant = Lab1Variant.currentOrExit();
        try (LlmClient client = LlmClient.create()) {
            run(client, variant);
        }
    }

    /** Крок для варіанта з LAB_VARIANT (не задано — варіант 1). */
    public static void run(LlmClient client) {
        run(client, Lab1Variant.current());
    }

    public static void run(LlmClient client, Lab1Variant variant) {
        Console.banner("Крок 5. Недетермінізм: той самий запит " + RUNS + " разів");

        String user = variant.step5().user(); // що називаємо — з даних варіанта (кав'ярня, книгарня…)

        boolean warned = false;
        for (double temperature : new double[]{1.0, 0.0}) {
            Console.section("temperature = " + temperature + (client.temperatureSupported() ? ""
                    : " — НЕ надсилається: модель не приймає temperature, серія з налаштуванням за замовчуванням"));
            if (!warned && !client.temperatureSupported()) {
                warned = true;
                warnNoTemperature(client);
            }
            Map<String, Integer> normalized = new LinkedHashMap<>();
            Set<String> rawUnique = new LinkedHashSet<>();
            System.out.printf("  %3s  %-12s %8s  %-32s %s%n", "№", "зупинка", "міркув.", "сира відповідь", "→ нормалізована");
            try {
                for (int i = 1; i <= RUNS; i++) {
                    LlmResponse r = client.ask(null, user, temperature, MAX_TOKENS);
                    String raw = r.text().strip();
                    String norm = normalize(raw);
                    rawUnique.add(raw);
                    normalized.merge(norm.isEmpty() ? "(порожня відповідь)" : norm, 1, Integer::sum);
                    System.out.printf("  %3d  %-12s %8d  %-32s → %s%n", i, r.stopReason(), r.thinkingTokens(),
                            Console.clip(raw.isEmpty() ? "(порожньо)" : raw.replace('\n', ' '), 32), norm);
                    if (!warned && !client.temperatureSupported()) {
                        warned = true;
                        warnNoTemperature(client);
                    }
                    pause(client.requestDelayMs());
                }
            } catch (ApiException e) {
                System.out.println("  API відхилило запит: " + e.getMessage());
                if (e.status() == 429) {
                    System.out.println("  Це ліміт запитів на хвилину. Збільште паузу в .env (наприклад, LAB_DELAY_MS=6000), зачекайте хвилину і запустіть крок знову.");
                } else {
                    System.out.println("  (деякі моделі обмежують параметри семплінгу — перевірте документацію моделі)");
                }
                continue;
            }
            System.out.println("  Розподіл після нормалізації:");
            normalized.forEach((name, n) -> System.out.printf("  %2d× %s%n", n, name));
            Console.kv("унікальних відповідей", "сирих " + rawUnique.size() + ", після нормалізації " + normalized.size() + " із " + RUNS);
        }
        System.out.println("  Нормалізація: нижній регістр, без лапок «»\" і зірочок *, без пробілів, апострофа, крапки чи знака оклику по краях.");
        if (!client.temperatureSupported()) {
            System.out.println("  Увага: " + client.model() + " не приймає temperature — обидві серії з налаштуванням за замовчуванням;"
                    + " порівняйте розкид двох серій і поясніть.");
        }

        Console.discuss(
                "Скільки різних назв (після нормалізації) ви отримали при T = 1.0 і при T = 0.0? Чи гарантує T = 0 однакову відповідь?",
                "Чому «у мене спрацювало один раз» нічого не доводить? Скільки запусків потрібно, щоб довіряти промпту?",
                "Коли нам потрібна варіативність (брейнсторм), а коли — ні (витяг даних, класифікація)?");
    }

    private static void warnNoTemperature(LlmClient client) {
        System.out.println("  ! " + client.model() + " не приймає temperature; обидві серії — з налаштуванням за замовчуванням.");
        System.out.println("    Порівняйте розкид двох серій і поясніть: що це означає для висновку «T = 0 дає одну відповідь»?");
    }

    /** Лапки й зірочки (markdown **жирний**) прибираються всюди; апостроф ' — лише по краях, щоб «кав'ярня» не зіпсувалась. */
    private static final String QUOTES = "«»\"“”„*";

    /** Приводить відповідь до порівнюваного вигляду: «"Кава на паузі".», **Кава на паузі** і «кава на паузі» → одна назва. */
    public static String normalize(String answer) {
        String s = answer == null ? "" : answer.toLowerCase(Locale.ROOT);
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) if (QUOTES.indexOf(c) < 0) b.append(c);
        s = b.toString().replaceAll("\\s+", " ").strip();
        String prev;
        do {
            prev = s;
            if (s.startsWith("'")) s = s.substring(1);
            if (s.endsWith("'") || s.endsWith(".") || s.endsWith("!")) s = s.substring(0, s.length() - 1);
            s = s.strip();
        } while (!s.equals(prev));
        return s;
    }

    private static void pause(long ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
