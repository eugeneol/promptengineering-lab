package ua.knu.promptlab.lab3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Лабораторна 3, крок 3. Ваша правка промпта: одне додаткове правило і/або 1–2 власні приклади,
 * які дописуються до few-shot промпта кроку 2. Заповнюється в {@link MyPromptTweaks}.
 *
 * @param rule     одне правило (одне-два речення) або порожньо
 * @param examples 0–2 ваші приклади «звернення → правильна відповідь»
 */
public record Tweaks(String rule, List<Variant.Example> examples) {

    public static final int MAX_EXAMPLES = 2;
    public static final int MAX_RULE_CHARS = 400;
    /** Схожість Жаккара, з якої приклад вважається копією тестового звернення. */
    public static final double MAX_SIMILARITY = 0.5;

    public Tweaks {
        rule = rule == null ? "" : rule.strip();
        examples = examples == null ? List.of() : List.copyOf(examples);
    }

    public static final Tweaks NONE = new Tweaks("", List.of());

    public boolean isEmpty() {
        return rule.isEmpty() && examples.isEmpty();
    }

    /** Приклад для {@link MyPromptTweaks}: текст і три мітки. */
    public static Variant.Example example(String text, String category, String urgency, boolean needsHuman) {
        return new Variant.Example("ваш", text == null ? "" : text.strip(), category == null ? "" : category.strip().toLowerCase(Locale.ROOT),
                urgency == null ? "" : urgency.strip().toLowerCase(Locale.ROOT), needsHuman, null, "ваш приклад");
    }

    /** Що не так із правкою (порожній список — усе гаразд). Повідомлення — для студента. */
    public List<String> problems(Variant v) {
        List<String> p = new ArrayList<>();
        if (examples.size() > MAX_EXAMPLES) p.add("прикладів " + examples.size() + ", а можна не більше " + MAX_EXAMPLES);
        if (rule.length() > MAX_RULE_CHARS) p.add("правило задовге (" + rule.length() + " символів, можна до " + MAX_RULE_CHARS + ") — одне правило, одне-два речення");
        for (int i = 0; i < examples.size(); i++) {
            Variant.Example e = examples.get(i);
            String w = "приклад " + (i + 1) + ": ";
            if (e.text().isBlank()) p.add(w + "порожній текст");
            if (!Variant.CATEGORIES.contains(e.category())) p.add(w + "category «" + e.category() + "» — має бути одна з " + Variant.CATEGORIES);
            if (!Variant.URGENCIES.contains(e.urgency())) p.add(w + "urgency «" + e.urgency() + "» — має бути одна з " + Variant.URGENCIES);
            if (e.text().isBlank()) continue;
            var sim = v.mostSimilar(e.text());
            if (sim.isPresent() && sim.get().getValue() >= MAX_SIMILARITY) {
                Map.Entry<Variant.Inquiry, Double> s = sim.get();
                p.add(String.format(Locale.ROOT, "%sзанадто схожий на тестове звернення %s (схожість слів %.2f ≥ %.1f). "
                                + "Приклад-копія вимірює пам'ять, а не узагальнення — напишіть своє звернення на ту саму межу іншими словами",
                        w, s.getKey().shortId(), s.getValue(), MAX_SIMILARITY));
            }
        }
        return p;
    }
}
