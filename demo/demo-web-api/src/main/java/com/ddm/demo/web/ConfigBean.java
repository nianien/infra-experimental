package com.ddm.demo.web;

import com.ddm.chaos.annotation.Conf;
import lombok.Getter;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Supplier;

@Component
@Getter
public class ConfigBean {
    @Conf(namespace = "com.ddm", group = "cfd", key = "demo.name", defaultValue = "这是默认值")
    private Supplier<String> name;
    @Conf(namespace = "com.ddm", group = "cfd", key = "demo.age", defaultValue = "-1")
    private Supplier<Integer> age;
    @Conf(namespace = "com.ddm", group = "cfd", key = "demo.whitelist", defaultValue = "u001,u002")
    private Supplier<List<String>> whitelist;

}
