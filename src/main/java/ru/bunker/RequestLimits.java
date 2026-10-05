package ru.bunker;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;

@Component
public class RequestLimits extends OncePerRequestFilter {
    private static final int MAX_BYTES = 2 * 1024 * 1024;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "same-origin");
        response.setHeader("Content-Security-Policy", "default-src 'self'; style-src 'self'; img-src 'self' data:; script-src 'self'; connect-src 'self'; object-src 'none'; frame-ancestors 'self'; base-uri 'self'");
        if (request.getRequestURI().startsWith("/api/")) response.setHeader("Cache-Control", "no-store");
        if ("POST".equals(request.getMethod())) {
            byte[] body = request.getInputStream().readNBytes(MAX_BYTES + 1);
            if (body.length > MAX_BYTES) {
                response.setStatus(413);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"message\":\"Размер запроса превышает 2 МБ.\"}");
                return;
            }
            var wrapped = new HttpServletRequestWrapper(request) {
                @Override
                public ServletInputStream getInputStream() {
                    var in = new ByteArrayInputStream(body);
                    return new ServletInputStream() {
                        public int read() {
                            return in.read();
                        }

                        public boolean isFinished() {
                            return in.available() == 0;
                        }

                        public boolean isReady() {
                            return true;
                        }

                        public void setReadListener(ReadListener listener) {
                            throw new UnsupportedOperationException();
                        }
                    };
                }

                @Override
                public BufferedReader getReader() {
                    return new BufferedReader(new InputStreamReader(getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
                }
            };
            chain.doFilter(wrapped, response);
        } else chain.doFilter(request, response);
    }
}
