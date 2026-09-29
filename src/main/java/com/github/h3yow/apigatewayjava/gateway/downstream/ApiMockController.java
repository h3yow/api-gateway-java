package com.github.h3yow.apigatewayjava.gateway.downstream;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 接口组业务下游 mock（与网关同进程，仅用于本地联调与文档验证）。
 * 对应默认路由表中的接口组：
 * <ul>
 *     <li>POST /api/orders/{id}    —— orders 组（全量、自有配额）；</li>
 *     <li>POST /api/reports/daily  —— reports 组（全量、回退配额）；</li>
 *     <li>POST /api/beta/query     —— beta 组（灰度）；</li>
 *     <li>POST /api/legacy/query   —— legacy 组（已暂停，网关本地即拒，不会到达这里）；</li>
 *     <li>POST /api/future/query   —— future 组（未开放，网关本地即拒，不会到达这里）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
public class ApiMockController {

    @PostMapping("/orders/{id}")
    public String order(@PathVariable String id) {
        return "{\"group\":\"orders\",\"id\":\"" + id + "\",\"ok\":true}";
    }

    @PostMapping("/reports/daily")
    public String dailyReport() {
        return "{\"group\":\"reports\",\"ok\":true}";
    }

    @PostMapping("/beta/query")
    public String betaQuery() {
        return "{\"group\":\"beta\",\"ok\":true}";
    }

    @PostMapping("/legacy/query")
    public String legacyQuery() {
        return "{\"group\":\"legacy\",\"ok\":true}";
    }

    @PostMapping("/future/query")
    public String futureQuery() {
        return "{\"group\":\"future\",\"ok\":true}";
    }
}
