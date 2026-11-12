package com.example.backend;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class BackendApplication {

    public static void main(String[] args) {
        // JVM 기본 시간대를 Clock(Asia/Seoul)과 맞춘다. PC·Docker가 UTC여도 저장 시각과 정리 기준이 어긋나지 않게
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
        SpringApplication.run(BackendApplication.class, args);
    }

}