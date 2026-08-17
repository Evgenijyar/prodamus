package ru.prodamus.client.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SuggestionQualityTest {
    @Test
    void rejectsTruncatedRecommendations() {
        assertThat(SuggestionQuality.isCompleteRecommendation("Отлично. Можем зарегистрировать ваш —")).isFalse();
        assertThat(SuggestionQuality.isCompleteRecommendation("Это бесплатно и ни к чему")).isFalse();
        assertThat(SuggestionQuality.isCompleteRecommendation("Для сферы:")).isFalse();
        assertThat(SuggestionQuality.isCompleteRecommendation("—")).isFalse();
    }

    @Test
    void acceptsCompleteRecommendations() {
        assertThat(SuggestionQuality.isCompleteRecommendation(
                "Давайте зарегистрируем аккаунт сейчас. Какой email лучше указать?")).isTrue();
        assertThat(SuggestionQuality.isCompleteRecommendation(
                "Уточните, пожалуйста, какой результат для вас сейчас наиболее важен.")).isTrue();
    }
}
