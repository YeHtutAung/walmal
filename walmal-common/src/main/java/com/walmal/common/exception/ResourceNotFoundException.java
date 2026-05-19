package com.walmal.common.exception;

public class ResourceNotFoundException extends WalmalException {
    public ResourceNotFoundException(String resource, Object id) {
        super(String.format("%s not found with id: %s", resource, id));
    }
}
