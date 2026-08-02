package com.pisces.service.service;

import com.pisces.common.request.ApplicationSpaceUpsertRequest;
import com.pisces.common.response.ApplicationSpaceResponse;

import java.util.List;

/**
 * 应用空间服务
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/1 11:39
 */
public interface ApplicationSpaceService {

    /**
     * 查询当前身份可见的应用空间。
     *
     * @return 应用空间列表
     */
    List<ApplicationSpaceResponse> listApplicationSpaces();

    /**
     * 保存应用空间。
     *
     * @param appId 应用ID
     * @param request 应用空间保存请求
     * @return 应用空间
     */
    ApplicationSpaceResponse upsertApplicationSpace(String appId, ApplicationSpaceUpsertRequest request);
}
