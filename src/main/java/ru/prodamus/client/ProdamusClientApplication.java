package ru.prodamus.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ProdamusClientApplication {
    private static final Logger log = LoggerFactory.getLogger(ProdamusClientApplication.class);

    private ProdamusClientApplication() {
    }

    public static void main(String[] args) {
        Thread.setDefaultUncaughtExceptionHandler((thread, error) ->
                log.error("Uncaught exception in thread {}", thread.getName(), error));
        log.info("Prodamus Predictive Windows client starting: pid={}, java={}, os={} {}, userDir={}, argsCount={}",
                ProcessHandle.current().pid(), System.getProperty("java.version"), System.getProperty("os.name"),
                System.getProperty("os.version"), System.getProperty("user.dir"), args.length);
        ProdamusFxApplication.launchApp(args);
    }
}
