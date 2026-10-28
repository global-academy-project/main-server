package com.example.backend.admin.dto;

/**
 * 임시 비밀번호. 이 응답에서 한 번만 보인다 (DB엔 해시만). 관리자가 본인에게 직접 전달하고,
 * 본인은 다음 로그인 때 새 비밀번호로 바꿔야 한다.
 */
public record TemporaryPasswordResponse(String temporaryPassword) {
}
