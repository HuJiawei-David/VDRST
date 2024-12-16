package org.example.springboot_1.mapper;

import org.apache.ibatis.annotations.*;
import org.example.springboot_1.entity.User;
import java.util.List;

@Mapper
public interface userMapper {

    @Insert("insert into `user`(username, password, name, phone, email, address, avatar) " +
            "values(#{username}, #{password}, #{name}, #{phone}, #{email}, #{address}, #{avatar})")
    void insert(User user);

    @Update("update `user` set username = #{username}, password = #{password}, name = #{name}, phone = #{phone}," +
            " email = #{email}, address = #{address}, avatar = #{avatar} where id = #{id}")
    void updateUser(User user);

    @Delete("delete from `user` where id = #{id}")
    void deleteUser(Integer id);

    @Delete("<script>" +
            "delete from `user` where id in " +
            "<foreach collection='list' item='item' open='(' separator=',' close=')'>#{item}</foreach>" +
            "</script>")
    void batchDeleteUser(List<Integer> ids);

    @Select("select * from `user`")
    List<User> selectAll();

    @Select("select * from `user` where id = #{id}")
    User selectId(Integer id);

    @Select("select * from `user` where name = #{name}")
    List<User> selectName(String name);

    @Select("select * from `user` where username = #{username}")
    User selectByUserName(String username);
}
