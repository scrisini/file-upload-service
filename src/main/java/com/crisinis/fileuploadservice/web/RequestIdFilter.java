package com.crisinis.fileuploadservice.web;

import com.crisinis.fileuploadservice.logging.RequestIds;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Gives every request an id ({@link RequestIds}), puts it in the logging context so each log line
 * written while handling the request carries {@code http.request.id}, and returns it in the
 * {@code X-Request-Id} response header. A request is handled on one thread; work handed to other
 * threads must carry the id with {@link RequestIds#bind}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = RequestIds.acceptOrGenerate(request.getHeader(RequestIds.HEADER));
        response.setHeader(RequestIds.HEADER, requestId);
        try (RequestIds.Scope ignored = RequestIds.bind(requestId)) {
            chain.doFilter(request, response);
        }
    }
}
