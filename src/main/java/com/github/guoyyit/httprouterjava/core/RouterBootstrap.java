package com.github.guoyyit.httprouterjava.core;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Recovers the last committed route table on startup before the service
 * accepts traffic.
 */
@Component
public class RouterBootstrap implements ApplicationRunner {

    private final DefaultRouterService routerService;

    public RouterBootstrap(DefaultRouterService routerService) {
        this.routerService = routerService;
    }

    @Override
    public void run(ApplicationArguments args) {
        routerService.restoreLatest();
    }
}
