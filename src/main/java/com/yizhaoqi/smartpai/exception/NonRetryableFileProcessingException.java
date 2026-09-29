package com.yizhaoqi.smartpai.exception;

/**
 * 标记不可通过重复消费恢复的文件处理错误。
 * Kafka 遇到此异常会直接投递 DLT，不进行阻塞重试。
 */
public class NonRetryableFileProcessingException extends RuntimeException {

    public NonRetryableFileProcessingException(String message) {
        super(message);
    }

    public NonRetryableFileProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}
