package com.pw.file.service;

import com.pw.common.config.ExternalProperties;
import com.pw.common.exception.BizException;
import com.pw.common.exception.ErrorCode;
import com.pw.file.dto.FileDto;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 本地文件存储。只做两件事：把图片落到磁盘、按 objectKey 取回。
 *
 * <p><b>刻意保持简单</b>：不建文件表、不接对象存储、不做多版本。
 * objectKey 形如 {@code 2026/10/3f2a...c1.jpg}，随机 UUID 文件名不可枚举，
 * 业务表（如 {@code order_evidence}）只存这个 key。</p>
 *
 * <p>安全约束：只接受 JPG/PNG/WEBP（按文件头判断，不信任客户端 Content-Type），
 * 单文件上限取 {@code pw.file.max-size-mb}；下载时再次校验 key 形状并限制在根目录内，
 * 防止 {@code ../} 穿越。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileStorageService {

    /** objectKey 白名单形状：yyyy/MM/32 位十六进制.扩展名 */
    private static final Pattern OBJECT_KEY =
            Pattern.compile("^\\d{4}/\\d{2}/[0-9a-f]{32}\\.(jpg|png|webp)$");
    private static final DateTimeFormatter DIR = DateTimeFormatter.ofPattern("yyyy/MM");

    private final ExternalProperties.FileStorage props;

    private Path root;

    @PostConstruct
    void init() {
        if (!props.isLocal()) {
            // 本期只实现本地存储；配置成 cos 时直接启动失败，避免静默写丢文件
            throw new IllegalStateException("pw.file.storage 仅支持 local，当前为 " + props.getStorage());
        }
        root = Paths.get(props.getLocalDir()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建上传目录 " + root, e);
        }
        log.info("文件本地存储目录：{}", root);
    }

    /** 保存图片，返回 objectKey / url / size / hash。 */
    public FileDto.UploadResult store(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_ERROR, "请选择要上传的图片");
        }
        long maxBytes = Math.max(1L, props.getMaxSizeMb()) * 1024L * 1024L;
        if (file.getSize() > maxBytes) {
            throw BizException.of(ErrorCode.PARAM_ERROR,
                    "图片不能超过 " + props.getMaxSizeMb() + "MB，请压缩后重试");
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw BizException.of(ErrorCode.SYSTEM_ERROR, "读取上传文件失败，请重试");
        }

        String ext = detectExtension(bytes);
        if (ext == null) {
            throw BizException.of(ErrorCode.PARAM_ERROR, "仅支持 JPG / PNG / WEBP 格式的图片");
        }

        String objectKey = LocalDate.now().format(DIR) + "/"
                + UUID.randomUUID().toString().replace("-", "") + "." + ext;
        Path target = resolve(objectKey);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
        } catch (IOException e) {
            log.error("写入上传文件失败 key={}", objectKey, e);
            throw BizException.of(ErrorCode.SYSTEM_ERROR, "图片保存失败，请重试");
        }

        return FileDto.UploadResult.builder()
                .objectKey(objectKey)
                .url(urlOf(objectKey))
                .size((long) bytes.length)
                .hash(sha256(bytes))
                .contentType(contentTypeOf(objectKey))
                .build();
    }

    /** 文件是否存在（履约凭证提交前校验，防止提交一个不存在的 key）。 */
    public boolean exists(String objectKey) {
        if (objectKey == null || !OBJECT_KEY.matcher(objectKey).matches()) {
            return false;
        }
        return Files.isRegularFile(root.resolve(objectKey).normalize());
    }

    /** 文件字节数；不存在返回 0。 */
    public long sizeOf(String objectKey) {
        if (!exists(objectKey)) {
            return 0L;
        }
        try {
            return Files.size(root.resolve(objectKey).normalize());
        } catch (IOException e) {
            return 0L;
        }
    }

    /** 文件内容 SHA-256；不存在返回 null。 */
    public String hashOf(String objectKey) {
        if (!exists(objectKey)) {
            return null;
        }
        try {
            return sha256(Files.readAllBytes(root.resolve(objectKey).normalize()));
        } catch (IOException e) {
            return null;
        }
    }

    /** 下载用资源；key 非法或越界抛 404。 */
    public Resource resource(String objectKey) {
        Path path = resolve(objectKey);
        if (!Files.isRegularFile(path)) {
            throw BizException.of(ErrorCode.NOT_FOUND, "文件不存在或已失效");
        }
        return new FileSystemResource(path);
    }

    public String contentTypeOf(String objectKey) {
        String key = objectKey == null ? "" : objectKey.toLowerCase(Locale.ROOT);
        if (key.endsWith(".png")) {
            return "image/png";
        }
        if (key.endsWith(".webp")) {
            return "image/webp";
        }
        return "image/jpeg";
    }

    /**
     * objectKey → 可直接访问的绝对地址。
     *
     * <p>优先用当前请求的域名/端口拼接：真机通过局域网 IP 访问后端时，
     * 生成的地址就是局域网 IP，不会像写死 {@code localhost} 那样在手机上打不开。
     * 没有请求上下文（定时任务等）时回退到 {@code pw.file.public-base-url}。</p>
     */
    public String urlOf(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return null;
        }
        String base;
        try {
            base = ServletUriComponentsBuilder.fromCurrentContextPath()
                    .path("/files/download/").toUriString();
        } catch (RuntimeException ex) {
            String configured = props.getPublicBaseUrl();
            base = (configured.endsWith("/") ? configured : configured + "/") + "download/";
        }
        return base + objectKey;
    }

    private Path resolve(String objectKey) {
        if (objectKey == null || !OBJECT_KEY.matcher(objectKey).matches()) {
            throw BizException.of(ErrorCode.NOT_FOUND, "文件不存在或已失效");
        }
        Path path = root.resolve(objectKey).normalize();
        if (!path.startsWith(root)) {
            throw BizException.of(ErrorCode.NOT_FOUND, "文件不存在或已失效");
        }
        return path;
    }

    /** 按文件头判断真实类型；只认三种图片。 */
    private static String detectExtension(byte[] b) {
        if (b.length >= 3
                && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "jpg";
        }
        if (b.length >= 8
                && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return "png";
        }
        if (b.length >= 12
                && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "webp";
        }
        return null;
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
