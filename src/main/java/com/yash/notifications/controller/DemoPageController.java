package com.yash.notifications.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class DemoPageController {

    @GetMapping("/demo")
    public String demo() {
        return "forward:/demo.html";
    }
}
