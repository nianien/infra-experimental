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

    @Conf(key = "demo.name", value = "savin")
    public Supplier<String> name;
    @Conf(key = "demo.age", value = "42")
    public Supplier<Integer> age;
}



