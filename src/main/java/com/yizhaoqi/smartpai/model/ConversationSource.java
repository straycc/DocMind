package com.yizhaoqi.smartpai.model;

/** 回答生成时使用的来源快照；sourceId 只在所属消息内有效。 */
public record ConversationSource(
        int sourceId,
        Long fileUploadId,
        Integer chunkOrdinal,
        String fileName,
        String titlePath,
        Integer pageStart,
        Integer pageEnd,
        String sourceLabel,
        String excerpt
) {
}
