
package org.example.springboot_1.controller;

import org.example.springboot_1.common.Result;
import org.example.springboot_1.entity.User;
import org.example.springboot_1.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@CrossOrigin
@RestController
@RequestMapping("/user")
public class UserController {

    @Autowired
    UserService userService;

    @PostMapping("/add")
    public Result add(@RequestBody User user){
        try {
            userService.insertUser(user);
        } catch (Exception e){
            if (e instanceof DuplicateKeyException) {
                return Result.error("主键重复异常");
            } else {
                return Result.error("系统错误");
            }
        }
        return Result.success();
    }

    @PutMapping("/update")
    public Result update(@RequestBody User user){
        userService.updateUser(user);
        return Result.success("更新成功");
    }

    @DeleteMapping("/delete/{id}")
    public Result delete(@PathVariable Integer id){
        userService.deleteUser(id);
        return Result.success();
    }

    @DeleteMapping("/delete/batch")
    public Result batchDelete(@RequestBody List<Integer> ids){
        userService.batchDeleteUser(ids);
        return Result.success();
    }

    @GetMapping("/select/all")
    public Result selectAll(){
        List<User> list = userService.selectAll();
        return Result.success(list);
    }

    @GetMapping("/selectId/{id}")
    public Result selectId(@PathVariable Integer id){
        User userid = userService.selectId(id);
        return Result.success(userid);
    }

    @GetMapping("/selectName/{name}")
    public Result selectName(@PathVariable String name){
        List<User> userName = userService.selectName(name);
        return Result.success(userName);
    }
}
