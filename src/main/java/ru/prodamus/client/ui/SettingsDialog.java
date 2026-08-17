package ru.prodamus.client.ui;

import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.stage.Modality;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.prodamus.client.audio.AudioDevice;
import ru.prodamus.client.audio.WindowsAudioService;
import ru.prodamus.client.config.AppSettings;

import java.util.List;
import java.util.Optional;

final class SettingsDialog {
    private static final Logger log = LoggerFactory.getLogger(SettingsDialog.class);
    private final Dialog<AppSettings> dialog = new Dialog<>();
    private final ComboBox<AudioDevice> microphone = new ComboBox<>();
    private final ComboBox<AudioDevice> loopback = new ComboBox<>();
    private final Spinner<Integer> threshold = new Spinner<>(100, 5000, 550, 50);
    private final Spinner<Integer> silence = new Spinner<>(300, 2500, 700, 100);
    private final CheckBox activeListening = new CheckBox("Реагировать, пока клиент продолжает говорить");
    private final Spinner<Integer> activeListeningInterval = new Spinner<>(1, 5, 2, 1);
    private final CheckBox dualSession = new CheckBox("Использовать прогноз и два AI-ключа");
    private final Slider opacity = new Slider(0.65, 1.0, 0.96);
    private final Label deviceStatus = new Label("Загрузка аудиоустройств…");
    private final AppSettings original;
    private final WindowsAudioService audioService;

    SettingsDialog(Window owner, AppSettings settings, WindowsAudioService audioService) {
        this.original = settings;
        this.audioService = audioService;
        dialog.initOwner(owner);
        dialog.initModality(Modality.WINDOW_MODAL);
        dialog.initStyle(StageStyle.DECORATED);
        dialog.setTitle("Prodamus Predictive 2 — локальные настройки");
        dialog.setHeaderText("Звук и отображение");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL,
                new ButtonType("Сохранить", ButtonBar.ButtonData.OK_DONE));
        dialog.getDialogPane().setPrefSize(700, 650);
        dialog.getDialogPane().setContent(buildContent());
        populate(settings);
        loadDevices();
        dialog.setResultConverter(button -> button.getButtonData() == ButtonBar.ButtonData.OK_DONE ? result() : null);
    }

    Optional<AppSettings> showAndWait() { return dialog.showAndWait(); }

    private Node buildContent() {
        GridPane grid = new GridPane();
        grid.setPadding(new Insets(18));
        grid.setHgap(14);
        grid.setVgap(14);
        grid.getColumnConstraints().add(new ColumnConstraints(190));
        ColumnConstraints grow = new ColumnConstraints();
        grow.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().add(grow);

        microphone.setMaxWidth(Double.MAX_VALUE);
        loopback.setMaxWidth(Double.MAX_VALUE);
        opacity.setShowTickLabels(true);
        opacity.setShowTickMarks(false);

        int row = 0;
        grid.add(new Label("Микрофон менеджера"), 0, row);
        grid.add(microphone, 1, row++);
        grid.add(new Label("Вывод / голос клиента"), 0, row);
        grid.add(loopback, 1, row++);
        grid.add(deviceStatus, 1, row++);
        grid.add(new Separator(), 0, row++, 2, 1);
        grid.add(new Label("Порог голоса (RMS)"), 0, row);
        grid.add(threshold, 1, row++);
        grid.add(new Label("Конец реплики, мс"), 0, row);
        grid.add(silence, 1, row++);
        grid.add(new Separator(), 0, row++, 2, 1);
        grid.add(new Label("Активное слушание"), 0, row);
        grid.add(activeListening, 1, row++);
        grid.add(new Label("Уточнение подсказки, сек"), 0, row);
        grid.add(activeListeningInterval, 1, row++);
        grid.add(new Separator(), 0, row++, 2, 1);
        grid.add(new Label("AI-схема"), 0, row);
        grid.add(dualSession, 1, row++);
        grid.add(new Label("Прозрачность окна"), 0, row);
        grid.add(opacity, 1, row++);

        activeListeningInterval.disableProperty().bind(activeListening.selectedProperty().not());
        Label note = new Label("В режиме двух ключей скрытый прогнозист заранее готовит три сценария, а рекомендатель показывает только одну лучшую фразу. В режиме одного ключа используется исходная совместимая схема Prodamus без прогнозиста.\n\nАктивное слушание даёт раннюю реакцию и уточняет её по мере продолжения длинной реплики. AI-ключи, модель, промпты и база знаний управляются централизованно на сервере.");
        note.setWrapText(true);
        note.getStyleClass().add("settings-note");
        grid.add(note, 0, row, 2, 1);
        return grid;
    }

    private void populate(AppSettings settings) {
        threshold.getValueFactory().setValue(settings.vadThreshold());
        silence.getValueFactory().setValue(settings.silenceMillis());
        activeListening.setSelected(settings.activeListening());
        activeListeningInterval.getValueFactory().setValue(settings.activeListeningIntervalSeconds());
        dualSession.setSelected(settings.dualSession());
        opacity.setValue(settings.overlayOpacity());
    }

    private void loadDevices() {
        Task<List<List<AudioDevice>>> task = new Task<>() {
            @Override protected List<List<AudioDevice>> call() {
                return List.of(audioService.listDevices(true), audioService.listDevices(false));
            }
        };
        task.setOnSucceeded(event -> {
            microphone.setItems(FXCollections.observableArrayList(task.getValue().get(0)));
            loopback.setItems(FXCollections.observableArrayList(task.getValue().get(1)));
            select(microphone, original.microphoneDeviceId());
            select(loopback, original.loopbackDeviceId());
            deviceStatus.setText("WASAPI loopback: системный звук захватывается напрямую");
        });
        task.setOnFailed(event -> {
            log.error("Audio device enumeration failed", task.getException());
            deviceStatus.setText("Ошибка устройств: " + task.getException().getMessage());
        });
        Thread.ofPlatform().name("audio-device-enumeration").daemon(true).start(task);
    }

    private void select(ComboBox<AudioDevice> box, String id) {
        String currentId = id == null ? "" : id;
        AudioDevice selected = box.getItems().stream()
                .filter(device -> !currentId.isBlank() && device.id().equalsIgnoreCase(currentId)).findFirst()
                .orElseGet(() -> box.getItems().stream().filter(AudioDevice::defaultDevice).findFirst()
                        .orElse(box.getItems().isEmpty() ? null : box.getItems().getFirst()));
        box.getSelectionModel().select(selected);
    }

    private AppSettings result() {
        AudioDevice mic = microphone.getValue();
        AudioDevice output = loopback.getValue();
        return new AppSettings(
                mic == null ? original.microphoneDeviceId() : mic.id(),
                output == null ? original.loopbackDeviceId() : output.id(),
                threshold.getValue(), silence.getValue(), original.excludeFromCapture(), opacity.getValue(),
                original.expandedPreferred(), activeListening.isSelected(), activeListeningInterval.getValue(),
                dualSession.isSelected(),
                original.lastRoleId());
    }
}
