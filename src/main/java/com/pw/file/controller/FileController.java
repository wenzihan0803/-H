package com.pw.file.controller;

import com.pw.common.result.Result;
import com.pw.file.dto.FileDto;
import com.pw.file.service.FileStorageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;

/**
 * 文件上传与下载。
 *
 * <p>上传需登录（默认安全策略），下载匿名可访问——objectKey 是不可枚举的 UUID，
 * 等价于一条「知道地址就能看」的图片链接。本期不做签名 URL，保持简单；
 * 后续接对象存储时只需替换 {@link FileStorageService}。</p>
 */
@Tag(name = "10-文件", description = "图片上传与读取")
@RestController
@RequestMapping("/files")
@RequiredArgsConstructor
public class FileController {

    private final FileStorageService storage;

    @Operation(summary = "上传图片", description = "JPG/PNG/WEBP，≤10MB；返回 objectKey 与可直接展示的 url")
    @PostMapping("/upload")
    public Result<FileDto.UploadResult> upload(@RequestParam("file") MultipartFile file) {
        return Result.ok(storage.store(file));
    }

    @Operation(summary = "读取图片", description = "按 objectKey 返回原图，匿名可访问")
    @GetMapping("/download/{*objectKey}")
    public ResponseEntity<Resource> download(@PathVariable String objectKey) {
        String key = objectKey.startsWith("/") ? objectKey.substring(1) : objectKey;
        Resource resource = storage.resource(key);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(storage.contentTypeOf(key)))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic())
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .body(resource);
    }
}
