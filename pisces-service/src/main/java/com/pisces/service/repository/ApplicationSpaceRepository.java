package com.pisces.service.repository;

import com.pisces.common.model.ApplicationSpace;

import java.util.List;
import java.util.Optional;

/**
 * 应用空间仓库
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/1 12:10
 */
public interface ApplicationSpaceRepository {

    ApplicationSpace save(ApplicationSpace applicationSpace);

    Optional<ApplicationSpace> findByAppId(String appId);

    List<ApplicationSpace> findAll();
}
