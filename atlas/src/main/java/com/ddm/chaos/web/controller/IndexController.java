package com.ddm.chaos.web.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 首页控制器，处理根路径请求。
 *
 * @author liyifei
 * @since 1.0
 */
@Controller
public class IndexController {

    /**
     * 根路径，重定向到首页。
     */
    @GetMapping("/")
    public String home() {
        return "redirect:/index.html";
    }
}

