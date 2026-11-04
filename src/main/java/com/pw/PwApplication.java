package com.pw;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * 三角洲陪玩平台 · 后端启动类
 *
 * <p>单体应用、模块化分包。模块之间只通过 Service 调用，不直接访问对方 Mapper。</p>
 */
@SpringBootApplication
@EnableScheduling
@EnableAsync
@EnableTransactionManagement
@MapperScan("com.pw.**.mapper")
public class PwApplication {

    public static void main(String[] args) {
        SpringApplication.run(PwApplication.class, args);
    }
}
