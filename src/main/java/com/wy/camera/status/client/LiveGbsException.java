package com.wy.camera.status.client;

/**
 * 调用 LiveGBS 失败。
 *
 * <p>把「HTTP 层错误」和「业务层错误」分开携带，是为了让上层能区分三种情况：</p>
 * <ul>
 *   <li>{@code authError = true}：token 失效，应刷新后重试，而不是记成"设备离线"；</li>
 *   <li>{@code httpStatus >= 500} 或为 null（超时）：平台/网络故障，记为 ERROR；</li>
 *   <li>其余 4xx：设备离线或平台资源不足（如 rtp server is busy），记为 OFFLINE 但保留原因。</li>
 * </ul>
 *
 * @author baijin-chao
 */
public class LiveGbsException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** HTTP 状态码，超时等无响应的情况为 null */
    private final Integer httpStatus;

    /** 是否属于鉴权失败 */
    private final boolean authError;

    public LiveGbsException(String message) {
        this(message, null, false, null);
    }

    public LiveGbsException(String message, Throwable cause) {
        this(message, null, false, cause);
    }

    public LiveGbsException(String message, Integer httpStatus, boolean authError, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
        this.authError = authError;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }

    public boolean isAuthError() {
        return authError;
    }

    /** 无响应（超时/IO）或 5xx，视为平台侧故障而非摄像头离线 */
    public boolean isPlatformError() {
        return httpStatus == null || httpStatus >= 500;
    }
}
