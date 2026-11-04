package com.pw.common.result;

import com.baomidou.mybatisplus.core.metadata.IPage;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/**
 * 分页响应。
 *
 * <p>口径来源：技术规范/04 §1.1 {@code interface Page<T>}</p>
 *
 * @param <T> 列表元素类型（VO，不返回 Entity）
 */
@Getter
@Schema(description = "分页数据")
public class PageResult<T> {

    @Schema(description = "当前页数据")
    private final List<T> list;

    @Schema(description = "总条数")
    private final long total;

    @Schema(description = "当前页码，从 1 开始")
    private final long page;

    @Schema(description = "每页条数")
    private final long pageSize;

    public PageResult(List<T> list, long total, long page, long pageSize) {
        this.list = list == null ? Collections.emptyList() : list;
        this.total = total;
        this.page = page;
        this.pageSize = pageSize;
    }

    public static <T> PageResult<T> of(List<T> list, long total, long page, long pageSize) {
        return new PageResult<>(list, total, page, pageSize);
    }

    public static <T> PageResult<T> empty(long page, long pageSize) {
        return new PageResult<>(new ArrayList<>(), 0L, page, pageSize);
    }

    /** MyBatis-Plus 分页对象 + Entity→VO 转换。 */
    public static <E, T> PageResult<T> from(IPage<E> source, Function<E, T> converter) {
        List<T> list = source.getRecords().stream().map(converter).toList();
        return new PageResult<>(list, source.getTotal(), source.getCurrent(), source.getSize());
    }

    /** 仅包装分页元信息，列表由调用方另行装配（如需批量查询关联数据）。 */
    public static <E, T> PageResult<T> from(IPage<E> source, List<T> convertedList) {
        return new PageResult<>(convertedList, source.getTotal(), source.getCurrent(), source.getSize());
    }
}
