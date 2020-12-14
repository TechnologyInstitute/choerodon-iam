package io.choerodon.iam.app.service.impl;

import io.choerodon.core.exception.CommonException;
import io.choerodon.core.iam.ResourceLevel;
import io.choerodon.core.oauth.CustomUserDetails;
import io.choerodon.iam.api.vo.RoleVO;
import io.choerodon.iam.app.service.*;
import io.choerodon.iam.infra.constant.MisConstants;
import io.choerodon.iam.infra.enums.MenuLabelEnum;
import io.choerodon.iam.infra.enums.RoleLabelEnum;
import io.choerodon.iam.infra.mapper.MenuC7nMapper;
import io.choerodon.iam.infra.mapper.RoleC7nMapper;
import io.choerodon.iam.infra.utils.CommonExAssertUtil;
import io.choerodon.iam.infra.utils.ConvertUtils;
import org.hzero.iam.app.service.RoleService;
import org.hzero.iam.domain.entity.*;
import org.hzero.iam.domain.service.role.impl.RoleCreateInternalService;
import org.hzero.iam.infra.common.utils.UserUtils;
import org.hzero.iam.infra.constant.HiamMenuType;
import org.hzero.iam.infra.constant.RolePermissionType;
import org.hzero.iam.infra.mapper.RoleMapper;
import org.hzero.mybatis.helper.SecurityTokenHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 〈功能简述〉
 * 〈〉
 *
 * @author wanghao
 * @since 2020/4/22 10:10
 */
@Service
public class OrganizationRoleServiceImpl implements OrganizationRoleC7nService {

    private static final String ERROR_BUILT_IN_ROLE_NOT_BE_EDIT = "error.built.in.role.not.be.edit";
    private static final String ERROR_ROLE_ID_NOT_BE_NULL = "error.role.id.not.be.null";

    private RoleCreateInternalService roleCreateInternalService;
    private RoleService roleService;
    private LabelC7nService labelC7nService;
    private RolePermissionC7nService rolePermissionC7nService;
    private RoleC7nMapper roleC7nMapper;
    private RoleC7nService roleC7nService;
    private RoleMapper roleMapper;
    private MenuC7nService menuC7nService;
    private MenuC7nMapper menuC7nMapper;

    public OrganizationRoleServiceImpl(RoleCreateInternalService roleCreateInternalService,
                                       RoleService roleService,
                                       LabelC7nService labelC7nService,
                                       RolePermissionC7nService rolePermissionC7nService,
                                       RoleC7nMapper roleC7nMapper,
                                       RoleC7nService roleC7nService,
                                       RoleMapper roleMapper,
                                       MenuC7nService menuC7nService,
                                       MenuC7nMapper menuC7nMapper) {
        this.roleCreateInternalService = roleCreateInternalService;
        this.roleService = roleService;
        this.labelC7nService = labelC7nService;
        this.rolePermissionC7nService = rolePermissionC7nService;
        this.roleC7nMapper = roleC7nMapper;
        this.roleC7nService = roleC7nService;
        this.roleMapper = roleMapper;
        this.menuC7nService = menuC7nService;
        this.menuC7nMapper = menuC7nMapper;
    }

    @Override
    @Transactional
    public void create(Long organizationId, RoleVO roleVO) {
        Role tenantAdmin = roleC7nService.getTenantAdminRole(organizationId);
        roleVO.setParentRoleId(tenantAdmin.getId());

        //  给角色添加层级标签
        if (ResourceLevel.PROJECT.value().equals(roleVO.getRoleLevel())) {
            Label label = labelC7nService.selectByName(RoleLabelEnum.PROJECT_ROLE.value());
            roleVO.getRoleLabels().add(label);
        } else if (ResourceLevel.ORGANIZATION.value().equals(roleVO.getRoleLevel())) {
            Label label = labelC7nService.selectByName(RoleLabelEnum.TENANT_ROLE.value());
            roleVO.getRoleLabels().add(label);

        }
        // 创建角色
        CustomUserDetails details = UserUtils.getUserDetails();
        User adminUser = new User();
        adminUser.setId(details.getUserId());
        roleVO.setTenantId(organizationId);
        Role role = roleCreateInternalService.createRole(roleVO, adminUser, false, false);


        // 分配权限集
        // 默认分配个人信息权限集

        Set<Long> psIds = listUserInfoPsIds();

        roleVO.getMenuIdList().addAll(psIds);
        assignRolePermission(role.getId(), roleVO.getMenuIdList());
    }

    private Set<Long> listUserInfoPsIds() {
        // 查询个人信息权限集
        // 查询个人信息菜单
        List<Menu> menus = menuC7nService.listUserInfoMenuOnlyTypeMenu();
        Set<Long> ids = menus.stream().map(Menu::getId).collect(Collectors.toSet());
        List<Menu> menuList = menuC7nMapper.listPermissionSetByParentIds(ids);
        return menuList.stream().map(Menu::getId).collect(Collectors.toSet());

    }

