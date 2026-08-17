package ru.prodamus.client.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ForecastQualityTest {
    @Test
    void acceptsExactlyThreeCompleteScenarios() {
        String value = """
                1 | НАМЕРЕНИЕ: цена | ПРИЗНАКИ: сколько; стоит | ОТВЕТ: Уточните, пожалуйста, какой тариф вы рассматриваете?
                2 | НАМЕРЕНИЕ: сроки | ПРИЗНАКИ: когда; запуск | ОТВЕТ: Давайте уточним желаемую дату запуска.
                3 | НАМЕРЕНИЕ: сомнение | ПРИЗНАКИ: не уверен; подумаю | ОТВЕТ: Что именно мешает вам принять решение сейчас?
                """;

        assertThat(ForecastQuality.normalize(value)).contains("1 |", "2 |", "3 |");
    }

    @Test
    void rejectsJunkIncompleteOrMissingScenarios() {
        assertThat(ForecastQuality.normalize("Вот мой прогноз:\n1 | что-то")).isEmpty();
        assertThat(ForecastQuality.normalize("""
                1 | НАМЕРЕНИЕ: цена | ПРИЗНАКИ: сколько | ОТВЕТ: Уточните бюджет.
                2 | НАМЕРЕНИЕ: сроки | ПРИЗНАКИ: когда | ОТВЕТ: Давайте обсудим
                3 | НАМЕРЕНИЕ: отказ | ПРИЗНАКИ: не надо | ОТВЕТ: Что вас останавливает?
                """)).isEmpty();
    }
}
