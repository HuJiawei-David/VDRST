package org.example.springboot_1.common;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class Result {

    public static final String CODE_SUCCESS = "200";
    public static final String CODE_AUTO_ERROR = "401";
    public static final String CODE_SYS_ERROR = "500";
    public static final String CODE_CLIENT_ERROR = "400";
    public static final String CODE_CONFLICT = "409";
    public static final String CODE_NOT_FOUND = "404";
    public static final String CODE_RATE_LIMIT = "429";

    private String code;
    private String msg;
    private Object data;

    public static Result success(){
        return Result.builder().code(CODE_SUCCESS).msg("请求成功").build();
    }
    public static Result success(Object data){
        return new Result(CODE_SUCCESS, "请求成功", data);
    }
    public static Result error(String msg){
        return new Result(CODE_SYS_ERROR, msg, null);
    }
    public static Result error(String code, String msg){
        return new Result(code, msg, null);
    }
    public static Result error(){
        return new Result(CODE_SYS_ERROR, "系统错误", null);
    }
}
