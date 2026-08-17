package ru.prodamus.client.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
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
import ru.prodamus.client.audio.SpeakerRole;
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
    private static final double COMPACT_W = 590;
    private static final double COMPACT_H = 360;
    private static final double EXPANDED_W = 840;
    private static final double EXPANDED_H = 720;

    private final AssistantCoordinator coordinator;
    private final BackendClient backend;
    private final SettingsService settingsService;
    private final WindowsAudioService audioService;
    private final WindowsPrivacyService privacyService;
    private final Executor executor;

    private final Label status = new Label("Подключение…");
    private final Label statusDot = new Label("●");
    private final Label suggestion = new Label("Готовлю рабочее пространство…");
    private final Button startStop = new Button("▶  Старт");
    private final ComboBox<Role> roleBox = new ComboBox<>();
    private final TextArea clientContext = new TextArea();
    private final CheckBox captureCheck = new CheckBox("Не показывать в захвате экрана");
    private final VBox historyBox = new VBox(8);
    private final ScrollPane historyScroll = new ScrollPane(historyBox);
    private final Button jumpLatest = new Button("↓  К последнему");
    private final Label userLabel = new Label();
    private final Label roleDescription = new Label();
    private final HBox updateBanner = new HBox(10);
    private final Label updateText = new Label();
    private final Hyperlink updateLink = new Hyperlink("Скачать");

    private Stage stage;
    private AppSettings settings;
    private Bootstrap bootstrap;
    private boolean expanded;
    private boolean autoFollow = true;
    private double dragX;
    private double dragY;
    private boolean workspaceListenersInitialized;
    private double expandedWidth = EXPANDED_W;
    private double expandedHeight = EXPANDED_H;
    private ResizeEdge activeResizeEdge = ResizeEdge.NONE;
    private double resizeStartScreenX;
    private double resizeStartScreenY;
    private double resizeStartX;
    private double resizeStartY;
    private double resizeStartWidth;
    private double resizeStartHeight;
    private Label liveCustomerMessage;
    private Label liveAssistantMessage;

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

        Region loginHeaderSpacer = new Region();
        HBox.setHgrow(loginHeaderSpacer, Priority.ALWAYS);
        Button loginMinimize = iconButton("—", "Свернуть");
        loginMinimize.setOnAction(e -> stage.setIconified(true));
        Button loginClose = iconButton("×", "Закрыть");
        loginClose.setOnAction(e -> { coordinator.stop(); stage.close(); Platform.exit(); });
        HBox loginHeader = new HBox(6, loginHeaderSpacer, loginMinimize, loginClose);
        loginHeader.setAlignment(Pos.CENTER_RIGHT);
        loginHeader.setMaxWidth(Double.MAX_VALUE);
        loginHeader.getStyleClass().add("login-header");
        enableDrag(loginHeader);

        ImageView logo = new ImageView(new Image(getClass().getResourceAsStream("/ui/prodamus-logo.png")));
        logo.setFitWidth(310);
        logo.setPreserveRatio(true);
        logo.setSmooth(true);
        logo.getStyleClass().add("login-logo");

        Label eyebrow = new Label("WINDOWS CLIENT");
        eyebrow.getStyleClass().add("login-eyebrow");
        Label title = new Label("Вход в Prodamus");
        title.getStyleClass().add("login-title");
        Label subtitle = new Label("Войдите под учётной записью менеджера. AI-настройки и ключи загружаются с сервера автоматически.");
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
        root.getChildren().addAll(loginHeader, logo, eyebrow, title, subtitle, login, password, remember, error, loginButton, spacer, server);

        Scene scene = scene(root, 470, 610);
        stage.setScene(scene);
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
            Platform.runLater(() -> showLoginWithFatal(ex.getMessage()));
        }
    }

    private void showLoginWithFatal(String message) {
        showLogin(false);
        new Alert(Alert.AlertType.ERROR, message == null ? "Не удалось загрузить настройки Prodamus." : message, ButtonType.OK).showAndWait();
    }

    private void openWorkspace(Bootstrap bootstrap) {
        this.bootstrap = bootstrap;
        this.settings = settingsService.load();
        resetWorkspaceState();
        this.expanded = settings.expandedPreferred() && bootstrap.features().expandedMode();
        Scene scene = scene(buildWorkspace(), expanded ? EXPANDED_W : COMPACT_W, expanded ? EXPANDED_H : COMPACT_H);
        stage.setScene(scene);
        stage.setOpacity(settings.overlayOpacity());
        configureWorkspaceData();
        resizeForMode(false);
        Platform.runLater(this::applyCaptureProtection);
    }

    private Region buildWorkspace() {
        statusDot.getStyleClass().add("status-dot");
        status.getStyleClass().add("status");
        userLabel.getStyleClass().add("user-label");
        roleDescription.getStyleClass().add("role-description");
        roleDescription.setWrapText(true);

        Label product = new Label("PRODAMUS");
        product.getStyleClass().add("product-wordmark");
        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        Button expand = iconButton("↗", "Развернуть / свернуть");
        expand.setId("expand-button");
        expand.setOnAction(e -> toggleExpanded());
        Button settingsButton = iconButton("⚙", "Локальные настройки");
        settingsButton.setOnAction(e -> openSettings());
        Button logout = iconButton("⇥", "Выйти из аккаунта");
        logout.setOnAction(e -> logout());
        Button minimize = iconButton("—", "Свернуть");
        minimize.setOnAction(e -> stage.setIconified(true));
        Button close = iconButton("×", "Закрыть");
        close.setOnAction(e -> { coordinator.stop(); stage.close(); Platform.exit(); });

        captureCheck.setSelected(settings.excludeFromCapture());
        captureCheck.getStyleClass().add("capture-check");
        captureCheck.setText("Не показывать в захвате");
        captureCheck.setOnAction(e -> {
            settings = new AppSettings(settings.microphoneDeviceId(), settings.loopbackDeviceId(), settings.vadThreshold(),
                    settings.silenceMillis(), captureCheck.isSelected(), settings.overlayOpacity(),
                    settings.expandedPreferred(), settings.lastRoleId());
            settingsService.save(settings);
            applyCaptureProtection();
        });

        HBox header = new HBox(8, statusDot, product, new Separator(javafx.geometry.Orientation.VERTICAL),
                status, headerSpacer, captureCheck, userLabel, expand, settingsButton, logout, minimize, close);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("header");
        enableDrag(header);

        roleBox.setMaxWidth(Double.MAX_VALUE);
        roleBox.getStyleClass().add("role-box");
        if (!workspaceListenersInitialized) {
            roleBox.valueProperty().addListener((obs, old, value) -> {
                if (value == null || settings == null) return;
                roleDescription.setText(value.description() == null ? "" : value.description());
                settings = new AppSettings(settings.microphoneDeviceId(), settings.loopbackDeviceId(), settings.vadThreshold(),
                        settings.silenceMillis(), settings.excludeFromCapture(), settings.overlayOpacity(),
                        settings.expandedPreferred(), value.id());
                settingsService.save(settings);
            });
        }

        clientContext.setPromptText("Контекст клиента перед звонком: компания, задача, что уже обсуждали…");
        clientContext.setWrapText(true);
        clientContext.setPrefRowCount(2);
        clientContext.getStyleClass().add("client-context");

        startStop.getStyleClass().add("start-button");
        startStop.setOnAction(e -> toggleRunning());
        Button clear = new Button("Очистить");
        clear.getStyleClass().add("secondary-button");
        clear.setOnAction(e -> clearConversation());
        HBox controls = new HBox(9, startStop, clear);
        controls.setAlignment(Pos.CENTER_LEFT);
        controls.getStyleClass().add("controls-row");
        controls.setId("bottom-controls");

        VBox roleArea = new VBox(5, roleBox, roleDescription);
        roleArea.getStyleClass().add("role-area");

        suggestion.setWrapText(true);
        suggestion.setMaxWidth(Double.MAX_VALUE);
        suggestion.getStyleClass().add("suggestion");

        updateBanner.getStyleClass().add("update-banner");
        updateText.setWrapText(true);
        HBox.setHgrow(updateText, Priority.ALWAYS);
        updateLink.setOnAction(e -> openUpdateUrl());
        updateBanner.getChildren().setAll(updateText, updateLink);
        updateBanner.setVisible(false);
        updateBanner.setManaged(false);

        VBox compactContent = new VBox(9, updateBanner, roleArea, clientContext, suggestion);
        compactContent.setId("compact-content");

        historyBox.getStyleClass().add("history-box");
        historyScroll.setFitToWidth(true);
        historyScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        historyScroll.getStyleClass().add("history-scroll");
        if (!workspaceListenersInitialized) {
            historyScroll.vvalueProperty().addListener((obs, old, value) -> {
                autoFollow = value.doubleValue() >= 0.985;
                jumpLatest.setVisible(!autoFollow);
                jumpLatest.setManaged(!autoFollow);
            });
            workspaceListenersInitialized = true;
        }
        jumpLatest.getStyleClass().add("jump-latest");
        jumpLatest.setVisible(false);
        jumpLatest.setManaged(false);
        jumpLatest.setOnAction(e -> { autoFollow = true; historyScroll.setVvalue(1.0); });
        StackPane historyStack = new StackPane(historyScroll, jumpLatest);
        StackPane.setAlignment(jumpLatest, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(jumpLatest, new Insets(0, 15, 15, 0));
        historyStack.setId("history-stack");
        VBox.setVgrow(historyStack, Priority.ALWAYS);

        VBox root = new VBox(10, header, compactContent, historyStack, controls);
        root.setPadding(new Insets(10));
        root.getStyleClass().add("overlay");
        installResizeSupport(root);
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
        if (selected != null) roleDescription.setText(selected.description() == null ? "" : selected.description());

        boolean manual = bootstrap.features().manualClientContext();
        clientContext.setVisible(manual);
        clientContext.setManaged(manual);
        Node expandNode = stage.getScene().lookup("#expand-button");
        if (expandNode != null) {
            expandNode.setVisible(bootstrap.features().expandedMode());
            expandNode.setManaged(bootstrap.features().expandedMode());
        }

        updateBanner.getStyleClass().remove("required");
        updateBanner.setVisible(false);
        updateBanner.setManaged(false);
        if (bootstrap.version().updateRequired()) {
            updateText.setText("Эта версия Prodamus больше не поддерживается. Требуется версия " + bootstrap.version().latest() + ".");
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
            suggestion.setText("Выберите роль и нажмите «Старт». Prodamus будет давать подсказки по ходу разговора.");
            startStop.setDisable(bootstrap.version().updateRequired());
        }
        setStatus("Готов");
        applyModeVisibility();
    }

    private void showUpdateBanner() {
        updateBanner.setManaged(true);
        updateBanner.setVisible(true);
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
        coordinator.start(settings, role.id(), clientContext.getText(), this);
    }

    private void openSettings() {
        if (coordinator.isRunning()) {
            new Alert(Alert.AlertType.INFORMATION,
                    "Остановите текущий разговор перед изменением аудиоустройств.", ButtonType.OK).showAndWait();
            return;
        }
        new SettingsDialog(stage, settings, audioService).showAndWait().ifPresent(updated -> {
            settings = updated;
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

    private void toggleExpanded() {
        if (bootstrap == null || !bootstrap.features().expandedMode()) return;
        if (expanded) {
            expandedWidth = Math.max(stage.getWidth(), 680);
            expandedHeight = Math.max(stage.getHeight(), 540);
        }
        expanded = !expanded;
        settings = new AppSettings(settings.microphoneDeviceId(), settings.loopbackDeviceId(), settings.vadThreshold(),
                settings.silenceMillis(), settings.excludeFromCapture(), settings.overlayOpacity(), expanded,
                settings.lastRoleId());
        settingsService.save(settings);
        resizeForMode(true);
    }

    private void resizeForMode(boolean animateIgnored) {
        stage.setResizable(expanded);
        if (expanded) {
            stage.setMinWidth(680);
            stage.setMinHeight(540);
            stage.setWidth(Math.max(680, expandedWidth));
            stage.setHeight(Math.max(540, expandedHeight));
        } else {
            stage.setMinWidth(COMPACT_W);
            stage.setMinHeight(COMPACT_H);
            stage.setWidth(COMPACT_W);
            stage.setHeight(COMPACT_H);
        }
        applyModeVisibility();
    }

    private void applyModeVisibility() {
        if (stage.getScene() == null) return;
        Node history = stage.getScene().lookup("#history-stack");
        if (history != null) {
            history.setVisible(expanded);
            history.setManaged(expanded);
        }
        suggestion.setVisible(!expanded);
        suggestion.setManaged(!expanded);
        if (!expanded) stage.getScene().setCursor(Cursor.DEFAULT);
    }

    private void resetWorkspaceState() {
        roleBox.getItems().clear();
        roleBox.getSelectionModel().clearSelection();
        roleDescription.setText("");
        clientContext.clear();
        suggestion.setText("Готовлю рабочее пространство…");
        historyBox.getChildren().clear();
        liveCustomerMessage = null;
        liveAssistantMessage = null;
        autoFollow = true;
        jumpLatest.setVisible(false);
        jumpLatest.setManaged(false);
        updateBanner.getStyleClass().remove("required");
        updateBanner.setVisible(false);
        updateBanner.setManaged(false);
        startStop.setText("▶  Старт");
        startStop.setDisable(false);
    }

    private void clearConversation() {
        suggestion.setText(coordinator.isRunning() ? "Слушаю разговор…" : "Готов к новому разговору.");
        historyBox.getChildren().clear();
        liveCustomerMessage = null;
        liveAssistantMessage = null;
        autoFollow = true;
        jumpLatest.setVisible(false);
        jumpLatest.setManaged(false);
    }

    private void addHistory(String kind, String title, String text) {
        if (text == null || text.isBlank()) return;
        addHistoryCard(kind, title, text);
    }

    private Label addHistoryCard(String kind, String title, String text) {
        Label head = new Label(title);
        head.getStyleClass().addAll("history-head", "history-head-" + kind);
        Label body = new Label(text.trim());
        body.setWrapText(true);
        body.setMaxWidth(Double.MAX_VALUE);
        body.getStyleClass().add("history-body");

        VBox card = new VBox(5, head, body);
        card.getStyleClass().addAll("history-card", "history-card-" + kind);
        double ratio = "error".equals(kind) ? 0.90 : 0.67;
        card.prefWidthProperty().bind(historyBox.widthProperty().multiply(ratio));
        card.maxWidthProperty().bind(historyBox.widthProperty().multiply(ratio));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox row = new HBox();
        row.setMaxWidth(Double.MAX_VALUE);
        row.getStyleClass().addAll("history-row", "history-row-" + kind);
        if ("assistant".equals(kind)) {
            row.getChildren().addAll(spacer, card);
            row.setAlignment(Pos.CENTER_RIGHT);
        } else {
            row.getChildren().addAll(card, spacer);
            row.setAlignment(Pos.CENTER_LEFT);
        }
        historyBox.getChildren().add(row);
        followHistory();
        return body;
    }

    private void updateLiveHistory(String kind, String title, String text, boolean complete) {
        if (text == null || text.isBlank()) return;
        Label body = "customer".equals(kind) ? liveCustomerMessage : liveAssistantMessage;
        if (body == null) {
            body = addHistoryCard(kind, title, text);
            if ("customer".equals(kind)) liveCustomerMessage = body;
            else liveAssistantMessage = body;
        } else {
            body.setText(text.trim());
            followHistory();
        }
        if (complete) {
            if ("customer".equals(kind)) liveCustomerMessage = null;
            else liveAssistantMessage = null;
        }
    }

    private void followHistory() {
        if (!autoFollow) return;
        Platform.runLater(() -> historyScroll.setVvalue(1.0));
    }

    private void installResizeSupport(Region root) {
        final double edge = 9.0;

        root.addEventFilter(MouseEvent.MOUSE_MOVED, event -> {
            if (!expanded || activeResizeEdge != ResizeEdge.NONE) return;
            ResizeEdge detected = detectResizeEdge(event.getSceneX(), event.getSceneY(), edge);
            stage.getScene().setCursor(cursorFor(detected));
        });

        root.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            if (!expanded || event.getButton() != javafx.scene.input.MouseButton.PRIMARY) return;
            ResizeEdge detected = detectResizeEdge(event.getSceneX(), event.getSceneY(), edge);
            if (detected == ResizeEdge.NONE) return;
            activeResizeEdge = detected;
            resizeStartScreenX = event.getScreenX();
            resizeStartScreenY = event.getScreenY();
            resizeStartX = stage.getX();
            resizeStartY = stage.getY();
            resizeStartWidth = stage.getWidth();
            resizeStartHeight = stage.getHeight();
            event.consume();
        });

        root.addEventFilter(MouseEvent.MOUSE_DRAGGED, event -> {
            if (!expanded || activeResizeEdge == ResizeEdge.NONE) return;
            double dx = event.getScreenX() - resizeStartScreenX;
            double dy = event.getScreenY() - resizeStartScreenY;
            double minW = Math.max(680, stage.getMinWidth());
            double minH = Math.max(540, stage.getMinHeight());

            if (activeResizeEdge.right) {
                stage.setWidth(Math.max(minW, resizeStartWidth + dx));
            }
            if (activeResizeEdge.bottom) {
                stage.setHeight(Math.max(minH, resizeStartHeight + dy));
            }
            if (activeResizeEdge.left) {
                double newWidth = Math.max(minW, resizeStartWidth - dx);
                stage.setX(resizeStartX + (resizeStartWidth - newWidth));
                stage.setWidth(newWidth);
            }
            if (activeResizeEdge.top) {
                double newHeight = Math.max(minH, resizeStartHeight - dy);
                stage.setY(resizeStartY + (resizeStartHeight - newHeight));
                stage.setHeight(newHeight);
            }
            expandedWidth = stage.getWidth();
            expandedHeight = stage.getHeight();
            event.consume();
        });

        root.addEventFilter(MouseEvent.MOUSE_RELEASED, event -> {
            if (activeResizeEdge == ResizeEdge.NONE) return;
            activeResizeEdge = ResizeEdge.NONE;
            if (stage.getScene() != null) stage.getScene().setCursor(Cursor.DEFAULT);
            event.consume();
        });
    }

    private ResizeEdge detectResizeEdge(double x, double y, double edge) {
        if (!expanded || stage == null) return ResizeEdge.NONE;
        boolean left = x <= edge;
        boolean right = x >= stage.getWidth() - edge;
        boolean top = y <= edge;
        boolean bottom = y >= stage.getHeight() - edge;
        if (left && top) return ResizeEdge.NW;
        if (right && top) return ResizeEdge.NE;
        if (left && bottom) return ResizeEdge.SW;
        if (right && bottom) return ResizeEdge.SE;
        if (left) return ResizeEdge.W;
        if (right) return ResizeEdge.E;
        if (top) return ResizeEdge.N;
        if (bottom) return ResizeEdge.S;
        return ResizeEdge.NONE;
    }

    private Cursor cursorFor(ResizeEdge edge) {
        return switch (edge) {
            case N -> Cursor.N_RESIZE;
            case S -> Cursor.S_RESIZE;
            case E -> Cursor.E_RESIZE;
            case W -> Cursor.W_RESIZE;
            case NE -> Cursor.NE_RESIZE;
            case NW -> Cursor.NW_RESIZE;
            case SE -> Cursor.SE_RESIZE;
            case SW -> Cursor.SW_RESIZE;
            default -> Cursor.DEFAULT;
        };
    }

    private enum ResizeEdge {
        NONE(false, false, false, false),
        N(false, false, true, false),
        S(false, false, false, true),
        E(false, true, false, false),
        W(true, false, false, false),
        NE(false, true, true, false),
        NW(true, false, true, false),
        SE(false, true, false, true),
        SW(true, false, false, true);

        private final boolean left;
        private final boolean right;
        private final boolean top;
        private final boolean bottom;

        ResizeEdge(boolean left, boolean right, boolean top, boolean bottom) {
            this.left = left;
            this.right = right;
            this.top = top;
            this.bottom = bottom;
        }
    }

    private void openUpdateUrl() {
        if (bootstrap == null || bootstrap.version().downloadUrl() == null || bootstrap.version().downloadUrl().isBlank()) return;
        try {
            if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI.create(bootstrap.version().downloadUrl()));
        } catch (Exception ex) {
            new Alert(Alert.AlertType.ERROR, "Не удалось открыть ссылку обновления: " + ex.getMessage(), ButtonType.OK).showAndWait();
        }
    }

    private Button iconButton(String text, String tooltip) {
        Button button = new Button(text);
        button.getStyleClass().add("window-button");
        button.setTooltip(new Tooltip(tooltip));
        return button;
    }

    private void enableDrag(Region region) {
        region.addEventHandler(MouseEvent.MOUSE_PRESSED, event -> { dragX = event.getSceneX(); dragY = event.getSceneY(); });
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
        stage.setWidth(width); stage.setHeight(height);
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
            else if (bootstrap != null) startStop.setDisable(bootstrap.roles().isEmpty() || bootstrap.version().updateRequired());
        });
    }

    @Override public void onStatus(String value) { Platform.runLater(() -> setStatus(value)); }

    @Override
    public void onSuggestion(String text, boolean complete) {
        Platform.runLater(() -> {
            suggestion.setText(text == null || text.isBlank() ? "Слушаю разговор…" : text);
            if (text != null && !text.isBlank() && !"—".equals(text.trim()) && !"-".equals(text.trim())) {
                updateLiveHistory("assistant", "ПОДСКАЗКА PRODAMUS", text, complete);
            }
        });
    }

    @Override
    public void onTranscript(SpeakerRole role, String text, boolean complete) {
        // Реплики менеджера намеренно не показываются ни в текущей строке, ни в истории.
        // Они остаются внутри AI-контекста и recovery-memory координатора.
        if (role != SpeakerRole.CUSTOMER || text == null || text.isBlank()) return;
        Platform.runLater(() -> updateLiveHistory("customer", "КЛИЕНТ", text, complete));
    }

    @Override
    public void onError(String message) {
        Platform.runLater(() -> {
            setStatus("Ошибка");
            suggestion.setText(message);
            addHistory("error", "ОШИБКА", message);
        });
    }

    private void setStatus(String value) { status.setText(value == null ? "" : value); }
}
