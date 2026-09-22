package com.github.h3yow.apigatewayjava.gateway.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 为每个 HTTP 请求生成可追踪标识 traceId。
 *
 * 设计决策：无论请求是否携带 X-Trace-Id，服务端都生成新值——因为“重试请求按新请求重新判定”，
 * 每次 HTTP 处理都是一次独立判定，traceId 必须一一对应。生成后同时：
 * <ol>
 *     <li>写入请求属性，供控制器/异常处理器读取；</li>
 *     <li>写入响应头 X-Trace-Id（包括被拒绝的响应）。</li>
 * </ol>
 */
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_ATTRIBUTE = "gateway.traceId";
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = UUID.randomUUID().toString().replace("-", "");
        request.setAttribute(TRACE_ID_ATTRIBUTE, traceId);
        response.setHeader(TRACE_ID_HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            // 兜底再确保一次：异常处理器可能替换 response 对象时仍能带上
            response.setHeader(TRACE_ID_HEADER, traceId);
        }
    }
}
