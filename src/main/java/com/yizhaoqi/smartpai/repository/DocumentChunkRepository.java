package com.yizhaoqi.smartpai.repository;

import com.yizhaoqi.smartpai.model.DocumentChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, Long> {
    List<DocumentChunk> findByFileUploadIdOrderByOrdinalAsc(Long fileUploadId);

    boolean existsByFileUploadId(Long fileUploadId);

    List<DocumentChunk> findBySectionIdAndOrdinalBetweenOrderByOrdinalAsc(Long sectionId, Integer start, Integer end);

    @Transactional
    @Modifying
    @Query("delete from DocumentChunk chunk where chunk.fileUploadId = :fileUploadId")
    void deleteByFileUploadId(@Param("fileUploadId") Long fileUploadId);
}
