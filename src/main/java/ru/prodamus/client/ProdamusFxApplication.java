package ru.prodamus.client;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.prodamus.client.ui.OverlayWindow;

public class ProdamusFxApplication extends Application {
    private static final Logger log = LoggerFactory.getLogger(ProdamusFxApplication.class);
    private static String[] arguments = new String[0];
    private ConfigurableApplicationContext context;

    static void launchApp(String[] args) {
        log.debug("Launching JavaFX runtime");
        arguments = args.clone();
        Application.launch(ProdamusFxApplication.class, args);
    }

    @Override
    public void init() {
        log.info("JavaFX init; creating Spring application context");
        context = new SpringApplicationBuilder(ProdamusClientConfiguration.class)
                .headless(false)
                .run(arguments);
        log.info("Spring application context initialized: beanCount={}", context.getBeanDefinitionCount());
    }

    @Override
    public void start(Stage primaryStage) {
        log.info("JavaFX primary stage start");
        context.getBean(OverlayWindow.class).show(primaryStage);
    }

    @Override
    public void stop() {
        log.info("JavaFX application stopping");
        if (context != null) context.close();
        Platform.exit();
        log.info("Prodamus stopped cleanly");
    }
}
