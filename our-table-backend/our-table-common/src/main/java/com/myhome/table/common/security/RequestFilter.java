package com.myhome.table.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myhome.table.common.dto.ApiResponse;
import com.myhome.table.common.exception.ApiException;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(-100)
public class RequestFilter extends OncePerRequestFilter {
  public static final String USER = "ourTableUser";
  private final SessionStore sessions;
  private final AccessFacts facts;
  private final ObjectMapper json;

  public RequestFilter(SessionStore sessions, AccessFacts facts, ObjectMapper json) {
    this.sessions = sessions;
    this.facts = facts;
    this.json = json;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    String incoming = req.getHeader("X-Request-ID");
    String id =
        incoming != null && incoming.matches("[A-Za-z0-9_-]{1,64}")
            ? incoming
            : UUID.randomUUID().toString();
    MDC.put("requestId", id);
    res.setHeader("X-Request-ID", id);
    try {
      if (req.getRequestURI().startsWith("/api/")
          && !req.getRequestURI().equals("/api/v1/auth/wechat/login")) {
        String h = req.getHeader("Authorization");
        String token = h != null && h.startsWith("Bearer ") ? h.substring(7) : null;
        Long user = sessions.resolve(token);
        if (facts.activeUser(user) == null)
          throw new ApiException(401, "AUTH_REQUIRED", "账号不可用，请重新登录");
        req.setAttribute(
            USER, new UserContext(user, facts.chefRestaurant(user), facts.grantExpiresAt(user)));
      }
      chain.doFilter(req, res);
    } catch (ApiException e) {
      error(res, e);
    } catch (org.springframework.dao.DataAccessException e) {
      error(res, ApiException.unavailable());
    } finally {
      MDC.remove("requestId");
    }
  }

  private void error(HttpServletResponse res, ApiException e) throws IOException {
    res.setStatus(e.status());
    res.setContentType("application/json;charset=UTF-8");
    json.writeValue(res.getOutputStream(), ApiResponse.error(e.code(), e.getMessage()));
  }
}
