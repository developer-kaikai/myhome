package com.myhome.table.party;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"com.myhome.table.common", "com.myhome.table.party"})
public class PartyApplication {
  public static void main(String[] args) {
    SpringApplication.run(PartyApplication.class, args);
  }
}
