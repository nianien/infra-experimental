package com.ddm.demo.client;

import com.ddm.chaos.annotation.Conf;
import lombok.Getter;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

@Component
@Getter
public class ConfigBean {
    @Conf(name = "demo.name")
    private Supplier<String> name;
    @Conf(name = "demo.age")
    private Supplier<Integer> age;


}
