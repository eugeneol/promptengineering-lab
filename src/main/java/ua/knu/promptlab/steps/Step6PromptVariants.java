package ua.knu.promptlab.steps;

import ua.knu.promptlab.LlmClient;
import ua.knu.promptlab.Console;
import ua.knu.promptlab.LlmResponse;
import ua.knu.promptlab.Pricing;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Крок 6. Три варіанти промпта + автоматична перевірка результату.
 * A — поганий; B — + роль і формат (Give Direction, Specify Format); C — + приклад (Provide Examples).
 * Метод checkFormat — найпростіший «eval» (принцип Evaluate Quality): правило, яке перевіряє код, а не око.
 *
 * <p>Місто, формулювання завдання, приклад і вимоги формату — з даних вашого варіанта ({@link Lab1Variant}, крок 6):
 * у варіанті 1 це Львів і «рівно 3 пункти, кожен до 15 слів», в інших — своє місто і свої вимоги
 * (кількість пунктів 3–5, слів 10–15; у частині варіантів ще нумерований список, число в кожному пункті, без назви міста
 * або «Тема: пояснення»).
 */
public final class Step6PromptVariants {

    private Step6PromptVariants() {
    }

    public static void main(String[] args) {
        Console.init();
        Lab1Variant variant = Lab1Variant.currentOrExit();
        try (LlmClient client = LlmClient.create()) {
            run(client, variant);
        }
    }

    private record Variant(String name, String system, String user) {
    }

    /** Крок для варіанта з LAB_VARIANT (не задано — варіант 1). */
    public static void run(LlmClient client) {
        run(client, Lab1Variant.current());
    }

    public static void run(LlmClient client, Lab1Variant data) {
        Console.banner("Крок 6. Порівнюємо промпти за п'ятьма принципами");
        Lab1Variant.Format format = data.step6().format();
        System.out.println("  Місто варіанта: " + data.city() + ";  вимоги формату (checkFormat): " + data.step6().formatText());

        // тексти — з даних варіанта (lab1/variants/V0N.json → step6); у варіанті 1 — Львів, як до появи варіантів
        String task = data.step6().task();
        String example = data.step6().example();

        Map<String, Variant> variants = new LinkedHashMap<>();
        variants.put("A", new Variant(data.labelA(), null, data.step6().promptA()));
        variants.put("B", new Variant("B: роль + формат", "Ти — досвідчений гід-краєзнавець.", task));
        variants.put("C", new Variant("C: роль + формат + приклад", "Ти — досвідчений гід-краєзнавець.", task + example));

        Pricing price = Pricing.forModel(client.model()).orElse(null);
        Map<String, String> verdicts = new LinkedHashMap<>();

        for (Variant v : variants.values()) {
            // 2048: «міркування» Gemini входить у ліміт; обрізаний список зіпсував би перевірку формату
            LlmResponse r = client.ask(v.system(), v.user(), null, 2048);
            Console.section("Варіант " + v.name());
            System.out.println(Console.indent(r.text()));
            String verdict = checkFormat(r.text(), format);
            String cost = price == null ? "—" : Console.usd(price.cost(r));
            Console.kv("причина зупинки", r.stopReason() + "   (у відповіді API: " + r.rawStopReason() + ")"
                    + (r.thinkingTokens() > 0 ? "   «міркування»: " + r.thinkingTokens() + " токенів" : ""));
            verdicts.put(v.name(), String.format("in=%-4d out=%-4d %s  stop=%-10s %s", r.inputTokens(), r.outputTokens(), cost,
                    r.stopReason(), verdict));
        }

        Console.section("Підсумок");
        verdicts.forEach((name, line) -> System.out.printf("  %-30s %s%n", name, line));

        Console.discuss(
                "Який принцип дав найбільший ефект: роль, формат чи приклад? Як це виміряти, а не «здається»?",
                "Приклад зробив промпт довшим і дорожчим. Коли це виправдано?",
                "Автоперевірка ловить формат, але не фактичну правильність. Що ще можна перевіряти кодом? (до цього повернемось у лекції 6 про оцінювання)");
    }

    /** Мінімальний eval варіанта 1 (і лабораторної до появи варіантів): чи рівно 3 пункти, і чи в кожному не більше 15 слів. */
    public static String checkFormat(String text) {
        return checkFormat(text, Lab1Variant.LEGACY_FORMAT);
    }

    /** Нумерований пункт: «1. », «2) » на початку рядка. */
    private static final Pattern NUMBERED = Pattern.compile("^\\d{1,2}[.)]\\s+");
    private static final Pattern TOPIC_COLON = Pattern.compile("^[^:]{2,40}:\\s*\\S.*");

    /**
     * Мінімальний eval за вимогами варіанта: кількість пунктів, найбільше слів у пункті і (якщо є) додаткова вимога до
     * КОЖНОГО пункту. Пункти — рядки, що починаються з «- », «• » або «* » (для нумерованого списку — з «1. », «2) »…).
     */
    public static String checkFormat(String text, Lab1Variant.Format f) {
        int bullets = 0;
        int maxWords = 0;
        int extraOk = 0;
        for (String raw : text.strip().split("\n")) {
            String line = raw.strip();
            String item = null;
            if (f.numbered()) {
                Matcher m = NUMBERED.matcher(line);
                if (m.find()) item = line.substring(m.end());
            } else if (line.startsWith("- ") || line.startsWith("• ") || line.startsWith("* ")) {
                item = line.substring(2);
            }
            if (item == null) continue;
            bullets++;
            int words = item.strip().split("\\s+").length;
            maxWords = Math.max(maxWords, words);
            if (f.extra() != null && extraHolds(item.strip(), f)) extraOk++;
        }
        boolean ok = bullets == f.bullets() && maxWords <= f.maxWords() && (f.extra() == null || extraOk == bullets);
        String details = String.format("пунктів: %d, макс. слів у пункті: %d", bullets, maxWords);
        if (f.extra() != null) details += String.format(", %s: %d з %d", extraLabel(f.extra()), extraOk, bullets);
        return String.format("%s  (%s)", ok ? "PASS" : "FAIL", details);
    }

    /** Чи виконано додаткову вимогу для одного пункту. */
    static boolean extraHolds(String item, Lab1Variant.Format f) {
        return switch (f.extra()) {
            case "digit" -> item.chars().anyMatch(Character::isDigit);
            case "no_city" -> !item.toLowerCase(Locale.ROOT).contains(f.cityStem());
            case "colon" -> TOPIC_COLON.matcher(item.replace("*", "")).matches();
            default -> throw new IllegalArgumentException("невідома вимога формату: " + f.extra());
        };
    }

    private static String extraLabel(String extra) {
        return switch (extra) {
            case "digit" -> "з числом";
            case "no_city" -> "без назви міста";
            case "colon" -> "у вигляді «Тема: пояснення»";
            default -> extra;
        };
    }
}
