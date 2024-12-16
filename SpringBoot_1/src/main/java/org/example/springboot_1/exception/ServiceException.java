package org.example.springboot_1.exception;

import lombok.Getter;

@Getter
public class ServiceException extends RuntimeException{
    private final String code;

    public ServiceException(String msg){
        super(msg);
        this.code = "500";
    }

    public ServiceException(String code, String msg){
        super(msg);
        this.code = code;
    }
}
