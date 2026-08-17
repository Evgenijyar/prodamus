package ru.prodamus.client.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Strict gate that prevents malformed or conversational forecast output from reaching the recommender. */
final class ForecastQuality {
    private static final Pattern SCENARIO = Pattern.compile(
            "^\\s*([123])\\s*\\|\\s*НАМЕРЕНИЕ\\s*:\\s*.+?\\s*\\|\\s*ПРИЗНАКИ\\s*:\\s*.+?\\s*\\|\\s*ОТВЕТ\\s*:\\s*(.+?)\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private ForecastQuality() {
    }

    static String normalize(String value) {
        if (value == null || value.isBlank()) return "";
        String text = value.trim();
        if (text.equals("—") || text.equals("-")) return "";
        List<String> scenarios = new ArrayList<>(3);
        for (String raw : text.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            Matcher matcher = SCENARIO.matcher(line);
            if (!matcher.matches()) return "";
            int expected = scenarios.size() + 1;
            if (Integer.parseInt(matcher.group(1)) != expected) return "";
            if (!SuggestionQuality.isCompleteRecommendation(matcher.group(2))) return "";
            scenarios.add(line);
        }
        return scenarios.size() == 3 ? String.join("\n", scenarios) : "";
    }
}
