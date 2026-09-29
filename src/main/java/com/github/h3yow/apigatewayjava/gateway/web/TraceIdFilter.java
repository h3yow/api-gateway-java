package com.github.h3yow.apigatewayjava.gateway.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 为每个 HTTP 请求生成唯一追踪标识 traceId。
 *
 * 设计决策：无论请求是否携带 X-Trace-Id，服务端都生成新值——因为“重试请求按新请求重新判定”，
 * 每次 HTTP 处理都是一次独立判定，traceId 必须一一对应。生成后：
 * <ol>
 *     <li>写入请求属性，供控制器/异常处理器读取（错误体 traceId 与响应头严格一致，来源唯一）；</li>
 *     <li>通过 {@code setHeader} 写入响应头 X-Trace-Id（同名重复写入会覆盖而非追加，
 *         因此每个响应只会保留一个 X-Trace-Id）。</li>
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
        filterChain.doFilter(request, response);
    }
}
