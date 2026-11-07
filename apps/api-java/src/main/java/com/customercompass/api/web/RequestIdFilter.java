package com.customercompass.api.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Every response carries X-Request-ID. A caller's own id is kept if it is 1 to 64 letters, digits,
 * dots, underscores or hyphens; anything else is replaced by a generated one. The id is also put in
 * the logging context so each log line of the request carries it.
 */
@Component
public class RequestIdFilter extends OncePerRequestFilter {

  public static final String HEADER = "X-Request-ID";
  private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._-]{1,64}");

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String id = request.getHeader(HEADER);
    if (id == null || !SAFE.matcher(id).matches()) {
      id = UUID.randomUUID().toString().replace("-", "");
    }
    response.setHeader(HEADER, id);
    MDC.put("request_id", id);
    try {
      chain.doFilter(request, response);
    } finally {
      MDC.remove("request_id");
    }
  }
}
