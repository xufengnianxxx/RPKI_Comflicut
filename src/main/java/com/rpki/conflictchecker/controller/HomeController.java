package com.rpki.conflictchecker.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HomeController {

    @GetMapping("/")
    public String home() {
        return "RPKI Conflict Detection System v1.0 - Ready";
    }
}
