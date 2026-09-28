package com.github.guoyyit.httprouterjava.web;

import com.github.guoyyit.httprouterjava.router.RouteSnapshotStore;
import com.github.guoyyit.httprouterjava.router.RouterService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

@Configuration
public class RouterConfig {

    @Bean
    public RouteSnapshotStore routeSnapshotStore(@Value("${router.snapshot-file:data/router-snapshot.json}") String file) {
        return new RouteSnapshotStore(Path.of(file));
    }

    @Bean
    public RouterService routerService(RouteSnapshotStore store) {
        RouterService service = new RouterService(store);
        service.recover();
        return service;
    }
}
