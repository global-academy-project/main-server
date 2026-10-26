package com.example.backend.common.response;

import java.util.List;

public record ErrorBody(String code, String message, List<ErrorDetail> details) {
}