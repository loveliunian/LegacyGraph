package io.github.legacygraph.common;

import lombok.Data;

/**
 * 统一API响应结果封装
 */
@Data
public class Result<T> {

    private Integer code;
    private String message;
    private T data;

    public static <T> Result<T> success() {
        return success(null);
    }

    public static <T> Result<T> success(T data) {
        Result<T> result = new Result<>();
        result.setCode(0);
        result.setMessage("success");
        result.setData(data);
        return result;
    }

    public static <T> Result<T> error(String message) {
        Result<T> result = new Result<>();
        result.setCode(1);
        result.setMessage(message);
        return result;
    }

    public static <T> Result<T> code(Integer code, String message) {
        Result<T> result = new Result<>();
        result.setCode(code);
        result.setMessage(message);
        return result;
    }

    /**
     * Alias for success - used by some controllers
     */
    public static <T> Result<T> ok(T data) {
        return success(data);
    }

    /**
     * Alias for success with no data
     */
    public static <T> Result<T> ok() {
        return success(null);
    }

    /**
     * Bad request error
     */
    public static <T> Result<T> badRequest(String message) {
        return error(message);
    }
}
