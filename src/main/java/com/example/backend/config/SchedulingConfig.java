package com.example.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 주기 작업 활성화 (@Scheduled). 지금은 멈춘 에이전트 실행 정리(AgentRunSweeper) */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
