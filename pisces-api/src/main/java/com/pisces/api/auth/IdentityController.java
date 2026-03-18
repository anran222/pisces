package com.pisces.api.auth;

import com.pisces.common.request.IdentityBindRequest;
import com.pisces.common.response.BaseResponse;
import com.pisces.service.annotation.NoTokenRequired;
import com.pisces.service.service.IdentityService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 访客身份控制器
 */
@RestController
@RequestMapping("/identity")
@NoTokenRequired
@RequiredArgsConstructor
public class IdentityController {

    private final IdentityService identityService;

    /**
     * 绑定匿名设备ID和登录用户ID
     *
     * @param request 绑定请求
     * @return 绑定结果
     */
    @PostMapping("/bind")
    public BaseResponse<Map<String, String>> bindUserId(@Valid @RequestBody IdentityBindRequest request) {
        identityService.bindUserId(request.getDeviceId(), request.getUserId());
        return BaseResponse.of("身份绑定成功", Map.of(
                "deviceId", request.getDeviceId(),
                "userId", request.getUserId()
        ));
    }

    /**
     * 解析规范访客ID
     *
     * @param visitorId 原始访客ID
     * @return 规范访客ID
     */
    @GetMapping("/resolve")
    public BaseResponse<Map<String, String>> resolveCanonicalId(@RequestParam("visitorId") String visitorId) {
        String canonicalVisitorId = identityService.resolveCanonicalId(visitorId);
        return BaseResponse.of(Map.of(
                "visitorId", visitorId,
                "canonicalVisitorId", canonicalVisitorId
        ));
    }
}
