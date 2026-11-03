package com.example.backend.support;

import com.example.backend.common.exception.BusinessException;
import com.example.backend.common.exception.ErrorCode;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** 테스트 공통 도구: BusinessException 코드 검증. */
public final class TestFixtures {

    private TestFixtures() {
    }

    /** BusinessException 이면서 기대한 ErrorCode 인지 확인하고, 예외를 돌려줘 메시지를 추가로 검증할 수 있게 한다. */
    public static BusinessException expectCode(ThrowingCallable call, ErrorCode code) {
        Throwable t = catchThrowable(call);
        assertThat(t).isInstanceOf(BusinessException.class);
        BusinessException be = (BusinessException) t;
        assertThat(be.getErrorCode()).isEqualTo(code);
        return be;
    }
}
