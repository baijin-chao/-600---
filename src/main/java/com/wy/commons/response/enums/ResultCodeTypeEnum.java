package com.wy.commons.response.enums;

import lombok.Getter;

/**
 * 异常类型枚举
 * @author baijin-chao
 * @date 2022-03-01
 * @version V1.0
 */
@Getter
public enum ResultCodeTypeEnum {

    /**
     * 异常类型枚举
     */
    USER("USER","用户"),
    SYSTEM("SYSTEM", "系统通用"),
    TEST("TEST", "测试"),
    ;

    // 结果类型code，将作为结果码的前缀
    private final String code;
    // 描述
    private final String desc;

    ResultCodeTypeEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /**
     * 根据 code 获取描述信息
     * @param code code 码
     * @return 描述信息
     */
    public static String getDescByCode(String code) {
        for (ResultCodeTypeEnum codeType : ResultCodeTypeEnum.values()) {
            if (codeType.getCode().equals(code)) {
                return codeType.getDesc();
            }
        }
        return null;
    }
}
