package com.ddm.chaos.defined;

import com.ddm.chaos.utils.Converters;

import java.lang.reflect.Type;

/**
 * @author : liyifei
 * @created : 2025/11/5, Wednesday
 * Copyright (c) 2004-2029 All Rights Reserved.
 **/
public record ConfDesc(ConfInfo info, Object defaultValue, Type type) {

    public ConfDesc(ConfInfo info, String defaultValue, Type type) {
        this(info, (Object) Converters.cast(defaultValue, type), type);
    }


}
