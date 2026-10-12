package com.workflow.ai.aiconfig;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 大模型动态配置仓储（单行表：取最新一条即为当前生效配置）。
 */
public interface SysAiConfigRepository extends JpaRepository<SysAiConfig, Long> {

    /** 当前生效配置（表只允许存在一行；防御式取最新一条非删除记录）。 */
    SysAiConfig findTopByIsDeletedOrderByIdDesc(Integer isDeleted);
}
