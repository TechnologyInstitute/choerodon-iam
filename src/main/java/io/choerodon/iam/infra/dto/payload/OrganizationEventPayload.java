package io.choerodon.iam.infra.dto.payload;

/**
 * @author wuguokai
 */
public class OrganizationEventPayload {

    private Long organizationId;

    public Long getOrganizationId() {
        return organizationId;
    }

    public void setOrganizationId(Long organizationId) {
        this.organizationId = organizationId;
    }
}
