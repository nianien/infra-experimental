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
public class DemoBean {

    @Conf(namespace = "com.ddm", group = "cfd", key = "demo.name", defaultValue = "savin")
    public Supplier<String> name = () -> "savin";
    @Conf(namespace = "com.ddm", group = "cfd", key = "demo.age", defaultValue = "42")
    public Supplier<Integer> age = () -> 42;
}



