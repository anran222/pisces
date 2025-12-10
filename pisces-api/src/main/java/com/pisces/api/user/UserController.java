package com.pisces.api.user;

import com.pisces.common.request.UserCreateRequest;
import com.pisces.common.request.UserQueryRequest;
import com.pisces.common.response.BaseResponse;
import com.pisces.common.response.UserResponse;
import com.pisces.service.service.UserService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 用户控制器
 */
@RestController
@RequestMapping("/users")
public class UserController {
    
    @Autowired
    private UserService userService;
    
    /**
     * 创建用户
     */
    @PostMapping
    public BaseResponse<UserResponse> createUser(@Valid @RequestBody UserCreateRequest request) {
        UserResponse user = userService.createUser(request);
        return BaseResponse.of("用户创建成功", user);
    }
    
    /**
     * 根据ID查询用户
     */
    @GetMapping("/{id}")
    public BaseResponse<UserResponse> getUserById(@PathVariable Long id) {
        UserResponse user = userService.getUserById(id);
        return BaseResponse.of(user);
    }
    
    /**
     * 查询用户列表
     */
    @GetMapping
    public BaseResponse<List<UserResponse>> queryUsers(@Valid UserQueryRequest request) {
        List<UserResponse> users = userService.queryUsers(request);
        return BaseResponse.of(users);
    }
    
    /**
     * 更新用户
     */
    @PutMapping("/{id}")
    public BaseResponse<UserResponse> updateUser(@PathVariable Long id, 
                                                 @Valid @RequestBody UserCreateRequest request) {
        UserResponse user = userService.updateUser(id, request);
        return BaseResponse.of("用户更新成功", user);
    }
    
    /**
     * 删除用户
     */
    @DeleteMapping("/{id}")
    public BaseResponse<Void> deleteUser(@PathVariable Long id) {
        userService.deleteUser(id);
        return BaseResponse.of("用户删除成功", null);
    }
}

