package com.example.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/** OCR 처리를 요청 스레드와 분리해서 실행하기 위한 설정 (@Async 활성화). */
@Configuration
@EnableAsync
public class AsyncConfig {
}
