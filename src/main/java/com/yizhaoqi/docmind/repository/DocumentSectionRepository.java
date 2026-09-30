package com.yizhaoqi.docmind.repository;

import com.yizhaoqi.docmind.model.DocumentSection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface DocumentSectionRepository extends JpaRepository<DocumentSection, Long> {
    List<DocumentSection> findByFileUploadIdOrderByOrdinalAsc(Long fileUploadId);

    @Transactional
    @Modifying
    @Query("delete from DocumentSection section where section.fileUploadId = :fileUploadId")
    void deleteByFileUploadId(@Param("fileUploadId") Long fileUploadId);
}
