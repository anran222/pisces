package com.pisces.api.application;

import com.pisces.common.request.ApplicationSpaceUpsertRequest;
import com.pisces.common.response.ApplicationDictionaryResponse;
import com.pisces.common.response.ApplicationSpaceResponse;
import com.pisces.common.response.BaseResponse;
import com.pisces.service.annotation.ApiKeyScopeRequired;
import com.pisces.service.annotation.NoTokenRequired;
import com.pisces.service.security.ApiKeyScope;
import com.pisces.service.service.ApplicationDictionaryService;
import com.pisces.service.service.ApplicationSpaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 应用空间控制器
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/1 11:39
 */
@RestController
@RequestMapping("/applications")
@ApiKeyScopeRequired(ApiKeyScope.MANAGEMENT)
@NoTokenRequired
@RequiredArgsConstructor
public class ApplicationSpaceController {

    private final ApplicationSpaceService applicationSpaceService;

    private final ApplicationDictionaryService applicationDictionaryService;

    /**
     * 查询应用空间列表。
     *
     * @return 应用空间列表
     */
    @GetMapping
    public BaseResponse<List<ApplicationSpaceResponse>> listApplicationSpaces() {
        return BaseResponse.of(applicationSpaceService.listApplicationSpaces());
    }

    /**
     * 保存应用空间。
     *
     * @param appId 应用ID
     * @param request 应用空间保存请求
     * @return 应用空间
     */
    @PutMapping("/{appId}")
    public BaseResponse<ApplicationSpaceResponse> upsertApplicationSpace(
            @PathVariable String appId,
            @RequestBody ApplicationSpaceUpsertRequest request) {
        return BaseResponse.of(applicationSpaceService.upsertApplicationSpace(appId, request));
    }

    /**
     * 查询应用事件和指标字典。
     *
     * @param appId 应用ID
     * @return 应用字典
     */
    @GetMapping("/{appId}/dictionary")
    public BaseResponse<ApplicationDictionaryResponse> getApplicationDictionary(@PathVariable String appId) {
        return BaseResponse.of(applicationDictionaryService.getApplicationDictionary(appId));
    }
}
