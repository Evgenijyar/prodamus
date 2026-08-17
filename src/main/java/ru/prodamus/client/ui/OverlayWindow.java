package ru.prodamus.client.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import ru.prodamus.client.audio.WindowsAudioService;
import ru.prodamus.client.config.AppSettings;
import ru.prodamus.client.config.SettingsService;
import ru.prodamus.client.core.AssistantCoordinator;
import ru.prodamus.client.core.AssistantListener;
import ru.prodamus.client.server.BackendClient;
import ru.prodamus.client.server.BackendClient.Bootstrap;
import ru.prodamus.client.server.BackendClient.Role;
import ru.prodamus.client.windows.WindowsPrivacyService;

import java.awt.Desktop;
import java.net.URI;
import java.util.List;
import java.util.concurrent.Executor;

@Component
public class OverlayWindow implements AssistantListener {
    private static final Logger log = LoggerFactory.getLogger(OverlayWindow.class);
    private static final String TITLE = "Prodamus — " + ProcessHandle.current().pid();
    private static final double WINDOW_W = 590;
    private static final double WINDOW_H = 430;
    private static final double SUGGESTION_HEIGHT = 128;

    private final AssistantCoordinator coordinator;
    private final BackendClient backend;
    private final SettingsService settingsService;
    private final WindowsAudioService audioService;
    private final WindowsPrivacyService privacyService;
    private final Executor executor;

    private final Label status = new Label("Подключение…");
    private final Label statusDot = new Label("●");
    private final Label suggestion = new Label("Готовлю рабочее пространство…");
    private final Label transcript = new Label("");
    private final Button startStop = new Button("▶  Старт");
    private final ComboBox<Role> roleBox = new ComboBox<>();
    private final TextArea clientContext = new TextArea();
    private final CheckBox captureCheck = new CheckBox("Не показывать в захвате");
    private final Label userLabel = new Label();
    private final HBox updateBanner = new HBox(10);
    private final Label updateText = new Label();
    private final Hyperlink updateLink = new Hyperlink("Скачать");

    private Stage stage;
    private AppSettings settings;
    private Bootstrap bootstrap;
    private boolean roleListenerInstalled;
    private double dragX;
    private double dragY;

    public OverlayWindow(AssistantCoordinator coordinator, BackendClient backend, SettingsService settingsService,
                         WindowsAudioService audioService, WindowsPrivacyService privacyService,
                         @Qualifier("assistantExecutor") Executor executor) {
        this.coordinator = coordinator;
        this.backend = backend;
        this.settingsService = settingsService;
        this.audioService = audioService;
        this.privacyService = privacyService;
        this.executor = executor;
    }

    public void show(Stage primaryStage) {
        this.stage = primaryStage;
        this.settings = settingsService.load();
        stage.initStyle(StageStyle.TRANSPARENT);
        stage.setAlwaysOnTop(true);
        stage.setTitle(TITLE);
        stage.setOnCloseRequest(event -> coordinator.stop());
        showLogin(true);
        stage.show();
        executor.execute(() -> {
            boolean restored = backend.restoreRememberedSession();
            if (restored) loadBootstrapAndOpen();
            else Platform.runLater(() -> showLogin(false));
        });
    }

