package com.wy.commons.response.enums;

import com.wy.commons.response.service.ResultCode;
import lombok.Getter;

/**
 * 公共业务码。
 *
 * <p>号段约定：4xxx 调用方问题，5xxx 服务端问题。新增业务码时按号段往后排，
 * 不要复用已发布的码值。</p>
 *
 * @author baijin-chao
 */
@Getter
public enum CommonResultCodeEnum implements ResultCode {

    /** 成功 */
    CODE_0000("0", "处理成功"),

    /** 请求参数错误 */
    CODE_4000("4000", "请求参数错误"),

    /** 请求参数格式错误 */
    CODE_4001("4001", "请求参数格式错误"),

    /** 未授权 */
    CODE_4010("4010", "未授权的访问，请检查 X-Admin-Token"),

    /** 资源不存在 */
    CODE_4040("4040", "资源不存在"),

    /** 业务处理失败 */
    CODE_4009("4009", "业务处理失败"),

    /** 任务正在执行中 */
    CODE_4090("4090", "上一轮探测尚未结束，请稍后再试"),

    /** 服务端异常 */
    CODE_5000("5000", "服务内部异常，请查看服务端日志"),

    /** 上游平台异常 */
    CODE_5020("5020", "调用 LiveGBS 失败"),

    /** 上游平台未就绪 */
    CODE_5030("5030", "LiveGBS 平台不可用"),
    ;

    private final String code;

    private final String desc;

    private final Boolean success;

    CommonResultCodeEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
        this.success = "0".equals(code);
    }

    @Override
    public ResultCodeTypeEnum getType() {
        return ResultCodeTypeEnum.SYSTEM;
    }
}
