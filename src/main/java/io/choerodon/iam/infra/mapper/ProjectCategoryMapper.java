package io.choerodon.iam.infra.mapper;


import io.choerodon.iam.infra.dto.ProjectCategoryDTO;
import io.choerodon.mybatis.common.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Set;

/**
 * @author jiameng.cao
 * @since 2019/6/4
 */
public interface ProjectCategoryMapper extends BaseMapper<ProjectCategoryDTO> {


    List<ProjectCategoryDTO> selectProjectCategoriesByOrgId(@Param("organizationId") Long organizationId, @Param("param") String param, @Param("projectCategoryDTO") ProjectCategoryDTO projectCategoryDTO);

    List<ProjectCategoryDTO> selectProjectCategoriesListByOrgId(@Param("organizationId") Long organizationId,
                                                                @Param("param") String param);

    List<ProjectCategoryDTO> selectByParam(@Param("param") String param, @Param("projectCategoryDTO") ProjectCategoryDTO projectCategoryDTO);

    Long getIdByCode(@Param("agile") String agile);

    /**
     * 模糊查询projectType
     *
     * @param name
     * @param code
     * @param param
     * @return ProjectCategoryDTO列表
     */
    List<ProjectCategoryDTO> fuzzyQuery(@Param("name") String name,
                                        @Param("code") String code,
                                        @Param("param") String param);

    List<Long> ListIdByCodes(@Param("codes") Set<String> codes);
}
