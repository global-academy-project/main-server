package com.example.backend.work.entity;

/**
 * 작업 안 역할 (DB ck_ww_role).
 * LEADER 작업 책임자 / WORKER 작업자 / FIRE_WATCHER 화재감시자 / ATTENDANT 밀폐공간 감시인 / SUPERVISOR 관리감독자
 */
public enum WorkRole {
    LEADER, WORKER, FIRE_WATCHER, ATTENDANT, SUPERVISOR
}
