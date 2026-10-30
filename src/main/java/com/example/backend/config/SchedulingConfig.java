package com.example.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 주기 작업 활성화 (@Scheduled): 멈춘 에이전트 실행 정리(AgentRunSweeper), 마감 알림(DeadlineScheduler) */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
