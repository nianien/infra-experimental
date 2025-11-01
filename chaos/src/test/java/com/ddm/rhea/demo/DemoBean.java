package com.ddm.rhea.demo;

import jakarta.annotation.Resource;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * @author : liyifei
 * @created : 2025/10/29, Wednesday
 * Copyright (c) 2004-2029 All Rights Reserved.
 **/
@ConfigurationProperties("com.dd.demo")
@Component
public class DemoBean {

    @Resource(name = "com.dd.demo.name")
    private Supplier<String> name;
    private Supplier<Integer> age;


    public void doSomething() {
        String name = this.name.get();
        Integer age = this.age.get();
        System.out.println(String.format("name=%s,age=%d", name, age));
    }

}



