package com.ddm.chaos.web.dto;

/**
 * 用户信息 DTO（Record 版本）。
 *
 * @author liyifei
 * @since 1.0
 */
public record User(
        Long id,
        String username,
        String password,
        Integer status
) {

    /**
     * 检查用户是否启用。
     */
    public boolean isEnabled() {
        return status != null && status == 1;
    }
}