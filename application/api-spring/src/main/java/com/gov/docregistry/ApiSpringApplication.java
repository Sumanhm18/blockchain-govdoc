package com.gov.docregistry;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@org.springframework.scheduling.annotation.EnableScheduling
@SpringBootApplication
public class ApiSpringApplication {
  public static void main(String[] args) {
    SpringApplication.run(ApiSpringApplication.class, args);
  }
}
