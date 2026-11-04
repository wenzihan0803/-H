package com.pw.common.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.BlockAttackInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;

/**
 * MyBatis-Plus 配置。
 *
 * <ul>
 *   <li>分页插件：MySQL 方言，单页上限 100（与 04 §1.2 一致）；</li>
 *   <li>攻击防护插件：拦截全表 update/delete，资金表误操作代价极高；</li>
 *   <li>审计字段自动填充：{@code createdAt} / {@code updatedAt}。</li>
 * </ul>
 */
@Configuration
public class MybatisPlusConfig {

    private static final long MAX_PAGE_SIZE = 100L;

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        pagination.setMaxLimit(MAX_PAGE_SIZE);
        // 超出总页数时返回空列表而不是回到首页，避免前端死循环
        pagination.setOverflow(false);
        interceptor.addInnerInterceptor(pagination);

        // 阻断没有 where 条件的 update/delete
        interceptor.addInnerInterceptor(new BlockAttackInnerInterceptor());

        return interceptor;
    }

    /**
     * 审计字段填充。数据库层已有 DEFAULT CURRENT_TIMESTAMP(3) 兜底，
     * 这里再填一次是为了让实体在 insert 后立即带上时间，便于同一事务内后续逻辑与响应装配。
     */
    @Bean
    public MetaObjectHandler metaObjectHandler() {
        return new MetaObjectHandler() {
            @Override
            public void insertFill(MetaObject metaObject) {
                LocalDateTime now = LocalDateTime.now();
                strictInsertFill(metaObject, "createdAt", LocalDateTime.class, now);
                strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, now);
            }

            @Override
            public void updateFill(MetaObject metaObject) {
                strictUpdateFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
            }
        };
    }
}
