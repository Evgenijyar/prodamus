package ru.prodamus.client.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Local, deterministic reducer for active-listening suggestions. It performs
 * no AI calls: repeated beginnings and revised tails update the existing card,
 * while a genuinely different recommendation starts a new card.
 */
public final class SuggestionMerger {
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Set<String> STOP_WORDS = Set.of(
            "а", "и", "или", "но", "что", "это", "как", "к", "ко", "в", "во", "на", "с", "со", "по",
            "за", "для", "из", "от", "до", "не", "ни", "ли", "же", "бы", "чтобы", "просто", "можно",
            "ваш", "ваша", "ваше", "ваши", "его", "ее", "их", "мы", "вы", "он", "она", "они"
    );
    private static final Set<String> INCOMPLETE_ENDINGS = Set.of(
            "а", "и", "или", "но", "что", "чтобы", "если", "когда", "как", "потому", "поэтому", "для",
            "на", "в", "во", "с", "со", "к", "от", "не", "ни", "это", "этот", "эта", "эти", "ваш",
            "ваша", "ваше", "вашего", "который", "которая", "которые", "предложи", "скажи", "спроси"
    );

    private SuggestionMerger() { }

    public static boolean related(String existing, String candidate) {
        String left = normalize(existing);
        String right = normalize(candidate);
        if (left.isBlank() || right.isBlank()) return false;
        if (left.startsWith(right) || right.startsWith(left) || left.contains(right) || right.contains(left)) return true;

        List<Token> first = tokens(existing);
        List<Token> second = tokens(candidate);
        if (first.isEmpty() || second.isEmpty()) return false;
        if (commonPrefix(first, second) >= 2) return true;
        if (prefixOverlap(first, second).length() >= 2) return true;

        Set<String> firstContent = contentStems(first);
        Set<String> secondContent = contentStems(second);
        Set<String> common = new HashSet<>(firstContent);
        common.retainAll(secondContent);
        int union = firstContent.size() + secondContent.size() - common.size();
        double jaccard = union == 0 ? 0 : (double) common.size() / union;
        if (common.size() >= 2 && jaccard >= 0.34) return true;

        String firstDirective = first.getFirst().stem();
        String secondDirective = second.getFirst().stem();
        return firstDirective.equals(secondDirective) && common.size() >= 1;
    }

    public static String merge(String existing, String candidate, boolean candidateComplete) {
        String base = clean(existing);
        String next = clean(candidate);
        if (base.isBlank()) return next;
        if (next.isBlank()) return base;

        String left = normalize(base);
        String right = normalize(next);
        if (left.equals(right)) return next.length() >= base.length() ? next : base;
        if (right.startsWith(left)) return next;
        if (left.startsWith(right)) return candidateComplete ? next : base;

        List<Token> baseTokens = tokens(base);
        List<Token> nextTokens = tokens(next);
        Overlap overlap = prefixOverlap(baseTokens, nextTokens);
        if (overlap.length() >= 2) {
            int baseStart = baseTokens.get(overlap.baseIndex()).start();
            String prefix = base.substring(0, baseStart);
            return clean(prefix + next);
        }

        if (!related(base, next)) return next;
        if (!candidateComplete && next.length() < Math.max(24, base.length() * 0.65)) return base;
        return next;
    }

    public static boolean completeThought(String value) {
        String text = clean(value);
        if (text.length() < 16) return false;
        List<Token> words = tokens(text);
        if (words.size() < 4) return false;
        char last = text.charAt(text.length() - 1);
        if (last == '—' || last == '–' || last == '-' || last == ':' || last == ';' || last == ',') return false;
        String lastWord = words.getLast().normalized();
        return !INCOMPLETE_ENDINGS.contains(lastWord);
    }

    private static int commonPrefix(List<Token> first, List<Token> second) {
        int common = 0;
        for (int i = 0; i < Math.min(first.size(), second.size()); i++) {
            if (!first.get(i).stem().equals(second.get(i).stem())) break;
            common++;
        }
        return common;
    }

    /** Finds a candidate-prefix sequence inside the existing recommendation. */
    private static Overlap prefixOverlap(List<Token> base, List<Token> candidate) {
        Overlap best = new Overlap(-1, 0);
        int candidateLimit = Math.min(candidate.size(), 8);
        for (int baseIndex = 0; baseIndex < base.size(); baseIndex++) {
            int length = 0;
            while (baseIndex + length < base.size() && length < candidateLimit
                    && base.get(baseIndex + length).stem().equals(candidate.get(length).stem())) {
                length++;
            }
            if (length > best.length()) best = new Overlap(baseIndex, length);
        }
        return best;
    }

    private static Set<String> contentStems(List<Token> values) {
        Set<String> result = new HashSet<>();
        for (Token token : values) {
            if (!STOP_WORDS.contains(token.normalized()) && token.normalized().length() > 2) result.add(token.stem());
        }
        return result;
    }

    private static List<Token> tokens(String value) {
        List<Token> result = new ArrayList<>();
        Matcher matcher = WORD.matcher(value == null ? "" : value);
        while (matcher.find()) {
            String normalized = matcher.group().toLowerCase(Locale.ROOT).replace('ё', 'е');
            String stem = normalized.length() <= 6 ? normalized : normalized.substring(0, 6);
            result.add(new Token(normalized, stem, matcher.start()));
        }
        return result;
    }

    private static String normalize(String value) {
        return clean(value).toLowerCase(Locale.ROOT).replace('ё', 'е')
                .replaceAll("[^\\p{L}\\p{N}]+", " ").trim().replaceAll("\\s+", " ");
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    private record Token(String normalized, String stem, int start) { }
    private record Overlap(int baseIndex, int length) { }
}
