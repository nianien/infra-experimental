package com.ddm.demo.client;

import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

@Component
public record ConfigBean(
        @Resource(name = "com.dd.demo.name") Supplier<String> name,
        @Resource(name = "com.dd.demo.age") Supplier<Integer> age
) {
}