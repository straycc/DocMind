package com.yizhaoqi.docmind.controller;

import com.yizhaoqi.docmind.dto.MultipartUploadDtos.CompleteResponse;
import com.yizhaoqi.docmind.dto.MultipartUploadDtos.InitRequest;
import com.yizhaoqi.docmind.service.MultipartUploadService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/v1/upload/multipart")
public class MultipartUploadController {
    private final MultipartUploadService multipartUploadService;

    public MultipartUploadController(MultipartUploadService multipartUploadService) {
        this.multipartUploadService = multipartUploadService;
    }

    @PostMapping("/init")
    public ResponseEntity<Map<String, Object>> initiate(@Valid @RequestBody InitRequest request,
                                                        @RequestAttribute("userId") String userId) {
        return execute(() -> multipartUploadService.initiate(request, userId), "创建上传任务成功");
    }

    @PostMapping("/{fileUploadId}/parts/{partNumber}/presign")
    public ResponseEntity<Map<String, Object>> presign(@PathVariable long fileUploadId,
                                                       @PathVariable int partNumber,
                                                       @RequestAttribute("userId") String userId) {
        return execute(() -> multipartUploadService.presignPart(fileUploadId, partNumber, userId),
                "生成分片上传地址成功");
    }

    @GetMapping("/{fileUploadId}/status")
    public ResponseEntity<Map<String, Object>> status(@PathVariable long fileUploadId,
                                                      @RequestAttribute("userId") String userId) {
        return execute(() -> multipartUploadService.status(fileUploadId, userId), "查询上传状态成功");
    }

    @PostMapping("/{fileUploadId}/complete")
    public ResponseEntity<Map<String, Object>> complete(@PathVariable long fileUploadId,
                                                        @RequestAttribute("userId") String userId) {
        try {
            CompleteResponse result = multipartUploadService.complete(fileUploadId, userId);
            return ResponseEntity.ok(envelope(200, "文件上传完成，处理任务已进入可靠投递队列", result));
        } catch (Exception e) {
            return error(e);
        }
    }

    @DeleteMapping("/{fileUploadId}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable long fileUploadId,
                                                      @RequestAttribute("userId") String userId) {
        return execute(() -> {
            multipartUploadService.deleteIncompleteUpload(fileUploadId, userId);
            return Map.of("fileUploadId", fileUploadId, "status", "DELETED");
        }, "上传任务已彻底删除");
    }

    private <T> ResponseEntity<Map<String, Object>> execute(CheckedSupplier<T> supplier, String message) {
        try {
            return ResponseEntity.ok(envelope(200, message, supplier.get()));
        } catch (Exception e) {
            return error(e);
        }
    }

    private ResponseEntity<Map<String, Object>> error(Exception exception) {
        HttpStatus status;
        if (exception instanceof NoSuchElementException) {
            status = HttpStatus.NOT_FOUND;
        } else if (exception instanceof IllegalArgumentException || exception instanceof IllegalStateException) {
            status = HttpStatus.BAD_REQUEST;
        } else {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        return ResponseEntity.status(status)
                .body(envelope(status.value(), exception.getMessage(), null));
    }

    private Map<String, Object> envelope(int code, String message, Object data) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("code", code);
        response.put("message", message);
        response.put("data", data);
        return response;
    }

    @FunctionalInterface
    private interface CheckedSupplier<T> {
        T get() throws Exception;
    }
}
