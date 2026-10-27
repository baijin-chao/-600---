package com.wy.commons.exception;

import com.wy.commons.response.enums.CommonResultCodeEnum;
import com.wy.commons.response.service.ResultCode;
import lombok.Getter;
import lombok.Setter;

/**
 * 业务异常：携带一个业务码，由全局异常处理器统一转换。
 *
 * @author baijin-chao
 */
@Getter
@Setter
public class CustomException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 业务码，可以是 CommonResultCodeEnum，也可以是业务方自己实现的 ResultCode */
    private ResultCode resultCode;

    public CustomException(String message) {
        super(message);
        this.resultCode = CommonResultCodeEnum.CODE_4009;
    }

    public CustomException(ResultCode resultCode) {
        super(resultCode.getDesc());
        this.resultCode = resultCode;
    }
}