    private void showLogin(boolean restoring) {
        if (coordinator.isRunning()) coordinator.stop();
        VBox root = new VBox(14);
        root.getStyleClass().add("login-root");
        root.setPadding(new Insets(28, 34, 30, 34));
        root.setAlignment(Pos.TOP_CENTER);

        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        Button minimize = iconButton("—", "Свернуть");
        minimize.setOnAction(e -> stage.setIconified(true));
        Button close = iconButton("×", "Закрыть");
        close.setOnAction(e -> { coordinator.stop(); stage.close(); Platform.exit(); });
        HBox loginHeader = new HBox(6, headerSpacer, minimize, close);
        loginHeader.setAlignment(Pos.CENTER_RIGHT);
        loginHeader.setMaxWidth(Double.MAX_VALUE);
        loginHeader.getStyleClass().add("login-header");
        enableDrag(loginHeader);

        ImageView logo = new ImageView(new Image(getClass().getResourceAsStream("/ui/prodamus-logo.png")));
        logo.setFitWidth(310);
        logo.setPreserveRatio(true);
        logo.setSmooth(true);

        Label eyebrow = new Label("WINDOWS CLIENT");
        eyebrow.getStyleClass().add("login-eyebrow");
        Label title = new Label("Вход в Prodamus");
        title.getStyleClass().add("login-title");
        Label subtitle = new Label("Войдите под учётной записью менеджера. AI-настройки и ключ загружаются с сервера перед началом разговора.");
        subtitle.setWrapText(true);
        subtitle.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        subtitle.getStyleClass().add("login-subtitle");

        TextField login = new TextField(settingsService.lastLogin());
        login.setPromptText("Логин");
        login.getStyleClass().add("login-input");
        PasswordField password = new PasswordField();
        password.setPromptText("Пароль");
        password.getStyleClass().add("login-input");
        CheckBox remember = new CheckBox("Запомнить меня на этом компьютере");
        remember.setSelected(true);
        remember.getStyleClass().add("remember-check");
        Label error = new Label(restoring ? "Проверяю сохранённую сессию…" : "");
        error.setWrapText(true);
        error.getStyleClass().add(restoring ? "login-status" : "login-error");
        error.setVisible(restoring);
        error.setManaged(restoring);

        Button loginButton = new Button(restoring ? "Подключение…" : "Войти");
        loginButton.getStyleClass().add("login-button");
        loginButton.setMaxWidth(Double.MAX_VALUE);
        loginButton.setDisable(restoring);
        login.setDisable(restoring);
        password.setDisable(restoring);
        remember.setDisable(restoring);

        Runnable submit = () -> {
            String loginValue = login.getText().trim();
            String passwordValue = password.getText();
            boolean rememberValue = remember.isSelected();
            if (loginValue.isBlank() || passwordValue.isBlank()) {
                showLoginError(error, "Введите логин и пароль.");
                return;
            }
            loginButton.setDisable(true);
            loginButton.setText("Входим…");
            login.setDisable(true);
            password.setDisable(true);
            remember.setDisable(true);
            error.setManaged(false);
            error.setVisible(false);
            executor.execute(() -> {
                try {
                    backend.login(loginValue, passwordValue, rememberValue);
                    loadBootstrapAndOpen();
                } catch (RuntimeException ex) {
                    Platform.runLater(() -> {
                        showLoginError(error, ex.getMessage());
                        loginButton.setDisable(false);
                        loginButton.setText("Войти");
                        login.setDisable(false);
                        password.setDisable(false);
                        remember.setDisable(false);
                        password.clear();
                        password.requestFocus();
                    });
                }
            });
        };
        loginButton.setOnAction(e -> submit.run());
        password.setOnAction(e -> submit.run());

        Label server = new Label("prodamus.abs7.ru  •  client " + backend.clientVersion());
        server.getStyleClass().add("login-server");
        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);
        root.getChildren().addAll(loginHeader, logo, eyebrow, title, subtitle, login, password,
                remember, error, loginButton, spacer, server);

