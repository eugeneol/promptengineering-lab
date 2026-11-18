package ua.knu.promptlab.lab4;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Лабораторна 4. Один текст — у консоль і в target/lab4-results.md: заголовок кроку, «Що це / Питання», таблиця (Markdown —
 * читається і в консолі), «Як читати» (кожна колонка одним рядком) і приклад тлумачення рядка.
 */
final class Report {

    private final StringBuilder md = new StringBuilder();

    void h1(String t) {
        md.append("# ").append(t).append("\n\n");
    }

    /** Розділ кроку: у консолі — «--- … ---», у файлі — «## …». */
    void section(String t) {
        System.out.println("\n--- " + t + " ---");
        md.append("## ").append(t).append("\n\n");
    }

    void sub(String t) {
        System.out.println("\n  " + t);
        md.append("### ").append(t).append("\n\n");
    }

    /** Текст і в консоль, і у файл. */
    void p(String t) {
        System.out.println(indent(t));
        md.append(t).append("\n\n");
    }

    /** Лише в консоль. */
    void console(String t) {
        System.out.println(indent(t));
    }

    /** Лише у файл. */
    void file(String t) {
        md.append(t).append("\n\n");
    }

    void what(String what, String question) {
        p("Що це: " + what + " Питання: " + question);
    }

    void table(String markdown) {
        System.out.println(indent(markdown));
        md.append(markdown.stripTrailing()).append("\n\n");
    }

    void how(List<String> lines) {
        System.out.println("  Як читати:");
        md.append("Як читати:\n\n");
        for (String l : lines) {
            System.out.println("  · " + l);
            md.append("- ").append(l).append("\n");
        }
        md.append("\n");
    }

    void example(String t) {
        p("Приклад: " + t);
    }

    void kv(String k, Object v) {
        System.out.printf("  %-26s %s%n", k + ":", v);
        md.append("- **").append(k).append("**: ").append(v).append("\n");
    }

    void endList() {
        md.append("\n");
    }

    String markdown() {
        return md.toString();
    }

    static String indent(String t) {
        return "  " + t.replace("\n", "\n  ");
    }

    /** Комірка таблиці Markdown: без «|» і переносів рядків. */
    static String cell(String s) {
        return s == null ? "" : s.replace("|", "/").replace("\r", "").replace("\n", " ").strip();
    }

    static String clip(String s, int max) {
        String t = cell(s);
        return t.length() <= max ? t : t.substring(0, max - 1) + "…";
    }

    static void write(Path p, String md) {
        if (p == null) return;
        try {
            Path parent = p.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(p, md, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("  [не вдалося записати " + p + ": " + e.getMessage() + "]");
        }
    }
}
