package io.choerodon.iam.infra.mapper;

import io.choerodon.iam.infra.dto.CategoryMenuDTO;
import io.choerodon.iam.infra.dto.MenuCodeDTO;
import io.choerodon.mybatis.common.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * @author jiameng.cao
 * @date 2019/6/5
 */
public interface CategoryMenuMapper extends BaseMapper<CategoryMenuDTO> {

    List<CategoryMenuDTO> selectByCode(@Param("code") String code);

    List<String> getMenuCodesByOrgId(@Param("organizationId") Long organizationId, @Param("resourceLevel") String resourceLevel);

    List<MenuCodeDTO> selectPermissionCodeIdsByCode(@Param("code") String code, @Param("resourceLevel") String resourceLevel);

}