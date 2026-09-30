package com.yizhaoqi.docmind.service;

import java.nio.file.Path;

/** 文档解析器的统一边界；后续 Docling、MinerU 只需实现此接口。 */
public interface DocumentParser {
    StructuredDocumentParser.ParseResult parse(Path path) throws Exception;
}
