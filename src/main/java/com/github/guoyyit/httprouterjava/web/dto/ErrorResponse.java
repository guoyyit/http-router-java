package com.github.guoyyit.httprouterjava.web.dto;

import java.util.List;

/** Explained failure response. The {@code error} code distinguishes miss kinds. */
public record ErrorResponse(String error, String message, List<String> details) {
}
