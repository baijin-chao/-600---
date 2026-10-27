package com.wy.commons.response;

import com.wy.commons.response.enums.CommonResultCodeEnum;
import com.wy.commons.response.service.ResultCode;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.util.UUID;

/**
 * 统一响应体。
 *
 * <p>设计取舍：HTTP 状态码统一返回 200，业务结果放在 {@code code} 里
 * （除了"触发探测"这种需要明确 202/409 语义的接口）。这样做的好处是前端只需解析一层，
 * 代价是不符合严格的 REST 语义——如果调用方是浏览器之外的系统，建议按接口约定改造。</p>
 *
 * @param <T> 业务数据类型
 * @author baijin-chao
 */
@Getter
@Setter
public class BaseResult<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 是否成功 */
    private Boolean success;

    /** 业务码，"0" 表示成功 */
    private String code;

    /** 描述信息 */
    private String message;

    /** 业务数据 */
    private T data;

    /** 服务器时间戳 */
    private final Long timestamp = System.currentTimeMillis();

    /** 请求链路 ID，便于和日志对照 */
    private final String requestId = UUID.randomUUID().toString().replace("-", "");

    public BaseResult() {
        this(CommonResultCodeEnum.CODE_0000);
    }

    public BaseResult(T data) {
        this(CommonResultCodeEnum.CODE_0000, data);
    }

    public BaseResult(ResultCode resultCode) {
        this(resultCode.getSuccess(), resultCode.getCode(), resultCode.getDesc(), null);
    }

    public BaseResult(ResultCode resultCode, T data) {
        this(resultCode.getSuccess(), resultCode.getCode(), resultCode.getDesc(), data);
    }

    public BaseResult(Boolean success, String code, String message, T data) {
        this.success = success;
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public static <T> BaseResult<T> ok() {
        return new BaseResult<>();
    }

    public static <T> BaseResult<T> ok(T data) {
        return new BaseResult<>(data);
    }

    public static <T> BaseResult<T> fail(ResultCode resultCode) {
        return new BaseResult<>(resultCode);
    }

    public static <T> BaseResult<T> fail(String code, String message) {
        return new BaseResult<>(false, code, message, null);
    }
}
