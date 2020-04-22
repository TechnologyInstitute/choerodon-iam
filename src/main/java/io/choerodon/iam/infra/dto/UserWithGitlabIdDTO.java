package io.choerodon.iam.infra.dto;

import io.swagger.annotations.ApiModelProperty;
import org.hzero.iam.domain.entity.User;

/**
 * @author zmf
 * @since 20-3-18
 */
public class UserWithGitlabIdDTO extends User {
    @ApiModelProperty("用户对应的gitlab用户id")
    private Long gitlabUserId;

    public Long getGitlabUserId() {
        return gitlabUserId;
    }

    public void setGitlabUserId(Long gitlabUserId) {
        this.gitlabUserId = gitlabUserId;
    }
}
