package io.github.astatine202.scras.backend.controller;

import org.springframework.web.bind.annotation.*;

@RestController
public class HomeController {
    @GetMapping("/")
    public String home() {
        return "Backend Service is Running";
    }
}
