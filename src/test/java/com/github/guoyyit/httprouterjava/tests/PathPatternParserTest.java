package com.github.guoyyit.httprouterjava.tests;

import com.github.guoyyit.httprouterjava.core.InvalidRouteException;
import com.github.guoyyit.httprouterjava.pattern.CatchAllSegment;
import com.github.guoyyit.httprouterjava.pattern.LiteralSegment;
import com.github.guoyyit.httprouterjava.pattern.ParamSegment;
import com.github.guoyyit.httprouterjava.pattern.PathPattern;
import com.github.guoyyit.httprouterjava.pattern.PathPatternParser;
import com.github.guoyyit.httprouterjava.pattern.WildcardSegment;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathPatternParserTest {

    private final PathPatternParser parser = new PathPatternParser();

    @Test
    void parsesStaticSegments() {
        PathPattern p = parser.parse("/users/profile");
        assertEquals(1, p.getAlternatives().size());
        List<?> segments = p.getAlternatives().get(0);
        assertEquals(List.of(new LiteralSegment("users"), new LiteralSegment("profile")),
                segments);
    }

    @Test
    void parsesParameterWithAndWithoutRegex() {
        PathPattern p = parser.parse("/users/{id:[0-9]+}/posts/{postId}");
        List<?> segments = p.getAlternatives().get(0);
        assertEquals(4, segments.size());
        assertEquals("id", ((ParamSegment) segments.get(1)).name());
        assertEquals("[0-9]+", ((ParamSegment) segments.get(1)).constraint().pattern());
        assertNull(((ParamSegment) segments.get(3)).constraint());
    }

    @Test
    void parsesWildcardAndCatchAll() {
        PathPattern p = parser.parse("/assets/**:tail");
        List<?> segments = p.getAlternatives().get(0);
        assertEquals(new CatchAllSegment("tail"), segments.get(1));

        PathPattern w = parser.parse("/assets/*/x");
        assertEquals(new WildcardSegment(), w.getAlternatives().get(0).get(1));
    }

    @Test
    void expandsOptionalFragment() {
        PathPattern p = parser.parse("/api(/v1)/users");
        assertEquals(2, p.getAlternatives().size());
        assertTrue(p.match(parser.splitRequestPath("/api/users")) != null);
        assertTrue(p.match(parser.splitRequestPath("/api/v1/users")) != null);
        assertNull(p.match(parser.splitRequestPath("/api/v2/users")));
    }

    @Test
    void multipleOptionalGroupsExpandCartesianProduct() {
        PathPattern p = parser.parse("/a(/b)(/c)");
        assertEquals(4, p.getAlternatives().size());
        for (String path : List.of("/a", "/a/b", "/a/c", "/a/b/c")) {
            assertTrue(p.match(parser.splitRequestPath(path)) != null, path);
        }
        assertNull(p.match(parser.splitRequestPath("/a/b/x")));
    }

    @Test
    void regexConstrainedParameterMustFullyMatch() {
        PathPattern p = parser.parse("/users/{id:[0-9]+}");
        assertTrue(p.match(parser.splitRequestPath("/users/123")) != null);
        assertNull(p.match(parser.splitRequestPath("/users/abc")));
    }

    @Test
    void catchAllMatchesZeroOrMoreSegments() {
        PathPattern p = parser.parse("/files/**:rest");
        Map<String, String> m = p.match(parser.splitRequestPath("/files/a/b/c.txt"));
        assertEquals("a/b/c.txt", m.get("rest"));
        Map<String, String> m0 = p.match(parser.splitRequestPath("/files"));
        assertEquals("", m0.get("rest"));
    }

    @Test
    void rejectsInvalidPatterns() {
        assertThrows(InvalidRouteException.class, () -> parser.parse("users"));
        assertThrows(InvalidRouteException.class, () -> parser.parse("/a//b"));
        assertThrows(InvalidRouteException.class, () -> parser.parse("/a(/b"));
        assertThrows(InvalidRouteException.class, () -> parser.parse("/a/b)"));
        assertThrows(InvalidRouteException.class, () -> parser.parse("/a(/b(/c))"));
        assertThrows(InvalidRouteException.class, () -> parser.parse("/{id"));
        assertThrows(InvalidRouteException.class, () -> parser.parse("/a*"));
        assertThrows(InvalidRouteException.class, () -> parser.parse("/**/a"));
        assertThrows(InvalidRouteException.class, () -> parser.parse("/{id:[0-9}"));
        assertThrows(InvalidRouteException.class, () -> parser.parse("/{a}/{a}"));
    }
}
