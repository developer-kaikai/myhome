package com.myhome.table.ordering;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"com.myhome.table.common", "com.myhome.table.ordering"})
public class OrderingApplication {
  public static void main(String[] args) {
    SpringApplication.run(OrderingApplication.class, args);
  }
}
