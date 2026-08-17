package ru.prodamus.client.core;

import java.util.Locale;
import java.util.Set;

/** Conservative guard against exposing a truncated Gemini stream as final advice. */
final class SuggestionQuality {
    private static final Set<String> INCOMPLETE_LAST_WORDS = Set.of(
            "а", "и", "или", "но", "что", "чтобы", "если", "когда", "как", "потому",
            "поэтому", "который", "которая", "которые", "для", "на", "в", "с", "к", "от"
    );

    private SuggestionQuality() { }

    static boolean isCompleteRecommendation(String value) {
        if (value == null) return false;
        String text = value.trim();
        if (text.length() < 12 || text.equals("—") || text.equals("-")) return false;
        char last = text.charAt(text.length() - 1);
        if (last == '—' || last == '–' || last == '-' || last == ':' || last == ';' || last == ',') return false;
        if (last != '.' && last != '!' && last != '?') return false;
        String normalized = text.toLowerCase(Locale.ROOT).replaceAll("[.!?…]+$", "").trim();
        int separator = Math.max(normalized.lastIndexOf(' '), normalized.lastIndexOf('\n'));
        String lastWord = separator < 0 ? normalized : normalized.substring(separator + 1);
        return !INCOMPLETE_LAST_WORDS.contains(lastWord);
    }
}
