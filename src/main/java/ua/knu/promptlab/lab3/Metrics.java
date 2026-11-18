package ua.knu.promptlab.lab3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Лабораторна 3. Уся арифметика вимірювань — окремо від мережі, щоб її можна було перевірити тестами
 * на підроблених відповідях (Lab3Test).
 *
 * <ul>
 *   <li>{@link #score} — точність: правильна відповідь = збіглися ВСІ ТРИ мітки (з урахуванням допустимих alt);</li>
 *   <li>{@link #noise} і {@link #compare} — розкид двох однакових прогонів і правило «різниця не більша за розкид → висновку немає»;</li>
 *   <li>{@link #confusions} і {@link #matrix} — які категорії модель плутає;</li>
 *   <li>{@link #pickHardest} — 5 найважчих звернень за помилками попередніх кроків;</li>
 *   <li>{@link #vote} і {@link #route} — голосування кількох відповідей, згода і поріг «авто / людина»;</li>
 *   <li>{@link #gainPrice} — ціна приросту: скільки вихідних токенів коштує +1 правильне звернення.</li>
 * </ul>
 */
public final class Metrics {

    private Metrics() {
    }

    /** Результат на наборі звернень. */
    public record Score(int n, int correct, int categoryOk, int urgencyOk, int humanOk, int traps, int trapsCorrect) {
        public double accuracy() {
            return n == 0 ? 0 : (double) correct / n;
        }

        public double trapAccuracy() {
            return traps == 0 ? 0 : (double) trapsCorrect / traps;
        }

        /** «9/15 (60%)». */
        public String accuracyText() {
            return frac(correct, n);
        }

        public String trapsText() {
            return traps == 0 ? "— (пасток немає)" : frac(trapsCorrect, traps);
        }
    }

    public static String frac(int a, int b) {
        return b == 0 ? "—" : String.format(Locale.ROOT, "%d/%d (%.0f%%)", a, b, 100.0 * a / b);
    }

    /** «6,7 п.п.» — одне звернення з n у відсоткових пунктах. */
    public static String pp(double inquiries, int n) {
        return String.format(Locale.ROOT, "%.1f", n == 0 ? 0 : 100.0 * inquiries / n).replace('.', ',') + " п.п.";
    }

    public static Score score(List<Variant.Inquiry> qs, List<Labels> got) {
        check(qs, got);
        int ok = 0, cat = 0, urg = 0, hum = 0, traps = 0, trapsOk = 0;
        for (int i = 0; i < qs.size(); i++) {
            Variant.Inquiry q = qs.get(i);
            Labels g = got.get(i);
            boolean all = q.accepts(g);
            ok += all ? 1 : 0;
            cat += q.fieldOk("category", g) ? 1 : 0;
            urg += q.fieldOk("urgency", g) ? 1 : 0;
            hum += q.fieldOk("needs_human", g) ? 1 : 0;
            if (q.isTrap()) {
                traps++;
                trapsOk += all ? 1 : 0;
            }
        }
        return new Score(qs.size(), ok, cat, urg, hum, traps, trapsOk);
    }

    /** Які мітки не збіглися: «category», «urgency», «needs_human» через кому; порожньо, якщо все правильно. */
    public static String wrongFields(Variant.Inquiry q, Labels got) {
        if (!got.parsed()) return "не розібрано";
        List<String> w = new ArrayList<>();
        for (String f : Variant.FIELDS) if (!q.fieldOk(f, got)) w.add(f);
        return String.join(", ", w);
    }

    // ------------------------------------------------------------------ шум і висновок

    /**
     * Шум однакових прогонів: точності (у зверненнях) кількох серій з однаковими параметрами.
     * spread = max − min, але не менше 1: «на рівному місці» різниця в одне звернення теж може бути випадковою.
     */
    public record Noise(int n, List<Integer> runs) {
        public double mean() {
            return runs.stream().mapToInt(Integer::intValue).average().orElse(0);
        }

        public int rawSpread() {
            int min = runs.stream().mapToInt(Integer::intValue).min().orElse(0);
            int max = runs.stream().mapToInt(Integer::intValue).max().orElse(0);
            return max - min;
        }

        public int spread() {
            return Math.max(1, rawSpread());
        }
    }

    public static Noise noise(int n, int... correctPerRun) {
        List<Integer> r = new ArrayList<>();
        for (int c : correctPerRun) r.add(c);
        return new Noise(n, r);
    }

    /** Висновок про техніку: ефект = її точність − база (у зверненнях); |ефект| ≤ розкиду → висновку немає. */
    public record Verdict(double effect, int spread, int n, int sign) {
        /** «+2,0 зв. (13,3 п.п.) при розкиді 1 → краще за шум». */
        public String text() {
            return effectText() + " при розкиді " + spread + " → " + conclusion();
        }

        public String effectText() {
            return String.format(Locale.ROOT, "%+.1f", effect).replace('.', ',').replace('-', '−') + " зв. (" + (effect >= 0 ? "+" : "−") + pp(Math.abs(effect), n) + ")";
        }

        public String conclusion() {
            return sign > 0 ? "краще за шум" : sign < 0 ? "гірше за шум" : "висновку немає (у межах шуму)";
        }
    }

    public static Verdict compare(int correct, double baseline, int spread, int n) {
        double effect = correct - baseline;
        int sign = Math.abs(effect) <= spread + 1e-9 ? 0 : effect > 0 ? 1 : -1;
        return new Verdict(effect, spread, n, sign);
    }

    // ------------------------------------------------------------------ сплутані категорії

    /** Сплутані пари категорій «очікувано → отримано» з кількістю, від найчастішої. Звернення з допустимою іншою категорією (alt) не рахуються. */
    public static Map<String, Integer> confusions(List<Variant.Inquiry> qs, List<Labels> got) {
        check(qs, got);
        Map<String, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < qs.size(); i++) {
            if (qs.get(i).fieldOk("category", got.get(i))) continue;
            m.merge(qs.get(i).category() + " → " + got.get(i).category(), 1, Integer::sum);
        }
        Map<String, Integer> sorted = new LinkedHashMap<>();
        m.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).forEach(e -> sorted.put(e.getKey(), e.getValue()));
        return sorted;
    }

    /** Скільки помилок категорії припало саме на пару варіанта (в будь-який бік). */
    public static int pairErrors(List<Variant.Inquiry> qs, List<Labels> got, List<String> pair) {
        check(qs, got);
        int c = 0;
        for (int i = 0; i < qs.size(); i++) {
            if (qs.get(i).fieldOk("category", got.get(i))) continue;
            if (pair.contains(qs.get(i).category()) && pair.contains(got.get(i).category())) c++;
        }
        return c;
    }

    /** Матриця сплутувань категорій: рядки — очікувано, стовпці — отримано (останній стовпець — «не розібрано»). */
    public static int[][] matrix(List<Variant.Inquiry> qs, List<Labels> got) {
        check(qs, got);
        int k = Variant.CATEGORIES.size();
        int[][] m = new int[k][k + 1];
        for (int i = 0; i < qs.size(); i++) {
            int r = Variant.CATEGORIES.indexOf(qs.get(i).category());
            int c = Variant.CATEGORIES.indexOf(got.get(i).category());
            m[r][c < 0 ? k : c]++;
        }
        return m;
    }

    // ------------------------------------------------------------------ найважчі звернення

    /** Обране «найважче» звернення і чому його обрано. */
    public record Pick(Variant.Inquiry inquiry, int score, String reason) {
    }

    /**
     * 5 найважчих — динамічно: звернення з помилками в zero-shot s1/s2 і few-shot та з розбіжністю s1 ≠ s2
     * (бал = 2 × кількість помилок + 1 за розбіжність; при рівності — пастки, потім за порядком).
     * Якщо таких менше n — добираємо пастками, потім запасним списком з JSON (hardestFallback).
     */
    public static List<Pick> pickHardest(List<Variant.Inquiry> qs, List<Labels> zero1, List<Labels> zero2, List<Labels> few,
                                         List<String> fallback, int n) {
        check(qs, zero1);
        check(qs, zero2);
        check(qs, few);
        List<Pick> scored = new ArrayList<>();
        for (int i = 0; i < qs.size(); i++) {
            Variant.Inquiry q = qs.get(i);
            List<String> why = new ArrayList<>();
            int errors = 0;
            if (!q.accepts(zero1.get(i))) {
                errors++;
                why.add("✗ zero s1");
            }
            if (!q.accepts(zero2.get(i))) {
                errors++;
                why.add("✗ zero s2");
            }
            if (!q.accepts(few.get(i))) {
                errors++;
                why.add("✗ few-shot");
            }
            boolean disagree = !zero1.get(i).key().equals(zero2.get(i).key());
            if (disagree) why.add("s1 ≠ s2");
            int s = 2 * errors + (disagree ? 1 : 0);
            if (s > 0) scored.add(new Pick(q, s, String.join(", ", why)));
        }
        scored.sort(Comparator.comparingInt((Pick p) -> -p.score()).thenComparing(p -> !p.inquiry().isTrap())
                .thenComparingInt(p -> qs.indexOf(p.inquiry())));
        List<Pick> out = new ArrayList<>(scored.subList(0, Math.min(n, scored.size())));
        for (Variant.Inquiry q : qs) {
            if (out.size() >= n) break;
            if (q.isTrap() && out.stream().noneMatch(p -> p.inquiry() == q)) out.add(new Pick(q, 0, "добрано: пастка"));
        }
        if (fallback != null) {
            for (String id : fallback) {
                if (out.size() >= n) break;
                qs.stream().filter(q -> q.id().equals(id)).findFirst().ifPresent(q -> {
                    if (out.stream().noneMatch(p -> p.inquiry() == q)) out.add(new Pick(q, 0, "добрано: запасний список"));
                });
            }
        }
        for (Variant.Inquiry q : qs) {
            if (out.size() >= n) break;
            if (out.stream().noneMatch(p -> p.inquiry() == q)) out.add(new Pick(q, 0, "добрано за порядком"));
        }
        // у таблицях — за номером звернення
        out.sort(Comparator.comparingInt(p -> qs.indexOf(p.inquiry())));
        return out;
    }

    // ------------------------------------------------------------------ голосування і поріг

    /** Результат голосування: найчастіша відповідь і скільки з total відповідей з нею збіглися. */
    public record Vote(Labels majority, int agree, int total) {
        public String agreementText() {
            return agree + "/" + total;
        }
    }

    /**
     * Голосування більшістю: відповіді однакові, якщо збігаються всі три мітки ({@link Labels#key()}).
     * При нічиїй (наприклад, усі три різні) перемагає та, що трапилась першою — тобто перша вибірка.
     */
    public static Vote vote(List<Labels> samples) {
        if (samples.isEmpty()) throw new IllegalArgumentException("немає відповідей для голосування");
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, Labels> first = new LinkedHashMap<>();
        for (Labels s : samples) {
            counts.merge(s.key(), 1, Integer::sum);
            first.putIfAbsent(s.key(), s);
        }
        String best = null;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (best == null || e.getValue() > counts.get(best)) best = e.getKey();
        }
        return new Vote(first.get(best), counts.get(best), samples.size());
    }

    /**
     * Маршрутизація за порогом згоди.
     *
     * @param toHuman      скільки звернень віддано людині (згода нижче порогу)
     * @param auto         скільки пішло автоматично
     * @param autoCorrect  з автоматичних — правильних (усі 3 мітки)
     * @param caughtErrors помилки більшості, які поріг «спіймав» (віддав людині)
     * @param missedErrors помилки, які все одно пішли автоматично
     * @param missedHuman  з пропущених — ті, де потрібна людина (needs_human = true), а бот вирішив відповісти сам
     */
    public record Routing(int threshold, int total, int toHuman, int auto, int autoCorrect, int caughtErrors, int missedErrors,
                          int missedHuman) {
        public String humanShareText() {
            return frac(toHuman, total);
        }

        public String autoAccuracyText() {
            return auto == 0 ? "— (автоматично нічого)" : frac(autoCorrect, auto);
        }
    }

    /** Звернення йде автоматично, якщо згода ≥ threshold (з vote.total()); інакше — людині. */
    public static Routing route(List<Vote> votes, List<Variant.Inquiry> qs, int threshold) {
        if (votes.size() != qs.size()) throw new IllegalArgumentException("різна кількість голосувань і звернень");
        int human = 0, auto = 0, autoOk = 0, caught = 0, missed = 0, missedHuman = 0;
        for (int i = 0; i < votes.size(); i++) {
            Vote v = votes.get(i);
            Variant.Inquiry q = qs.get(i);
            boolean ok = q.accepts(v.majority());
            if (v.agree() >= threshold) {
                auto++;
                if (ok) autoOk++;
                else {
                    missed++;
                    if (!q.accepted("needs_human").contains("false") && v.majority().parsed() && !v.majority().needsHuman()) missedHuman++;
                }
            } else {
                human++;
                if (!ok) caught++;
            }
        }
        return new Routing(threshold, votes.size(), human, auto, autoOk, caught, missed, missedHuman);
    }

    // ------------------------------------------------------------------ ціна приросту

    /**
     * Ціна приросту точності: скільки ДОДАТКОВИХ вихідних токенів (разом на всіх зверненнях набору, порівняно з базою)
     * припадає на одне додатково правильне звернення.
     *
     * @param gain           приріст: правильних у техніки − правильних у бази (у зверненнях)
     * @param extraOutTokens додаткові вихідні токени разом на всіх зверненнях (може бути від'ємним)
     */
    public record GainPrice(int gain, double extraOutTokens) {
        public boolean hasGain() {
            return gain > 0;
        }

        /** Токенів на +1 правильне звернення; NaN, якщо приросту немає; 0, якщо приріст «безкоштовний». */
        public double perCorrect() {
            return gain > 0 ? Math.max(0, extraOutTokens) / gain : Double.NaN;
        }

        /** «≈190 вих. токенів на +1 правильне (+2 зв. за +380 токенів)» або «приросту немає (+380 токенів без користі)». */
        public String text() {
            long extra = Math.round(extraOutTokens);
            String extraText = (extra >= 0 ? "+" : "−") + Math.abs(extra) + " " + tokens(extra);
            if (gain <= 0) return "приросту немає" + (extra >= 1 ? " (" + extraText + " без користі)" : "");
            if (perCorrect() < 1) return "+" + gain + " зв. без помітних додаткових вихідних токенів";
            long per = Math.round(perCorrect());
            return "≈" + per + " вих. " + tokens(per) + " на +1 правильне (+" + gain + " зв. за " + extraText + ")";
        }
    }

    /** «токен / токени / токенів» за числом. */
    public static String tokens(long n) {
        long a = Math.abs(n) % 100, b = a % 10;
        if (b == 1 && a != 11) return "токен";
        if (b >= 2 && b <= 4 && (a < 12 || a > 14)) return "токени";
        return "токенів";
    }

    /**
     * @param baseCorrect  правильних у бази
     * @param baseOutTotal вихідних токенів бази разом на всіх зверненнях набору
     * @param correct      правильних у техніки
     * @param outTotal     вихідних токенів техніки разом (для голосування — сума всіх вибірок)
     */
    public static GainPrice gainPrice(int baseCorrect, double baseOutTotal, int correct, double outTotal) {
        return new GainPrice(correct - baseCorrect, outTotal - baseOutTotal);
    }

    /** У скількох зверненнях дві серії відповідей розійшлися (хоч в одній мітці) — міра шуму між прогонами. */
    public static int disagreements(List<Labels> a, List<Labels> b) {
        if (a.size() != b.size()) throw new IllegalArgumentException("різна довжина серій");
        int d = 0;
        for (int i = 0; i < a.size(); i++) if (!a.get(i).key().equals(b.get(i).key())) d++;
        return d;
    }

    private static void check(List<Variant.Inquiry> qs, List<Labels> got) {
        if (qs.size() != got.size()) throw new IllegalArgumentException("звернень " + qs.size() + ", відповідей " + got.size());
    }
}
