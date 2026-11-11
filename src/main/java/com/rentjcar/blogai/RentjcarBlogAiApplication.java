package com.rentjcar.blogai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.TimeZone;

@SpringBootApplication
public class RentjcarBlogAiApplication {

    public static void main(String[] args) {
        // 서버가 해외에 있어도 날짜·시간은 한국 기준
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
        SpringApplication.run(RentjcarBlogAiApplication.class, args);
    }

}
