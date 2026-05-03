package com.auracademic.backend.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Trả về 428 Precondition Required khi login thành công nhưng cần nhập 2FA code */
@ResponseStatus(HttpStatus.PRECONDITION_REQUIRED)
public class TwoFactorRequiredException extends RuntimeException {
    public TwoFactorRequiredException(String message) {
        super(message);
    }
}