        stage.setScene(scene(root, 470, 610));
        stage.setResizable(false);
        stage.setOpacity(1.0);
        centerStage(470, 610);
        Platform.runLater(() -> { if (!restoring) (login.getText().isBlank() ? login : password).requestFocus(); });
    }

    private void showLoginError(Label label, String message) {
        label.getStyleClass().remove("login-status");
        if (!label.getStyleClass().contains("login-error")) label.getStyleClass().add("login-error");
        label.setText(message == null || message.isBlank() ? "Не удалось войти." : message);
        label.setManaged(true);
        label.setVisible(true);
    }

    private void loadBootstrapAndOpen() {
        try {
            Bootstrap loaded = backend.bootstrap();
            Platform.runLater(() -> openWorkspace(loaded));
        } catch (RuntimeException ex) {
            log.warn("Bootstrap failed", ex);
            Platform.runLater(() -> {
                showLogin(false);
                new Alert(Alert.AlertType.ERROR,
                        ex.getMessage() == null ? "Не удалось загрузить настройки Prodamus." : ex.getMessage(),
                        ButtonType.OK).showAndWait();
            });
        }
    }

    private void openWorkspace(Bootstrap bootstrap) {
        this.bootstrap = bootstrap;
        this.settings = settingsService.load();
        this.transcript.setText("");
        stage.setScene(scene(buildWorkspace(), WINDOW_W, WINDOW_H));
        stage.setResizable(false);
        stage.setOpacity(settings.overlayOpacity());
        configureWorkspaceData();
        stage.setWidth(WINDOW_W);
        stage.setHeight(WINDOW_H);
        var bounds = Screen.getPrimary().getVisualBounds();
        stage.setX(bounds.getMaxX() - WINDOW_W - 30);
        stage.setY(bounds.getMinY() + 30);
        Platform.runLater(this::applyCaptureProtection);
    }

    private Region buildWorkspace() {
        statusDot.getStyleClass().setAll("status-dot");
        status.getStyleClass().setAll("status");
        userLabel.getStyleClass().setAll("user-label");

        Label product = new Label("PRODAMUS");
        product.getStyleClass().add("product-wordmark");
        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        Button settingsButton = iconButton("⚙", "Локальные настройки");
        settingsButton.setOnAction(e -> openSettings());
        Button logout = iconButton("⇥", "Выйти из аккаунта");
        logout.setOnAction(e -> logout());
        Button minimize = iconButton("—", "Свернуть");
        minimize.setOnAction(e -> stage.setIconified(true));
        Button close = iconButton("×", "Закрыть");
        close.setOnAction(e -> { coordinator.stop(); stage.close(); Platform.exit(); });

        captureCheck.setSelected(settings.excludeFromCapture());
        captureCheck.getStyleClass().setAll("capture-check");
        captureCheck.setOnAction(e -> {
            settings = new AppSettings(settings.microphoneDeviceId(), settings.loopbackDeviceId(), settings.vadThreshold(),
                    settings.silenceMillis(), captureCheck.isSelected(), settings.overlayOpacity(), false,
                    settings.lastRoleId());
            settingsService.save(settings);
            applyCaptureProtection();
        });

        HBox header = new HBox(8, statusDot, product, new Separator(javafx.geometry.Orientation.VERTICAL),
                status, headerSpacer, captureCheck, userLabel, settingsButton, logout, minimize, close);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("header");
        enableDrag(header);

        roleBox.setMaxWidth(Double.MAX_VALUE);
        roleBox.getStyleClass().setAll("role-box");
        if (!roleListenerInstalled) {
            roleBox.valueProperty().addListener((obs, old, value) -> {
                if (value == null || settings == null) return;
                roleBox.setTooltip(value.description() == null || value.description().isBlank()
                        ? null : new Tooltip(value.description()));
                settings = new AppSettings(settings.microphoneDeviceId(), settings.loopbackDeviceId(),
                        settings.vadThreshold(), settings.silenceMillis(), settings.excludeFromCapture(),
                        settings.overlayOpacity(), false, value.id());
                settingsService.save(settings);
            });
            roleListenerInstalled = true;
        }

        clientContext.setPromptText("Контекст клиента перед звонком…");
        clientContext.setWrapText(true);
        clientContext.setPrefRowCount(2);
        clientContext.setMaxHeight(58);
        clientContext.getStyleClass().setAll("client-context");

        suggestion.setWrapText(true);
        suggestion.setMaxWidth(Double.MAX_VALUE);
        suggestion.setMinHeight(SUGGESTION_HEIGHT);
        suggestion.setPrefHeight(SUGGESTION_HEIGHT);
        suggestion.setMaxHeight(SUGGESTION_HEIGHT);
        suggestion.setAlignment(Pos.TOP_LEFT);
        suggestion.getStyleClass().setAll("suggestion");

        transcript.setWrapText(true);
        transcript.setMaxWidth(Double.MAX_VALUE);
        transcript.setMinHeight(26);
        transcript.getStyleClass().setAll("transcript");

        updateBanner.getStyleClass().setAll("update-banner");
        updateText.setWrapText(true);
        HBox.setHgrow(updateText, Priority.ALWAYS);
        updateLink.setOnAction(e -> openUpdateUrl());
        updateBanner.getChildren().setAll(updateText, updateLink);
        updateBanner.setVisible(false);
        updateBanner.setManaged(false);

        startStop.getStyleClass().setAll("start-button");
        startStop.setOnAction(e -> toggleRunning());
        Button clear = new Button("Очистить");
        clear.getStyleClass().add("secondary-button");
        clear.setOnAction(e -> {
            suggestion.setText(coordinator.isRunning() ? "Слушаю разговор…" : "Готов к новому разговору.");
            transcript.setText("");
        });
        Region footerSpacer = new Region();
        HBox.setHgrow(footerSpacer, Priority.ALWAYS);
        Label hint = new Label("Окно можно перетаскивать за верхнюю панель");
        hint.getStyleClass().add("hint");
        HBox controls = new HBox(9, startStop, clear, footerSpacer, hint);
        controls.setAlignment(Pos.CENTER_LEFT);
        controls.getStyleClass().add("controls-row");

        VBox root = new VBox(9, header, updateBanner, roleBox, clientContext, suggestion, transcript, controls);
        root.setPadding(new Insets(10));
        root.getStyleClass().add("overlay");
        return root;
    }

    private void configureWorkspaceData() {
        userLabel.setText(bootstrap.user().displayName() == null || bootstrap.user().displayName().isBlank()
                ? bootstrap.user().login() : bootstrap.user().displayName());
        List<Role> roles = bootstrap.roles() == null ? List.of() : bootstrap.roles();
        roleBox.getItems().setAll(roles);
        Role selected = roles.stream().filter(r -> r.id() == settings.lastRoleId()).findFirst()
                .orElse(roles.isEmpty() ? null : roles.getFirst());
        roleBox.getSelectionModel().select(selected);

        boolean manualContext = bootstrap.features().manualClientContext();
        clientContext.setVisible(manualContext);
        clientContext.setManaged(manualContext);

        updateBanner.getStyleClass().remove("required");
        if (bootstrap.version().updateRequired()) {
            updateText.setText("Требуется версия Prodamus " + bootstrap.version().latest() + ".");
            updateBanner.getStyleClass().add("required");
            showUpdateBanner();
        } else if (bootstrap.version().updateAvailable()) {
            updateText.setText("Доступна новая версия Prodamus: " + bootstrap.version().latest());
            showUpdateBanner();
        }
        boolean hasDownload = bootstrap.version().downloadUrl() != null && !bootstrap.version().downloadUrl().isBlank();
        updateLink.setVisible(hasDownload);
        updateLink.setManaged(hasDownload);

        if (roles.isEmpty()) {
            suggestion.setText("Администратор ещё не назначил вам ни одной роли продаж.");
            startStop.setDisable(true);
        } else {
            suggestion.setText("Выберите роль и нажмите «Старт».");
            startStop.setDisable(bootstrap.version().updateRequired());
        }
        setStatus("Готов");
    }

    private void showUpdateBanner() {
        updateBanner.setVisible(true);
        updateBanner.setManaged(true);
    }

    private void toggleRunning() {
        if (coordinator.isRunning()) {
            coordinator.stop();
            return;
        }
        Role role = roleBox.getValue();
        if (role == null) {
            setStatus("Выберите роль");
            return;
        }
        if (bootstrap.version().updateRequired()) {
            openUpdateUrl();
            return;
        }
        transcript.setText("");
        coordinator.start(settings, role.id(), clientContext.getText(), this);
    }

    private void openSettings() {
        if (coordinator.isRunning()) {
            new Alert(Alert.AlertType.INFORMATION,
                    "Остановите текущий разговор перед изменением аудиоустройств.", ButtonType.OK).showAndWait();
            return;
        }
        new SettingsDialog(stage, settings, audioService).showAndWait().ifPresent(updated -> {
            settings = new AppSettings(updated.microphoneDeviceId(), updated.loopbackDeviceId(),
                    updated.vadThreshold(), updated.silenceMillis(), updated.excludeFromCapture(),
                    updated.overlayOpacity(), false, updated.lastRoleId());
            settingsService.save(settings);
            stage.setOpacity(settings.overlayOpacity());
            captureCheck.setSelected(settings.excludeFromCapture());
            applyCaptureProtection();
            setStatus("Настройки сохранены");
        });
    }

    private void logout() {
        if (coordinator.isRunning()) coordinator.stop();
        setStatus("Выход…");
        executor.execute(() -> {
            backend.logout();
            Platform.runLater(() -> showLogin(false));
        });
    }

    private void openUpdateUrl() {
        if (bootstrap == null || bootstrap.version().downloadUrl() == null
                || bootstrap.version().downloadUrl().isBlank()) return;
        try {
            if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI.create(bootstrap.version().downloadUrl()));
        } catch (Exception ex) {
            new Alert(Alert.AlertType.ERROR,
                    "Не удалось открыть ссылку обновления: " + ex.getMessage(), ButtonType.OK).showAndWait();
        }
    }

    private Button iconButton(String text, String tooltip) {
        Button button = new Button(text);
        button.getStyleClass().add("window-button");
        button.setTooltip(new Tooltip(tooltip));
        return button;
    }

    private void enableDrag(Region region) {
        region.addEventHandler(MouseEvent.MOUSE_PRESSED, event -> {
            dragX = event.getSceneX();
            dragY = event.getSceneY();
        });
        region.addEventHandler(MouseEvent.MOUSE_DRAGGED, event -> {
            stage.setX(event.getScreenX() - dragX);
            stage.setY(event.getScreenY() - dragY);
        });
    }

    private void applyCaptureProtection() {
        boolean applied = privacyService.setExcluded(TITLE, captureCheck.isSelected());
        if (captureCheck.isSelected() && !applied) setStatus("Защита захвата недоступна");
    }

    private Scene scene(Region root, double width, double height) {
        Scene scene = new Scene(root, width, height, Color.TRANSPARENT);
        scene.getStylesheets().add(getClass().getResource("/ui/overlay.css").toExternalForm());
        return scene;
    }

    private void centerStage(double width, double height) {
        var bounds = Screen.getPrimary().getVisualBounds();
        stage.setWidth(width);
        stage.setHeight(height);
        stage.setX(bounds.getMinX() + (bounds.getWidth() - width) / 2);
        stage.setY(bounds.getMinY() + (bounds.getHeight() - height) / 2);
    }

    @Override
    public void onRunningChanged(boolean running) {
        Platform.runLater(() -> {
            startStop.setText(running ? "■  Стоп" : "▶  Старт");
            statusDot.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("active"), running);
            roleBox.setDisable(running);
            clientContext.setDisable(running);
            if (running) suggestion.setText("Слушаю разговор…");
            else if (bootstrap != null) {
                startStop.setDisable(bootstrap.roles().isEmpty() || bootstrap.version().updateRequired());
            }
        });
    }

    @Override public void onStatus(String value) { Platform.runLater(() -> setStatus(value)); }

    @Override
    public void onSuggestion(String text, boolean complete) {
        Platform.runLater(() -> suggestion.setText(
                text == null || text.isBlank() ? "Слушаю разговор…" : text));
    }

    @Override
    public void onTranscript(String text) {
        if (text == null || text.isBlank()) return;
        Platform.runLater(() -> transcript.setText("Речь: " + text));
    }

    @Override
    public void onError(String message) {
        Platform.runLater(() -> {
            setStatus("Ошибка");
            suggestion.setText(message == null ? "Неизвестная ошибка" : message);
        });
    }

    private void setStatus(String value) {
        status.setText(value == null ? "" : value);
    }
}
