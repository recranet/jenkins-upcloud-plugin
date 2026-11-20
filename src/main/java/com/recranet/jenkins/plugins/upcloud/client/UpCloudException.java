package com.recranet.jenkins.plugins.upcloud.client;

import java.io.IOException;

/** An error response from the UpCloud API. */
public class UpCloudException extends IOException {

    private static final long serialVersionUID = 1L;

    private final int statusCode;
    private final String errorCode;

    public UpCloudException(int statusCode, String errorCode, String message) {
        super(message);
        this.statusCode = statusCode;
        this.errorCode = errorCode;
    }

    public int getStatusCode() {
        return statusCode;
    }

    /** The API's machine-readable error code, e.g. {@code SERVER_NOT_FOUND}; empty when the response had none. */
    public String getErrorCode() {
        return errorCode;
    }

    public boolean isNotFound() {
        return statusCode == 404;
    }
}
