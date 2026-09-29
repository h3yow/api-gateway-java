package com.github.h3yow.apigatewayjava.gateway.downstream;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * 本地模拟下游服务（与网关同进程，仅用于本地联调与文档验证）。
 * <ul>
 *     <li>POST /mock/downstream/echo  —— 正常 200；</li>
 *     <li>POST /mock/downstream/slow  —— sleep 2 秒，触发网关 300ms 读超时；</li>
 *     <li>POST /mock/downstream/error —— 返回 500，触发下游错误链路；</li>
 *     <li>POST /mock/downstream/busy  —— 返回 429，触发下游限流链路。</li>
 * </ul>
 */
@RestController
@RequestMapping("/mock/downstream")
public class DownstreamMockController {

    @PostMapping("/echo")
    public String echo() {
        return "{\"downstream\":\"echo\",\"ok\":true}";
    }

    @PostMapping("/slow")
    public String slow() throws InterruptedException {
        Thread.sleep(2_000);
        return "{\"downstream\":\"slow\"}";
    }

    @PostMapping("/error")
    public void error(HttpServletResponse response) throws IOException {
        response.setStatus(500);
        response.setContentType("application/json");
        response.getWriter().write("{\"downstream\":\"error\"}");
    }

    @PostMapping("/busy")
    public void busy(HttpServletResponse response) throws IOException {
        response.setStatus(429);
        response.setContentType("application/json");
        response.getWriter().write("{\"downstream\":\"busy\"}");
    }

    // —— 业务接口组 mock：orders / payments / reports / beta-ai / ledger-v2 ——
    // 类级前缀为 /mock/downstream，故这些业务组演示端点也位于此前缀下；
    // 它们归属于各自的接口组（由 ApiGroupConfig 路由表声明），与 echo 等同路径前缀无关。

    @PostMapping("/orders/create")
    public String ordersCreate() {
        return "{\"downstream\":\"orders\",\"created\":true}";
    }

    @PostMapping("/payments/charge")
    public String paymentsCharge() {
        return "{\"downstream\":\"payments\",\"charged\":true}";
    }

    @PostMapping("/reports/daily")
    public String reportsDaily() {
        return "{\"downstream\":\"reports\"}";
    }

    @PostMapping("/ai/chat")
    public String aiChat() {
        return "{\"downstream\":\"beta-ai\",\"reply\":\"ok\"}";
    }

    @PostMapping("/ledger/v2")
    public String ledgerV2() {
        return "{\"downstream\":\"ledger-v2\"}";
    }
}
