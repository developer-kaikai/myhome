package com.myhome.table.core.infrastructure;

import com.myhome.table.common.entity.AppUser;
import org.apache.ibatis.annotations.*;

@Mapper
public interface UserMapper {
  @Select("SELECT * FROM app_user WHERE id=#{id}")
  AppUser find(Long id);

  @Select("SELECT * FROM app_user WHERE openid=#{openid}")
  AppUser findByOpenid(String openid);

  @Select("SELECT id FROM app_user WHERE id=#{id} FOR UPDATE")
  Long lock(Long id);

  @Insert(
      "INSERT INTO app_user(openid,unionid,nickname) VALUES(#{openid},#{unionid},#{nickname}) ON DUPLICATE KEY UPDATE id=id")
  void register(String openid, String unionid, String nickname);

  @Update(
      "UPDATE app_user SET nickname=#{nickname},version=version+1 WHERE id=#{id} AND version=#{version} AND status='ACTIVE'")
  int updateNickname(Long id, String nickname, Long version);
}
