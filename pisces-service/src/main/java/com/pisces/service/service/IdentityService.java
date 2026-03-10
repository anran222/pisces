package com.pisces.service.service;

/**
 * 访客身份管理服务
 *
 * <p>解决"匿名→登录"的 ID 拼接问题：用户在匿名状态下以 deviceId 参与实验，
 * 登录后将 deviceId 映射到 userId，后续统一使用 userId 进行数据查询和统计。</p>
 *
 * <p>典型调用时机：
 * <ul>
 *   <li>App/Web 初始化：{@link #getOrCreateVisitorId} 获取或创建匿名访客 ID</li>
 *   <li>用户登录成功后：{@link #bindUserId} 将匿名 ID 与 userId 绑定</li>
 *   <li>TrafficService/DataService 调用：{@link #resolveCanonicalId} 解析规范 ID</li>
 * </ul>
 * </p>
 */
public interface IdentityService {

    /**
     * 获取或创建匿名访客 ID（幂等）
     * <p>若 deviceId 已有对应 visitorId 则直接返回；否则以 deviceId 为 visitorId 并保存映射。</p>
     *
     * @param deviceId 设备唯一标识（客户端自行生成并持久化）
     * @return 访客 ID（首次为 deviceId，绑定后为 userId）
     */
    String getOrCreateVisitorId(String deviceId);

    /**
     * 将 deviceId 绑定到已登录的 userId
     * <p>绑定后，{@link #resolveCanonicalId} 将始终返回 userId；
     * 历史事件数据不回溯，但后续分配和上报均使用 userId。</p>
     *
     * @param deviceId 匿名设备 ID
     * @param userId   已登录用户 ID
     */
    void bindUserId(String deviceId, String userId);

    /**
     * 解析规范 ID
     * <p>若 visitorId 已绑定 userId，则返回 userId；否则原样返回。</p>
     *
     * @param visitorId 原始访客 ID（deviceId 或 userId）
     * @return 规范 ID
     */
    String resolveCanonicalId(String visitorId);

    /**
     * 解除 deviceId 与 userId 的绑定（用于账号注销场景）
     *
     * @param deviceId 设备 ID
     */
    void unbind(String deviceId);
}
