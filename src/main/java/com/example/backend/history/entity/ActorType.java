package com.example.backend.history.entity;

/** 행위 주체. USER = users.id, AGENT = agent_runs.id, SYSTEM = id 없음 (배치·스케줄러). */
public enum ActorType {
    USER, AGENT, SYSTEM
}
