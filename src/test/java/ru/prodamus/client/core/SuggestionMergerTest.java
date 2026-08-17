package ru.prodamus.client.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SuggestionMergerTest {
    @Test
    void replacesAnIncompleteBeginningWithTheFullPhrase() {
        String early = "Скажи, что это не";
        String full = "Скажи, что это ни к чему не обязывает, просто покажешь, как можно упростить работу.";

        assertThat(SuggestionMerger.related(early, full)).isTrue();
        assertThat(SuggestionMerger.merge(early, full, true)).isEqualTo(full);
    }

    @Test
    void splicesARevisedTailWithoutLosingTheBeginning() {
        String first = "Предложи сделать короткую демонстрацию на 10 минут, чтобы они просто увидели возможности.";
        String revisedTail = "минут, чтобы они просто посмотрели на возможности. Скажи, что это ни к чему не обязывает.";

        assertThat(SuggestionMerger.related(first, revisedTail)).isTrue();
        assertThat(SuggestionMerger.merge(first, revisedTail, true))
                .isEqualTo("Предложи сделать короткую демонстрацию на 10 минут, чтобы они просто посмотрели на возможности. Скажи, что это ни к чему не обязывает.");
    }

    @Test
    void keepsADifferentRecommendationAsANewThought() {
        assertThat(SuggestionMerger.related(
                "Предложи обсудить текущие задачи и приоритеты на неделю.",
                "Спроси, какая задача сейчас самая горящая.")).isFalse();
    }

    @Test
    void identifiesTruncatedGarbage() {
        assertThat(SuggestionMerger.completeThought("Скажи, что это не")).isFalse();
        assertThat(SuggestionMerger.completeThought("горящая. Предложи")).isFalse();
        assertThat(SuggestionMerger.completeThought(
                "Скажи, что это ни к чему не обязывает, и предложи короткую демонстрацию.")).isTrue();
    }
}
