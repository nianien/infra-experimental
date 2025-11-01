package com.ddm.chaos.demo;

import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * @author : liyifei
 * @created : 2025/10/29, Wednesday
 * Copyright (c) 2004-2029 All Rights Reserved.
 **/
@Component
public class DemoBean {

    @Resource(name = "com.dd.demo.name")
    Supplier<String> name;

    @Resource(name = "com.dd.demo.age")
    Supplier<Integer> age;


    public void doSomething() {
        String name = this.name.get();
        Integer age = this.age.get();
        System.out.println(String.format("name=%s,age=%d", name, age));
    }

}



