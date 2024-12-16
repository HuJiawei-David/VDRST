
package org.example.springboot_1.service;

import org.example.springboot_1.entity.User;
import org.example.springboot_1.exception.ServiceException;
import org.example.springboot_1.mapper.userMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class UserService {

    @Autowired
    private userMapper userMapper;

    public User login(User user) {
        User dbUser = userMapper.selectByUserName(user.getUsername());
        if (dbUser == null) {
            throw new ServiceException("404", "User not found.");
        }
        if (!dbUser.getPassword().equals(user.getPassword())) {
            throw new ServiceException("401", "Incorrect password.");
        }
        return dbUser;
    }

    public void resetPassword(String username, String phone) {
        User user = userMapper.selectByUserName(username);
        if (user == null) {
            throw new ServiceException("404", "User not found.");
        }
        if (!phone.equals(user.getPhone())) {
            throw new ServiceException("400", "输入错误");
        }
        user.setPassword("123");
        userMapper.updateUser(user);
    }


    public void register(User user) {
        User existingUser = userMapper.selectByUserName(user.getUsername());
        if (existingUser != null) {
            throw new ServiceException("409", "Username already exists.");
        }
        userMapper.insert(user);
    }

    public void insertUser(User user){
        userMapper.insert(user);
    }

    public void updateUser(User user){
        userMapper.updateUser(user);
    }

    public void deleteUser(Integer id){
        userMapper.deleteUser(id);
    }

    public void batchDeleteUser(List<Integer> ids){
        userMapper.batchDeleteUser(ids);
    }

    public List<User> selectAll(){
        return userMapper.selectAll();
    }

    public User selectId(Integer id){
        return userMapper.selectId(id);
    }

    public List<User> selectName(String name){
        return userMapper.selectName(name);
    }
}

