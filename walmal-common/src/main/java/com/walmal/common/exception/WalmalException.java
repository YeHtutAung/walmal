package com.walmal.common.exception;

public class WalmalException extends RuntimeException {
    public WalmalException(String message) { super(message); }
    public WalmalException(String message, Throwable cause) { super(message, cause); }
}
