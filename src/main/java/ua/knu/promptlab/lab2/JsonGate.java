package ua.knu.promptlab.lab2;

import ua.knu.promptlab.LlmClient;
import ua.knu.promptlab.LlmResponse;

import java.util.ArrayList;
import java.util.List;

/**
 * Ворота валідації з повтором (лекція 3, частина 3): виклик → перевірка → при невдачі повтор
 * з реченням-причиною, не більше {@code maxRetries} разів. Вичерпано — явна невдача, а не «якось далі».
 *
 * Це ВНУТРІШНІЙ цикл (повтор генерації). Зовнішній цикл — таймаути, інфраструктура — лишається
 * в HttpJson і в оркестрації (лекція 5).
 *
 * При повторі моделі передається лише речення-причина, дописане до того самого повідомлення користувача;
 * попередня (відхилена) відповідь моделі НЕ передається — кожна спроба є окремим запитом без історії.
 */
public final class JsonGate {

    /**
     * Що сталося: тікет або null, кількість спроб, остання причина, сумарні токени, причини всіх відхилених спроб
     * і чи зупинилось усе через обрізання лімітом вихідних токенів (truncated — це конфігурація, а не модель).
     */
    public record Outcome(Ticket ticket, int attempts, String lastError, int inputTokens, int outputTokens,
                          List<String> errors, boolean truncated) {
        public boolean ok() {
            return ticket != null;
        }
    }

    private final LlmClient client;
    private final int maxRetries;
    private final int maxTokens;

    public JsonGate(LlmClient client, int maxRetries, int maxTokens) {
        this.client = client;
        this.maxRetries = maxRetries;
        this.maxTokens = maxTokens;
    }

    /** Повний цикл для одного повідомлення клієнта: схема у запиті + validate + повтор. */
    public Outcome run(String system, String message) {
        String user = "Повідомлення клієнта:\n\"\"\"" + message + "\"\"\"";
        List<String> errors = new ArrayList<>();
        int in = 0, out = 0;
        for (int attempt = 1; attempt <= 1 + maxRetries; attempt++) {
            LlmResponse r = client.askJson(system, user, TicketSchema.json(), maxTokens);
            in += r.inputTokens();
            out += r.outputTokens();
            if ("max_tokens".equals(r.stopReason())) {
                // обрізаний JSON повторювати тим самим лімітом безглуздо — це помилка конфігурації, не моделі
                errors.add("відповідь обірвано лімітом вихідних токенів (" + maxTokens + ")");
                return new Outcome(null, attempt, errors.get(errors.size() - 1), in, out, errors, true);
            }
            TicketValidator.Result res = TicketValidator.check(r.text());
            if (res.ok()) return new Outcome(res.ticket(), attempt, null, in, out, errors, false);
            errors.add(res.error());
            // семантичний відгук замість stack trace: одне речення, яке модель може виправити.
            // Попередню відповідь не надсилаємо — лише причину (дешевше; модель генерує тікет заново).
            user = user + "\n\nПопередня відповідь відхилена: " + res.error() + ". Виправ і поверни тікет знову.";
            pause();
        }
        return new Outcome(null, 1 + maxRetries, errors.get(errors.size() - 1), in, out, errors, false);
    }

    private void pause() {
        long ms = client.requestDelayMs();
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
