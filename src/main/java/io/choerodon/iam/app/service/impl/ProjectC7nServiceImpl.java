package io.choerodon.iam.app.service.impl;

import static io.choerodon.iam.infra.utils.SagaTopic.Project.ADD_PROJECT_CATEGORY;
import static io.choerodon.iam.infra.utils.SagaTopic.Project.PROJECT_UPDATE;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.hzero.iam.domain.entity.Role;
import org.hzero.iam.domain.entity.Tenant;
import org.hzero.iam.domain.entity.User;
import org.hzero.iam.infra.mapper.TenantMapper;
import org.hzero.iam.infra.mapper.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ObjectUtils;

import io.choerodon.asgard.saga.annotation.Saga;
import io.choerodon.asgard.saga.producer.StartSagaBuilder;
import io.choerodon.asgard.saga.producer.TransactionalProducer;
import io.choerodon.core.domain.Page;
import io.choerodon.core.exception.CommonException;
import io.choerodon.core.iam.ResourceLevel;
import io.choerodon.core.oauth.CustomUserDetails;
import io.choerodon.core.oauth.DetailsHelper;
import io.choerodon.core.utils.ConvertUtils;
import io.choerodon.iam.api.vo.AgileProjectInfoVO;
import io.choerodon.iam.api.vo.ProjectCategoryVO;
import io.choerodon.iam.api.vo.ProjectCategoryWarpVO;
import io.choerodon.iam.api.vo.agile.AgileUserVO;
import io.choerodon.iam.app.service.OrganizationProjectC7nService;
import io.choerodon.iam.app.service.ProjectC7nService;
import io.choerodon.iam.app.service.UserC7nService;
import io.choerodon.iam.infra.asserts.DetailsHelperAssert;
import io.choerodon.iam.infra.asserts.OrganizationAssertHelper;
import io.choerodon.iam.infra.asserts.ProjectAssertHelper;
import io.choerodon.iam.infra.asserts.UserAssertHelper;
import io.choerodon.iam.infra.constant.ResourceCheckConstants;
import io.choerodon.iam.infra.dto.ProjectCategoryDTO;
import io.choerodon.iam.infra.dto.ProjectDTO;
import io.choerodon.iam.infra.dto.ProjectMapCategoryDTO;
import io.choerodon.iam.infra.dto.UserDTO;
import io.choerodon.iam.infra.dto.payload.ProjectEventPayload;
import io.choerodon.iam.infra.enums.RoleLabelEnum;
import io.choerodon.iam.infra.feign.operator.AgileFeignClientOperator;
import io.choerodon.iam.infra.feign.operator.TestManagerFeignClientOperator;
import io.choerodon.iam.infra.mapper.*;
import io.choerodon.mybatis.pagehelper.PageHelper;
import io.choerodon.mybatis.pagehelper.domain.PageRequest;

/**
 * @author scp
 * @since 2020/4/15
 */
