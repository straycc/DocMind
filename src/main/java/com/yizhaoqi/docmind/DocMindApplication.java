package com.yizhaoqi.docmind;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class DocMindApplication {

    public static void main(String[] args) {
        SpringApplication.run(DocMindApplication.class, args);
    }

}
