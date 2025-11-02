package com.ddm.chaos.demo;

import com.ddm.chaos.annotation.Conf;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * @author : liyifei
 * @created : 2025/10/29, Wednesday
 * Copyright (c) 2004-2029 All Rights Reserved.
 **/
@Component
public record DemoBean(
        @Conf(key = "demo.name") Supplier<String> name,
        @Conf(key = "demo.age") Supplier<Integer> age
) {
}



