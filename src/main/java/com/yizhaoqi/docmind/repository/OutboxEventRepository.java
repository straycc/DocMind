package com.yizhaoqi.docmind.repository;

import com.yizhaoqi.docmind.model.OutboxEvent;
import com.yizhaoqi.docmind.model.OutboxStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {
    Optional<OutboxEvent> findByDedupKey(String dedupKey);

    boolean existsByDedupKey(String dedupKey);

    @Query("""
            SELECT e.id FROM OutboxEvent e
            WHERE (e.status = :pending AND (e.nextRetryAt IS NULL OR e.nextRetryAt <= :now))
               OR (e.status = :publishing AND e.lockedUntil < :now)
            ORDER BY e.createdAt ASC, e.id ASC
            """)
    List<Long> findDispatchCandidateIds(@Param("pending") OutboxStatus pending,
                                        @Param("publishing") OutboxStatus publishing,
                                        @Param("now") LocalDateTime now,
                                        Pageable pageable);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE OutboxEvent e
               SET e.status = :publishing,
                   e.lockToken = :lockToken,
                   e.lockedUntil = :lockedUntil,
                   e.updatedAt = :now
             WHERE e.id = :id
               AND ((e.status = :pending AND (e.nextRetryAt IS NULL OR e.nextRetryAt <= :now))
                 OR (e.status = :publishing AND e.lockedUntil < :now))
            """)
    int claim(@Param("id") Long id,
              @Param("pending") OutboxStatus pending,
              @Param("publishing") OutboxStatus publishing,
              @Param("lockToken") String lockToken,
              @Param("lockedUntil") LocalDateTime lockedUntil,
              @Param("now") LocalDateTime now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE OutboxEvent e
               SET e.status = :published,
                   e.publishedAt = :now,
                   e.lockToken = NULL,
                   e.lockedUntil = NULL,
                   e.lastError = NULL,
                   e.updatedAt = :now
             WHERE e.id = :id
               AND e.status = :publishing
               AND e.lockToken = :lockToken
            """)
    int markPublished(@Param("id") Long id,
                      @Param("lockToken") String lockToken,
                      @Param("publishing") OutboxStatus publishing,
                      @Param("published") OutboxStatus published,
                      @Param("now") LocalDateTime now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE OutboxEvent e
               SET e.status = :pending,
                   e.retryCount = :retryCount,
                   e.nextRetryAt = :nextRetryAt,
                   e.lockToken = NULL,
                   e.lockedUntil = NULL,
                   e.lastError = :lastError,
                   e.updatedAt = :now
             WHERE e.id = :id
               AND e.status = :publishing
               AND e.lockToken = :lockToken
            """)
    int reschedule(@Param("id") Long id,
                   @Param("lockToken") String lockToken,
                   @Param("publishing") OutboxStatus publishing,
                   @Param("pending") OutboxStatus pending,
                   @Param("retryCount") int retryCount,
                   @Param("nextRetryAt") LocalDateTime nextRetryAt,
                   @Param("lastError") String lastError,
                   @Param("now") LocalDateTime now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE OutboxEvent e
               SET e.status = :dead,
                   e.retryCount = :retryCount,
                   e.lockToken = NULL,
                   e.lockedUntil = NULL,
                   e.lastError = :lastError,
                   e.updatedAt = :now
             WHERE e.id = :id
               AND e.status = :publishing
               AND e.lockToken = :lockToken
            """)
    int markDead(@Param("id") Long id,
                 @Param("lockToken") String lockToken,
                 @Param("publishing") OutboxStatus publishing,
                 @Param("dead") OutboxStatus dead,
                 @Param("retryCount") int retryCount,
                 @Param("lastError") String lastError,
                 @Param("now") LocalDateTime now);
}
