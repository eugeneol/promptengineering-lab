package ua.knu.promptlab;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Дрібні помічники для гарного виводу в консоль IDEA. */
public final class Console {

    private static final ObjectMapper JSON = new ObjectMapper();

    private Console() {
    }

    /** Примусово UTF-8, щоб кирилиця не ламалась на Windows. */
    public static void init() {
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));
    }

    public static void banner(String title) {
        String line = "=".repeat(Math.max(64, title.length() + 4));
        System.out.println("\n" + line + "\n  " + title + "\n" + line);
    }

    public static void section(String title) {
        System.out.println("\n--- " + title + " ---");
    }

    public static void kv(String key, Object value) {
        System.out.printf("  %-24s %s%n", key + ":", value);
    }

    public static void discuss(String... questions) {
        section("Питання для самоперевірки (відповіді — у звіті)");
        for (String q : questions) System.out.println("  ? " + q);
    }

    public static String usd(double v) {
        return String.format(Locale.ROOT, "$%.6f", v);
    }

    public static String pretty(JsonNode node) {
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (JsonProcessingException e) {
            return node.toString();
        }
    }

    public static String indent(String text) {
        return "    " + text.strip().replace("\n", "\n    ");
    }

    public static String clip(String s, int max) {
        String flat = s.strip().replace("\n", " ⏎ ");
        return flat.length() <= max ? flat : flat.substring(0, max - 1) + "…";
    }
}
