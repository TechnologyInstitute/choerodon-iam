package io.choerodon.iam.app.task;

import org.hzero.iam.domain.entity.Tenant;
import org.hzero.iam.infra.mapper.TenantMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import io.choerodon.core.exception.CommonException;
import io.choerodon.iam.app.service.TenantC7nService;
import io.choerodon.iam.infra.constant.TenantConstants;

/**
 * 初始化默认组织
 */
@Component
public class DefaultTenantInitRunner implements CommandLineRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultTenantInitRunner.class);


    @Autowired
    private TenantMapper tenantMapper;
    @Autowired
    private TenantC7nService tenantC7nService;

    @Override
    public void run(String... strings) {
        try {
            Tenant tenant = tenantMapper.selectByPrimaryKey(TenantConstants.DEFAULT_C7N_TENANT_TD);
            if (tenant == null) {
                // 默认组织不存在则创建
                tenantC7nService.createDefaultTenant(TenantConstants.DEFAULT_TENANT_NAME, TenantConstants.DEFAULT_TENANT_NUM);
            }
        } catch (Exception e) {
            throw new CommonException("error.init.default.tenant", e);
        }

    }

}