    @Override
    @Transactional
    public void update(Long organizationId, Long roleId, RoleVO roleVO) {
        Role role = roleMapper.selectByPrimaryKey(roleId);
        if (role.getTenantId() != null) {
            CommonExAssertUtil.assertTrue(organizationId.equals(role.getTenantId()), MisConstants.ERROR_OPERATING_RESOURCE_IN_OTHER_ORGANIZATION);
        }

        roleVO.setId(roleId);

        // 预定义角色无法修改
        checkEnableEdit(roleId);


        // 修改角色
        roleMapper.updateByPrimaryKeySelective(roleVO);

        // 更新角色权限
        List<RolePermission> rolePermissions = rolePermissionC7nService.listRolePermissionByRoleId(roleId);
        Set<Long> permissionIds = rolePermissions.stream().map(RolePermission::getPermissionSetId).collect(Collectors.toSet());
        Set<Long> psIds = listUserInfoPsIds();
        permissionIds.addAll(psIds);
        // 要新增的权限
        Set<Long> newPermissionIds = roleVO.getMenuIdList().stream().filter(permissionId -> !permissionIds.contains(permissionId)).collect(Collectors.toSet());
        // 要删除的权限
        Set<Long> deletePermissionIds = permissionIds.stream().filter(permissionId -> !roleVO.getMenuIdList().contains(permissionId)).collect(Collectors.toSet());
        if (!CollectionUtils.isEmpty(deletePermissionIds)) {
            // 删除权限
            rolePermissionC7nService.batchDelete(roleId, deletePermissionIds);
        }
        if (!CollectionUtils.isEmpty(newPermissionIds)) {
            // 新增权限
            assignRolePermission(roleId, newPermissionIds);
        }


    }


    @Override
    public List<Role> getByTenantIdAndLabel(Long tenantId, String labelName) {
        return roleC7nMapper.getByTenantIdAndLabel(tenantId, labelName);
    }

    @Override
    public RoleVO queryById(Long organizationId, Long roleId) {
        Role role = roleMapper.selectByPrimaryKey(roleId);
        List<Label> labels = roleC7nMapper.listRoleLabels(role.getId());

        RoleVO roleVO = ConvertUtils.convertObject(role, RoleVO.class);
        roleVO.setRoleLabels(new ArrayList<>());
        Set<String> labelNames = new HashSet<>();
        for (Label label : labels) {
            if (RoleLabelEnum.TENANT_ROLE.value().equals(label.getName())) {
                labelNames.add(MenuLabelEnum.TENANT_MENU.value());
                labelNames.add(MenuLabelEnum.TENANT_GENERAL.value());
            }
            if (RoleLabelEnum.PROJECT_ROLE.value().equals(label.getName())) {
                labelNames.add(MenuLabelEnum.GENERAL_MENU.value());
                labelNames.add(MenuLabelEnum.AGILE_MENU.value());
                labelNames.add(MenuLabelEnum.PROGRAM_MENU.value());
                labelNames.add(MenuLabelEnum.OPERATIONS_MENU.value());
            }
            if (RoleLabelEnum.GITLAB_OWNER.value().equals(label.getName())
                    || RoleLabelEnum.GITLAB_DEVELOPER.value().equals(label.getName())) {
                roleVO.getRoleLabels().add(label);
            }
        }
        SecurityTokenHelper.close();
        Set<String> typeNames = new HashSet<>();
        typeNames.add(HiamMenuType.ROOT.value());
        typeNames.add(HiamMenuType.DIR.value());
        typeNames.add(HiamMenuType.MENU.value());
        List<Menu> menus = menuC7nMapper.listMenuByLabelAndType(labelNames, typeNames);
        SecurityTokenHelper.clear();


        List<Menu> rolePermissions = rolePermissionC7nService.listRolePermissionByRoleIdAndLabels(roleId, null);
        Set<Long> psIds = rolePermissions.stream().map(Menu::getId).collect(Collectors.toSet());
        // 查询权限集
        Set<Long> ids = menus.stream().map(Menu::getId).collect(Collectors.toSet());
        List<Menu> permissionSetList = menuC7nMapper.listPermissionSetByParentIds(ids);
        permissionSetList.forEach(ps -> {
            if (psIds.contains(ps.getId())) {
                ps.setCheckedFlag("Y");
            } else {
                ps.setCheckedFlag("N");
            }
        });
        menus.addAll(permissionSetList);

        roleVO.setMenuList(menus);
        return roleVO;
    }

    /**
     * 校验是否时预定义角色，预定义角色无法编辑
     *
     * @param roleId
     */
    private void checkEnableEdit(Long roleId) {
        org.hzero.iam.domain.vo.RoleVO role = roleService.selectRoleDetails(roleId);
        if (Boolean.TRUE.equals(role.getBuiltIn())) {
            throw new CommonException(ERROR_BUILT_IN_ROLE_NOT_BE_EDIT);
        }
    }

    /**
     * 分配角色权限
     *
     * @param roleId
     * @param permissionIds
     */
    private void assignRolePermission(Long roleId, Set<Long> permissionIds) {
        RolePermission rolePermission = new RolePermission();
        rolePermission.setRoleId(roleId);
        rolePermission.setPermissionSetIds(permissionIds);
        rolePermission.setType(RolePermissionType.PS.name());
        roleService.directAssignRolePermission(rolePermission);
    }
}
