package com.example.myapplication.network;

import java.io.IOException;

public final class HttpException extends IOException {

    private final int statusCode;
    private final String errorCode;

    public HttpException(int statusCode, String errorCode, String message) {
        super(message);
        this.statusCode = statusCode;
        this.errorCode = errorCode;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
