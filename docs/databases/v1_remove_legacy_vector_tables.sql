-- 已完成 document_chunks / knowledge_base_v1 验收后的历史表清理。
-- 执行后不可恢复；运行前如需保留历史数据，请自行备份。
DROP TABLE IF EXISTS document_vectors;
DROP TABLE IF EXISTS test_entity;
