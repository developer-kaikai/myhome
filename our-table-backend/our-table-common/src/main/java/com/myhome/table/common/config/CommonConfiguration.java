package com.myhome.table.common.config;

import java.time.Clock;
import org.springframework.context.annotation.*;

@Configuration
public class CommonConfiguration {
  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }
}
