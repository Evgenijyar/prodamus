package ru.prodamus.client.core;

public enum SuggestionKind {
    HYPOTHESIS("hypothesis"),
    FINAL("final");

    private final String cssClass;

    SuggestionKind(String cssClass) {
        this.cssClass = cssClass;
    }

    public String cssClass() { return cssClass; }
}
