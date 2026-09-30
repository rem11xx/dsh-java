package com.dsh.web;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 健康检查端点, 用于验证项目能正常启动。
 */
@RestController
@RequestMapping("/api")
public class PingController {

    @GetMapping("/ping")
    public Map<String, String> ping() {
        return Map.of("status", "ok", "app", "dsh-java");
    }
}
