package com.pisces.api.runtime;

import com.pisces.common.response.BaseResponse;
import com.pisces.common.response.RuntimeExperimentConfigResponse;
import com.pisces.common.response.RuntimeExperimentConfigVersionResponse;
import com.pisces.service.annotation.ApiKeyScopeRequired;
import com.pisces.service.annotation.NoTokenRequired;
import com.pisces.service.security.ApiKeyScope;
import com.pisces.service.service.RuntimeConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运行时配置控制器
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/1 11:49
 */
@RestController
@RequestMapping("/runtime/experiments")
@ApiKeyScopeRequired(ApiKeyScope.RUNTIME)
@NoTokenRequired
@RequiredArgsConstructor
public class RuntimeConfigController {

    private final RuntimeConfigService runtimeConfigService;

    /**
     * 查询 SDK 运行时实验配置。
     *
     * @param experimentId 实验ID
     * @return 运行时实验配置
     */
    @GetMapping("/{experimentId}/config")
    public BaseResponse<RuntimeExperimentConfigResponse> getExperimentConfig(@PathVariable String experimentId) {
        return BaseResponse.of(runtimeConfigService.getExperimentConfig(experimentId));
    }

    /**
     * 查询 SDK 运行时实验配置版本。
     *
     * @param experimentId 实验ID
     * @param knownVersion SDK 已知配置版本
     * @param waitMillis 最大等待毫秒数
     * @return 运行时实验配置版本
     */
    @GetMapping("/{experimentId}/config/version")
    public BaseResponse<RuntimeExperimentConfigVersionResponse> getExperimentConfigVersion(
            @PathVariable String experimentId,
            @RequestParam(value = "knownVersion", required = false) Long knownVersion,
            @RequestParam(value = "waitMillis", required = false) Long waitMillis) {
        return BaseResponse.of(runtimeConfigService.getExperimentConfigVersion(experimentId, knownVersion, waitMillis));
    }
}
