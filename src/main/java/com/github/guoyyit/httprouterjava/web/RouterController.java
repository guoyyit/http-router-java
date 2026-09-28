package com.github.guoyyit.httprouterjava.web;

import com.github.guoyyit.httprouterjava.router.HeaderCondition;
import com.github.guoyyit.httprouterjava.router.MatchResult;
import com.github.guoyyit.httprouterjava.router.PatternParseException;
import com.github.guoyyit.httprouterjava.router.Route;
import com.github.guoyyit.httprouterjava.router.RouteConflictException;
import com.github.guoyyit.httprouterjava.router.RouteNotFoundException;
import com.github.guoyyit.httprouterjava.router.RouterService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/router")
public class RouterController {

    private final RouterService routerService;

    public RouterController(RouterService routerService) {
        this.routerService = routerService;
    }

    @PostMapping("/match")
    public Map<String, Object> match(@RequestBody MatchRequest req) {
        MatchResult result = routerService.match(req.method(), req.path(), req.headers());
        return serialize(result);
    }

    @PostMapping("/routes")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, String> addRoute(@RequestBody RouteRequest req) {
        Route route = toRoute(req);
        routerService.addRoute(route);
        return Map.of("id", route.id());
    }

    @DeleteMapping("/routes/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeRoute(@PathVariable String id) {
        routerService.removeRoute(id);
    }

    @PostMapping("/routes/batch")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void batchUpdate(@RequestBody BatchRequest req) {
        List<Route> additions = new ArrayList<>();
        if (req.additions() != null) {
            for (RouteRequest r : req.additions()) {
                additions.add(toRoute(r));
            }
        }
        routerService.update(additions, req.removals() == null ? List.of() : req.removals());
    }

    @GetMapping("/routes")
    public List<RouteView> listRoutes() {
        List<RouteView> views = new ArrayList<>();
        for (Route r : routerService.routes()) {
            views.add(toView(r));
        }
        return views;
    }

    @PostMapping("/rollback")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void rollback() {
        routerService.rollback();
    }

    private static Route toRoute(RouteRequest req) {
        return Route.create(req.id(), req.method(), req.pattern(), req.headers(),
                req.handlerId(), req.priority());
    }

    private static RouteView toView(Route r) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (HeaderCondition c : r.headerConditions()) {
            headers.put(c.name(), c.expression());
        }
        return new RouteView(r.id(), r.method().name(), r.pattern().raw(), headers,
                r.handlerId(), r.priority());
    }

    private static Map<String, Object> serialize(MatchResult result) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (result instanceof MatchResult.Hit hit) {
            out.put("status", "HIT");
            out.put("routeId", hit.route().id());
            out.put("handlerId", hit.route().handlerId());
            out.put("method", hit.route().method().name());
            out.put("pattern", hit.route().pattern().raw());
            out.put("priority", hit.route().priority());
        } else if (result instanceof MatchResult.NoMatch miss) {
            out.put("status", "NO_MATCH");
            out.put("reason", miss.reason());
        } else if (result instanceof MatchResult.Conflict conflict) {
            out.put("status", "CONFLICT");
            out.put("reason", conflict.reason());
            List<String> ids = new ArrayList<>();
            for (Route r : conflict.candidates()) {
                ids.add(r.id());
            }
            out.put("candidates", ids);
        }
        return out;
    }

    @ExceptionHandler(RouteConflictException.class)
    public ResponseEntity<Map<String, String>> onConflict(RouteConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(RouteNotFoundException.class)
    public ResponseEntity<Map<String, String>> onNotFound(RouteNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler({PatternParseException.class, IllegalArgumentException.class})
    public ResponseEntity<Map<String, String>> onBadRequest(RuntimeException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> onBadState(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    // ---- request DTOs ----
    public record MatchRequest(String method, String path, Map<String, String> headers) {}
    public record RouteRequest(String id, String method, String pattern, Map<String, String> headers,
                               String handlerId, Integer priority) {}
    public record BatchRequest(List<RouteRequest> additions, List<String> removals) {}
    public record RouteView(String id, String method, String pattern, Map<String, String> headers,
                            String handlerId, int priority) {}
}
