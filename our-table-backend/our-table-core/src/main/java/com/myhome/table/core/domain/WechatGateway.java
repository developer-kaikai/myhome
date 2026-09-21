package com.myhome.table.core.domain;

public interface WechatGateway {
  record Identity(String openid, String unionid) {}

  Identity exchange(String code);
}
