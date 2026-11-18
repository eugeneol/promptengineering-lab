package ua.knu.promptlab.steps;

import ua.knu.promptlab.ApiException;
import ua.knu.promptlab.Console;
import ua.knu.promptlab.LlmClient;

/** Запускає всі шість кроків поспіль. Зручно для демонстрації «від початку до кінця». */
public final class RunAll {

    private RunAll() {
    }

    public static void main(String[] args) {
        Console.init();
        Lab1Variant variant = Lab1Variant.currentOrExit(); // LAB_VARIANT з .env; не задано — варіант 1
        try (LlmClient client = LlmClient.create()) {
            runAll(client, variant);
        }
    }

    private static void runAll(LlmClient client, Lab1Variant v) {
        System.out.println("Провайдер: " + client.provider() + "   Модель: " + client.model());

        Runnable[] steps = {
                () -> Step1FirstRequest.run(client, v),
                () -> Step2SystemAndMaxTokens.run(client, v),
                () -> Step3UsageAndCost.run(client, v),
                () -> Step4TokensUaVsEn.run(client, v),
                () -> Step5NonDeterminism.run(client, v),
                () -> Step6PromptVariants.run(client, v)
        };

        for (Runnable step : steps) {
            try {
                step.run();
            } catch (ApiException e) {
                System.out.println("\n  !!! Крок завершився помилкою: " + e);
            }
        }
    }
}
