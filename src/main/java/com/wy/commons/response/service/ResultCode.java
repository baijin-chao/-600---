package com.wy.commons.response.service;

import com.wy.commons.response.enums.ResultCodeTypeEnum;

/**
 * 获取结果信息接口
 * @author baijin-chao
 * @date 2022-03-01
 * @version V1.0
 */
public interface ResultCode {

    /**
     * 获取异常类型
     * @return 异常类型(按业务区分)
     */
    ResultCodeTypeEnum getType();

    /**
     * 获取结果码
     * @return 结果码
     */
    String getCode();

    /**
     * 获取结果描述
     * @return 结果描述
     */
    String getDesc();

    /**
     * 获取结果成功状态
     * @return 成功状态
     */
    Boolean getSuccess();

}
