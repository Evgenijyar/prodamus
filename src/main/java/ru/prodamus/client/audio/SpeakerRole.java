package ru.prodamus.client.audio;

public enum SpeakerRole {
    CUSTOMER("КЛИЕНТ"),
    MANAGER("МЕНЕДЖЕР");

    private final String label;

    SpeakerRole(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
