package com.yizhaoqi.docmind.model;

/** Transactional Outbox 事件的投递状态。 */
public enum OutboxStatus {
    PENDING,
    PUBLISHING,
    PUBLISHED,
    DEAD
}
