package com.pw.common.result;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 通用分页查询参数。
 *
 * <p>口径来源：技术规范/04 §1.2 —— page 默认 1，pageSize 默认 20、最大 100。</p>
 */
@Data
@Schema(description = "分页查询参数")
public class PageQuery {

    @Min(value = 1, message = "页码最小为 1")
    @Schema(description = "页码，从 1 开始", defaultValue = "1", example = "1")
    private Integer page = 1;

    @Min(value = 1, message = "每页条数最小为 1")
    @Max(value = 100, message = "每页条数最大为 100，请缩小范围后重试")
    @Schema(description = "每页条数，最大 100", defaultValue = "20", example = "20")
    private Integer pageSize = 20;

    public long getPageOrDefault() {
        return page == null || page < 1 ? 1L : page;
    }

    public long getPageSizeOrDefault() {
        if (pageSize == null || pageSize < 1) {
            return 20L;
        }
        return Math.min(pageSize, 100L);
    }

    /** 构造 MyBatis-Plus 分页对象。 */
    public <T> Page<T> toPage() {
        return Page.of(getPageOrDefault(), getPageSizeOrDefault());
    }
}
