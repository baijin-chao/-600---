package com.wy.commons.exception;

import com.wy.commons.response.BaseResult;
import com.wy.commons.response.enums.CommonResultCodeEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;

/**
 * 全局异常处理。
 *
 * <p>两个原则：</p>
 * <ol>
 *   <li><b>不把堆栈返回给调用方</b>——原始实现把完整 stacktrace 塞进了 data，
 *       既泄露内部结构，又让响应体大得没法看；</li>
 *   <li><b>分类型给码</b>——参数错误、路径错误、未知异常要能区分，否则排查全靠猜。</li>
 * </ol>
 *
 * @author baijin-chao
 */
@Slf4j
@RestControllerAdvice
public class WyExceptionHandler {

    /** 业务异常 */
    @ExceptionHandler(CustomException.class)
    public BaseResult<String> handleCustomException(CustomException ex) {
        log.warn("业务异常：{}", ex.getMessage());
        return new BaseResult<>(ex.getResultCode());
    }

    /** 参数缺失、类型不匹配、报文不可读 */
    @ExceptionHandler({
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class
    })
    public BaseResult<String> handleBadRequest(Exception ex) {
        log.warn("请求参数错误：{}", ex.getMessage());
        return BaseResult.fail(CommonResultCodeEnum.CODE_4001);
    }

    /** 请求方法不支持 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public BaseResult<String> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        log.warn("请求方法不支持：{}", ex.getMessage());
        return BaseResult.fail(CommonResultCodeEnum.CODE_4000);
    }

    /** 路径不存在 */
    @ExceptionHandler(NoHandlerFoundException.class)
    public BaseResult<String> handleNoHandlerFound(NoHandlerFoundException ex) {
        log.warn("接口不存在：{} {}", ex.getHttpMethod(), ex.getRequestURL());
        return BaseResult.fail(CommonResultCodeEnum.CODE_4040);
    }

    /** 兜底：任何未预料的异常都不应该把堆栈吐给调用方 */
    @ExceptionHandler(Throwable.class)
    public BaseResult<String> handleUnexpected(Throwable ex) {
        log.error("未处理的异常", ex);
        return BaseResult.fail(CommonResultCodeEnum.CODE_5000);
    }
}
