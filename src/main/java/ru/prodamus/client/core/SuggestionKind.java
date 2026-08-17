package ru.prodamus.client.core;

public enum SuggestionKind {
    RECOMMENDATION("ПОДСКАЗКА", "recommendation");

    private final String label;
    private final String cssClass;

    SuggestionKind(String label, String cssClass) {
        this.label = label;
        this.cssClass = cssClass;
    }

    public String label() { return label; }
    public String cssClass() { return cssClass; }
}
