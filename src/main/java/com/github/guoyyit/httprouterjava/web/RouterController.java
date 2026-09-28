package com.github.guoyyit.httprouterjava.web;

import com.github.guoyyit.httprouterjava.core.DefaultRouterService;
import com.github.guoyyit.httprouterjava.core.RouterService;
import com.github.guoyyit.httprouterjava.model.HttpMethod;
import com.github.guoyyit.httprouterjava.model.MatchResult;
import com.github.guoyyit.httprouterjava.web.dto.MatchResponse;
import com.github.guoyyit.httprouterjava.web.dto.RouteRequest;
import com.github.guoyyit.httprouterjava.web.dto.RoutesReplaceRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * HTTP surface: matching, atomic registration updates and rollback.
 */
@RestController
@RequestMapping("/router")
public class RouterController {

    private final RouterService routerService;
    private final DefaultRouterService defaultRouterService;

    public RouterController(RouterService routerService,
                            DefaultRouterService defaultRouterService) {
        this.routerService = routerService;
        this.defaultRouterService = defaultRouterService;
    }

    /** Match by method + path; header conditions are read from request headers. */
    @GetMapping("/match")
    public MatchResponse match(@RequestParam String method,
                               @RequestParam String path,
                               @RequestHeader Map<String, String> headers) {
        MatchResult result = routerService.match(HttpMethod.from(method), path, headers);
        return new MatchResponse(result.handlerId(), routerService.currentVersion(),
                result.priority(), result.pathVariables(), result.matchedHeaders());
    }

    @PostMapping("/routes")
    public Map<String, Object> addRoute(@RequestBody RouteRequest request) {
        routerService.addRoute(request.toRoute());
        return Map.of("version", routerService.currentVersion());
    }

    @DeleteMapping("/routes/{handlerId}")
    public Map<String, Object> removeRoute(@PathVariable String handlerId) {
        routerService.removeRoute(handlerId);
        return Map.of("version", routerService.currentVersion());
    }

    @PutMapping("/routes")
    public Map<String, Object> replaceAll(@RequestBody RoutesReplaceRequest request) {
        routerService.replaceAll(request.toRoutes());
        return Map.of("version", routerService.currentVersion());
    }

    @GetMapping("/routes")
    public Object list() {
        return Map.of("version", routerService.currentVersion(),
                "routes", routerService.snapshot());
    }

    @PostMapping("/rollback/{version}")
    public Object rollback(@PathVariable long version) {
        return defaultRouterService.rollback(version);
    }
}