@Service
public class ProjectC7nServiceImpl implements ProjectC7nService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ProjectC7nServiceImpl.class);

    protected static final String ERROR_PROJECT_NOT_EXIST = "error.project.not.exist";
    protected static final String CATEGORY_REF_TYPE = "projectCategory";

    protected OrganizationProjectC7nService organizationProjectC7nService;

    @Value("${choerodon.category.enabled:false}")
    protected boolean enableCategory;

    @Value("${spring.application.name:default}")
    protected String serviceName;

    private final ObjectMapper mapper = new ObjectMapper();

    protected UserMapper userMapper;

    protected ProjectMapper projectMapper;
    protected ProjectAssertHelper projectAssertHelper;
    protected ProjectMapCategoryMapper projectMapCategoryMapper;
    protected UserAssertHelper userAssertHelper;
    protected OrganizationAssertHelper organizationAssertHelper;
    protected TenantMapper organizationMapper;
    protected AgileFeignClientOperator agileFeignClientOperator;
    protected TestManagerFeignClientOperator testManagerFeignClientOperator;
    protected ProjectPermissionMapper projectPermissionMapper;
    protected TransactionalProducer transactionalProducer;

    protected RoleC7nMapper roleC7nMapper;
    protected UserC7nService userC7nService;

    private ProjectCategoryMapper projectCategoryMapper;

    public ProjectC7nServiceImpl(OrganizationProjectC7nService organizationProjectC7nService,
                                 OrganizationAssertHelper organizationAssertHelper,
                                 UserMapper userMapper,
                                 ProjectMapper projectMapper,
                                 ProjectAssertHelper projectAssertHelper,
                                 ProjectMapCategoryMapper projectMapCategoryMapper,
                                 UserAssertHelper userAssertHelper,
                                 TenantMapper organizationMapper,
                                 TestManagerFeignClientOperator testManagerFeignClientOperator,
                                 AgileFeignClientOperator agileFeignClientOperator,
                                 TransactionalProducer transactionalProducer,
                                 ProjectPermissionMapper projectPermissionMapper,
                                 @Lazy
                                         UserC7nService userC7nService,
                                 RoleC7nMapper roleC7nMapper,
                                 ProjectCategoryMapper projectCategoryMapper) {
        this.organizationProjectC7nService = organizationProjectC7nService;
        this.organizationAssertHelper = organizationAssertHelper;
        this.userMapper = userMapper;
        this.projectMapper = projectMapper;
        this.projectAssertHelper = projectAssertHelper;
        this.projectMapCategoryMapper = projectMapCategoryMapper;
        this.userAssertHelper = userAssertHelper;
        this.organizationMapper = organizationMapper;
        this.agileFeignClientOperator = agileFeignClientOperator;
        this.testManagerFeignClientOperator = testManagerFeignClientOperator;
        this.projectPermissionMapper = projectPermissionMapper;
        this.transactionalProducer = transactionalProducer;
        this.roleC7nMapper = roleC7nMapper;
        this.userC7nService = userC7nService;
        this.projectCategoryMapper = projectCategoryMapper;
    }

    @Override
    public ProjectDTO queryProjectById(Long projectId, boolean withCategoryInfo, boolean withUserInfo, boolean withAgileInfo) {
        ProjectDTO dto = projectAssertHelper.projectNotExisted(projectId);
        if (withCategoryInfo) {
            if (enableCategory) {
                dto.setCategories(projectMapCategoryMapper.selectProjectCategoryNames(dto.getId()));
            }
        }
        if (withUserInfo) {
            User createdUser = userMapper.selectByPrimaryKey(dto.getCreatedBy());
            if (createdUser != null) {
                dto.setCreateUserName(createdUser.getRealName());
                dto.setCreateUserImageUrl(createdUser.getImageUrl());
            }
        }
        if (withAgileInfo) {
            try {
                AgileProjectInfoVO agileProjectResponse = agileFeignClientOperator.queryProjectInfoByProjectId(projectId);
                dto.setAgileProjectId(agileProjectResponse.getInfoId());
                dto.setAgileProjectCode(agileProjectResponse.getProjectCode());
                dto.setAgileProjectObjectVersionNumber(agileProjectResponse.getObjectVersionNumber());
            } catch (Exception e) {
                LOGGER.warn("agile feign invoke exception: {}", e.getMessage());
            }
        }
        return dto;
    }


    @Transactional(rollbackFor = CommonException.class)
    @Override
    @Saga(code = PROJECT_UPDATE, description = "iam更新项目", inputSchemaClass = ProjectEventPayload.class)
    public ProjectDTO update(ProjectDTO projectDTO) {
        AgileProjectInfoVO projectInfoVO=null;
        try {
            projectInfoVO = agileFeignClientOperator.queryProjectInfoByProjectId(projectDTO.getAgileProjectId());
        } catch (Exception e) {
            LOGGER.warn("agile feign invoke exception: {}", e.getMessage());
        }
        if (projectDTO.getAgileProjectId() != null) {
            AgileProjectInfoVO agileProject = new AgileProjectInfoVO();
            agileProject.setInfoId(projectDTO.getAgileProjectId());
            agileProject.setProjectCode(projectDTO.getAgileProjectCode());
            agileProject.setObjectVersionNumber(projectDTO.getAgileProjectObjectVersionNumber());
            try {
                agileFeignClientOperator.updateProjectInfo(projectDTO.getId(), agileProject);
                testManagerFeignClientOperator.updateProjectInfo(projectDTO.getId(), agileProject);
            } catch (Exception e) {
                LOGGER.warn("agile feign invoke exception: {}", e.getMessage());
            }
        }
        ProjectDTO dto = new ProjectDTO();
        CustomUserDetails details = DetailsHelperAssert.userDetailNotExisted();
        User user = userAssertHelper.userNotExisted(UserAssertHelper.WhichColumn.LOGIN_NAME, details.getUsername());
        ProjectDTO newProject = projectAssertHelper.projectNotExisted(projectDTO.getId());

        Tenant tenant = organizationMapper.selectByPrimaryKey(newProject.getOrganizationId());
        ProjectEventPayload projectEventMsg = new ProjectEventPayload();
        projectEventMsg.setUserName(details.getUsername());
        projectEventMsg.setUserId(user.getId());
        if (tenant != null) {
            projectEventMsg.setOrganizationCode(tenant.getTenantNum());
            projectEventMsg.setOrganizationName(tenant.getTenantName());
        }
        projectEventMsg.setProjectId(newProject.getId());
        projectEventMsg.setProjectCode(newProject.getCode());
        projectEventMsg.setProjectName(projectDTO.getName());
        projectEventMsg.setImageUrl(newProject.getImageUrl());
        projectEventMsg.setAgileProjectCode(projectDTO.getAgileProjectCode());
        projectEventMsg.setOldAgileProjectCode(Objects.isNull(projectInfoVO) ? null : projectInfoVO.getProjectCode());

        try {
            String input = mapper.writeValueAsString(projectEventMsg);
            transactionalProducer.apply(StartSagaBuilder.newBuilder()
                            .withRefId(String.valueOf(projectDTO.getId()))
                            .withRefType(ResourceLevel.PROJECT.value())
                            .withSagaCode(PROJECT_UPDATE)
                            .withLevel(ResourceLevel.PROJECT)
                            .withSourceId(projectDTO.getId())
                            .withJson(input),
                    builder -> {
                        ProjectDTO newDTO = organizationProjectC7nService.updateSelective(projectDTO);
                        BeanUtils.copyProperties(newDTO, dto);
                    });
        } catch (Exception e) {
            throw new CommonException("error.projectService.update.event", e);
        }
        return dto;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProjectDTO disableProject(Long projectId) {
        Long userId = DetailsHelper.getUserDetails().getUserId();
        return organizationProjectC7nService.disableProject(null, projectId, userId);
    }

    @Override
    public List<Long> listUserIds(Long projectId) {
        return projectMapper.listUserIds(projectId);
    }

    @Override
    public List<ProjectDTO> queryByIds(Set<Long> ids) {
        if (ids.isEmpty()) {
            return new ArrayList<>();
        } else {
            return projectMapper.selectByProjectIds(ids);
        }
    }

    @Override
    public List<Long> getProListByName(String name) {
        return projectMapper.getProListByName(name);
    }

    @Override
    public Tenant getOrganizationByProjectId(Long projectId) {
        ProjectDTO projectDTO = checkNotExistAndGet(projectId);
        return organizationAssertHelper.notExisted(projectDTO.getOrganizationId());
    }

    @Override
    public ProjectDTO checkNotExistAndGet(Long projectId) {
        ProjectDTO projectDTO = projectMapper.selectByPrimaryKey(projectId);
        if (projectDTO == null) {
            throw new CommonException(ERROR_PROJECT_NOT_EXIST);
        }
        return projectDTO;
    }

    @Override
    public List<ProjectDTO> listOrgProjectsWithLimitExceptSelf(Long projectId, String name) {
        ProjectDTO projectDTO = projectMapper.selectByPrimaryKey(projectId);
        if (projectDTO == null) {
            throw new CommonException(ERROR_PROJECT_NOT_EXIST);
        }
        int limit = 50;

        List<ProjectDTO> projectDTOS = projectMapper.selectProjectsByOrgIdAndNameWithLimit(projectDTO.getOrganizationId(), name, limit);
        return projectDTOS.stream()
                .filter(project -> !project.getId().equals(projectId))
                .collect(Collectors.toList());
    }

    @Override
    public List<ProjectDTO> listAllProjects() {
        return projectMapper.selectAll();
    }

    @Override
    public Page<UserDTO> pagingQueryTheUsersOfProject(Long projectId, Long userId, String email, PageRequest pageRequest, String param) {
        ProjectDTO project = projectMapper.selectByPrimaryKey(projectId);
        if (ObjectUtils.isEmpty(project)) {
            return new Page<>();
        }
        Long organizationId = project.getOrganizationId();
        Role projectAdmin = queryProjectAdminByTenantId(organizationId);
        Role projectMember = queryProjectMemberByTenantId(organizationId);
        return PageHelper.doPage(pageRequest, () -> projectPermissionMapper.selectUsersByOptionsOrderByRoles(projectId, userId, email, param, projectAdmin.getId(), projectMember.getId()));
    }

    protected Role queryProjectAdminByTenantId(Long organizationId) {
        List<Role> roles = roleC7nMapper.getByTenantIdAndLabel(organizationId, RoleLabelEnum.PROJECT_ADMIN.value());
        if (ObjectUtils.isEmpty(roles)) {
            throw new CommonException("error.project.role.not.existed");
        }
        return roles.get(0);
    }

    protected Role queryProjectMemberByTenantId(Long organizationId) {
        List<Role> roles = roleC7nMapper.getByTenantIdAndLabel(organizationId, RoleLabelEnum.PROJECT_MEMBER.value());
        if (ObjectUtils.isEmpty(roles)) {
            throw new CommonException("error.project.role.not.existed");
        }
        return roles.get(0);
    }

    protected Set<Long> getRoleIdsByLabel(Long organizationId, String labelName) {
        List<Role> roles = roleC7nMapper.getByTenantIdAndLabel(organizationId, labelName);
        if (ObjectUtils.isEmpty(roles)) {
            throw new CommonException("error.project.role.not.existed");
        }
        return roles.stream().map(Role::getId).collect(Collectors.toSet());
    }

    @Override
    public Page<UserDTO> agileUsers(Long projectId, PageRequest pageable, Set<Long> userIds, String param) {
        ProjectDTO project = projectMapper.selectByPrimaryKey(projectId);
        if (ObjectUtils.isEmpty(project)) {
            return new Page<>();
        }
        Long organizationId = project.getOrganizationId();
        Set<Long> adminRoleIds = getRoleIdsByLabel(organizationId, RoleLabelEnum.PROJECT_ADMIN.value());
        return PageHelper.doPage(pageable, () -> projectPermissionMapper.selectAgileUsersByProjectId(projectId, userIds, param, adminRoleIds));
    }

    @Override
    public Page<UserDTO> agileUsersByProjects(PageRequest pageable, AgileUserVO agileUserVO) {
        Long organizationId = agileUserVO.getOrganizationId();
        if (ObjectUtils.isEmpty(organizationId)) {
            throw new CommonException("error.feign.agile.user.organizationId.null");
        }
        Set<Long> projectIds = agileUserVO.getProjectIds();
        Set<Long> userIds = agileUserVO.getUserIds();
        if (ObjectUtils.isEmpty(projectIds)) {
            throw new CommonException("error.feign.agile.user.projectIds.empty");
        }
        Set<Long> adminRoleIds = getRoleIdsByLabel(organizationId, RoleLabelEnum.PROJECT_ADMIN.value());
        return PageHelper.doPage(pageable, () -> projectPermissionMapper.selectAgileUsersByProjectIds(projectIds, userIds, agileUserVO.getParam(), adminRoleIds));
    }

    @Override
    public ProjectDTO queryBasicInfo(Long projectId) {
        Assert.notNull(projectId, ResourceCheckConstants.ERROR_PROJECT_IS_NULL);
        ProjectDTO projectDTO = projectMapper.selectByPrimaryKey(projectId);
        List<ProjectCategoryDTO> projectCategoryDTOS = projectMapCategoryMapper.selectProjectCategoryNames(projectDTO.getId());
        projectDTO.setCategories(projectCategoryDTOS);

        return projectDTO;
    }

    @Override
    public List<ProjectDTO> queryProjectByOption(ProjectDTO projectDTO) {
        return projectMapper.select(projectDTO);
    }

    @Override
    public Boolean checkProjCode(String code) {
        ProjectDTO projectDTO = new ProjectDTO();
        projectDTO.setCode(code);
        return projectMapper.selectOne(projectDTO) == null;
    }


    @Override
    public Boolean checkPermissionByProjectId(Long projectId) {
        CustomUserDetails userDetails = DetailsHelper.getUserDetails();
        if (userDetails == null) {
            return false;
        }
        ProjectDTO projectDTO = projectMapper.selectByPrimaryKey(projectId);
        boolean isAdmin = userC7nService.isRoot(userDetails.getUserId());
        boolean isOrgAdmin = userC7nService.checkIsOrgRoot(projectDTO.getOrganizationId(), userDetails.getUserId());
        if (isAdmin || isOrgAdmin) {
            return true;
        } else {
            return projectMapper.checkPermissionByProjectId(projectDTO.getOrganizationId(), projectId, userDetails.getUserId());
        }
    }

    @Override
    public void deleteProjectCategory(Long projectId, List<Long> categoryIds) {
        ProjectDTO projectDTO = projectMapper.selectByPrimaryKey(projectId);
        List<Long> dbProjectCategoryIds = validateAndGetDbCategoryIds(projectDTO, categoryIds);
        if (dbProjectCategoryIds == null) {
            return;
        }
        //要删除的集合
        List<Long> ids = dbProjectCategoryIds.stream().filter(aLong -> categoryIds.contains(aLong)).collect(Collectors.toList());
        //至少需要保留一个项目类型
        if (dbProjectCategoryIds.size() == ids.size() || ids.size() == 0) {
            return;
        }
        //批量删除
        projectMapCategoryMapper.batchDelete(projectId, categoryIds);

    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    @Saga(code = ADD_PROJECT_CATEGORY, description = "iam添加项目类型", inputSchemaClass = ProjectEventPayload.class)
    public void addProjectCategory(Long projectId, List<Long> categoryIds) {
        ProjectDTO projectDTO = projectMapper.selectByPrimaryKey(projectId);
//        List<Long> dbProjectCategoryIds = validateAndGetDbCategoryIds(projectDTO, categoryIds);
//        if (dbProjectCategoryIds == null) return;
//        //要添加的集合
//        List<Long> ids = categoryIds.stream().filter(aLong -> !dbProjectCategoryIds.contains(aLong)).collect(Collectors.toList());
//        if (CollectionUtils.isEmpty(ids)) {
//            return;
//        }
        //批量插入
        List<ProjectMapCategoryDTO> projectMapCategoryDTOS = new ArrayList<>();
        for (Long categoryId : categoryIds) {
            ProjectMapCategoryDTO mapCategoryDTO = new ProjectMapCategoryDTO();
            mapCategoryDTO.setProjectId(projectId);
            mapCategoryDTO.setCategoryId(categoryId);
            projectMapCategoryDTOS.add(mapCategoryDTO);
        }
        projectMapCategoryMapper.batchInsert(projectMapCategoryDTOS);

        //发送saga做添加项目类型的后续处理
        ProjectEventPayload projectEventPayload = new ProjectEventPayload();
        projectEventPayload.setProjectId(projectId);
        projectEventPayload.setProjectCode(projectDTO.getCode());
        projectEventPayload.setProjectName(projectDTO.getName());
        Tenant organization = getOrganizationByProjectId(projectId);
        projectEventPayload.setOrganizationCode(organization.getTenantNum());
        projectEventPayload.setOrganizationName(organization.getTenantName());
        projectEventPayload.setOrganizationId(organization.getTenantId());
        projectEventPayload.setUserId(DetailsHelper.getUserDetails().getUserId());
        projectEventPayload.setUserName(DetailsHelper.getUserDetails().getUsername());
        //加添项目类型的数据
        List<ProjectCategoryDTO> projectCategoryDTOS = projectCategoryMapper.selectByIds(StringUtils.join(categoryIds, ","));
        if (!CollectionUtils.isEmpty(projectCategoryDTOS)) {
            projectEventPayload.setProjectCategoryVOS(ConvertUtils.convertList(projectCategoryDTOS, ProjectCategoryVO.class));
        }
        try {
            String input = mapper.writeValueAsString(projectEventPayload);
            transactionalProducer.apply(StartSagaBuilder.newBuilder()
                            .withRefId(String.valueOf(projectId))
                            .withRefType(CATEGORY_REF_TYPE)
                            .withSagaCode(ADD_PROJECT_CATEGORY)
                            .withLevel(ResourceLevel.PROJECT)
                            .withSourceId(projectId)
                            .withJson(input),
                    builder -> {
                    });
        } catch (Exception e) {
            throw new CommonException("error.projectCategory.update.event", e);
        }
    }

    @Override
    public ProjectCategoryWarpVO queryProjectCategory(Long projectId) {
        ProjectCategoryWarpVO projectCategoryWarpVO = new ProjectCategoryWarpVO();
        ProjectMapCategoryDTO mapCategoryDTO = new ProjectMapCategoryDTO();
        mapCategoryDTO.setProjectId(projectId);

        List<ProjectMapCategoryDTO> selectedCategory = projectMapCategoryMapper.select(mapCategoryDTO);
        List<ProjectCategoryDTO> projectCategoryDTOS = projectCategoryMapper.selectByIds(StringUtils.join(selectedCategory.stream().map(ProjectMapCategoryDTO::getCategoryId).collect(Collectors.toList()), ","));
        projectCategoryWarpVO.setSelectedProjectCategory(ConvertUtils.convertList(projectCategoryDTOS, ProjectCategoryVO.class));
        List<Long> ids = projectCategoryDTOS.stream().map(ProjectCategoryDTO::getId).collect(Collectors.toList());

        List<ProjectCategoryDTO> unSelected = projectCategoryMapper.selectAll().stream().filter(projectCategoryDTO -> !ids.contains(projectCategoryDTO.getId())).collect(Collectors.toList());
        projectCategoryWarpVO.setUnSelectedProjectCategory(ConvertUtils.convertList(unSelected, ProjectCategoryVO.class));
        return projectCategoryWarpVO;
    }

    private List<Long> validateAndGetDbCategoryIds(ProjectDTO projectDTO, List<Long> categoryIds) {
        Assert.notNull(projectDTO, ERROR_PROJECT_NOT_EXIST);
        if (CollectionUtils.isEmpty(categoryIds)) {
            return null;
        }
        ProjectMapCategoryDTO projectMapCategoryDTO = new ProjectMapCategoryDTO();
        projectMapCategoryDTO.setProjectId(projectDTO.getId());
        List<Long> dbProjectCategoryIds = projectMapCategoryMapper.select(projectMapCategoryDTO).stream().map(ProjectMapCategoryDTO::getCategoryId).collect(Collectors.toList());
        return dbProjectCategoryIds;
    }
}
