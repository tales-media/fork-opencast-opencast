package media.tales.userdirectory;

import org.opencastproject.security.api.Organization;
import org.opencastproject.security.api.OrganizationDirectoryListener;
import org.opencastproject.security.api.OrganizationDirectoryService;
import org.opencastproject.security.api.SecurityService;
import org.opencastproject.security.api.UnauthorizedException;
import org.opencastproject.security.impl.jpa.JpaGroup;
import org.opencastproject.security.impl.jpa.JpaOrganization;
import org.opencastproject.security.impl.jpa.JpaRole;
import org.opencastproject.security.util.SecurityUtil;
import org.opencastproject.userdirectory.JpaGroupRoleProvider;
import org.opencastproject.util.NotFoundException;

import org.apache.commons.lang3.StringUtils;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Group loader for creating and updating tales.media organization groups.
 */
@Component(
    property = { "service.description=tales.media group loader" },
    immediate = true,
    service = {
      TalesMediaGroupLoader.class
    }
)
public class TalesMediaGroupLoader implements OrganizationDirectoryListener {
  private static final Logger logger = LoggerFactory.getLogger(TalesMediaGroupLoader.class);

  public static final String CFG_GROUP_PREFIX = "media.tales.groups.";
  public static final String CFG_ID_KEY = "id_tmpl";
  public static final String CFG_NAME_KEY = "name_tmpl";
  public static final String CFG_DESCRIPTION_KEY = "description_tmpl";
  public static final String CFG_ROLE_KEY = "roles";

  protected OrganizationDirectoryService organizationDirectoryService;
  protected SecurityService securityService;
  protected JpaGroupRoleProvider groupRoleProvider;
  private ComponentContext componentContext;
  private List<OrgGroupTemplate> groups;

  @Reference(name = "organizationDirectoryService")
  void setOrganizationDirectoryService(OrganizationDirectoryService organizationDirectoryService) {
    this.organizationDirectoryService = organizationDirectoryService;
    this.organizationDirectoryService.addOrganizationDirectoryListener(this);
  }

  @Reference(name = "security-service")
  void setSecurityService(SecurityService securityService) {
    this.securityService = securityService;
  }

  @Reference(name = "groupRoleProvider")
  void setGroupRoleProvider(JpaGroupRoleProvider groupRoleProvider) {
    this.groupRoleProvider = groupRoleProvider;
  }

  @Activate
  public void activate(ComponentContext cc) {
    logger.info("Ensure tales.media groups exist");
    this.componentContext = cc;
    groups = findConfiguredGroups();
    ensureOrgGroupsExist();
  }

  private List<OrgGroupTemplate> findConfiguredGroups() {
    List<OrgGroupTemplate> groups = new ArrayList<>();

    Collections.list(componentContext.getProperties().keys())
        .stream()
        .filter(s -> s.startsWith(CFG_GROUP_PREFIX))
        .filter(s -> s.endsWith(CFG_ID_KEY))
        .forEach(s -> {
          String keyPrefix = s.substring(0, s.length() - CFG_ID_KEY.length());

          String idTemplate = StringUtils.trimToNull((String) componentContext.getProperties().get(keyPrefix + CFG_ID_KEY));
          String nameTemplate = StringUtils.trimToNull((String) componentContext.getProperties().get(keyPrefix + CFG_NAME_KEY));
          String descriptionTemplate = StringUtils
              .trimToNull((String) componentContext.getProperties().get(keyPrefix + CFG_DESCRIPTION_KEY));
          String rolesStr = StringUtils.trimToNull((String) componentContext.getProperties().get(keyPrefix + CFG_ROLE_KEY));

          if (idTemplate == null || nameTemplate == null || descriptionTemplate == null || rolesStr == null) {
            logger.error("The configuration for the tales.media organization group '{}' is incomplete",
                keyPrefix.substring(0, keyPrefix.length() - 1));
            return;
          }

          Set<String> roles = Arrays.stream(rolesStr.split(","))
              .map(StringUtils::trimToNull)
              .filter(Objects::nonNull)
              .collect(Collectors.toSet());

          OrgGroupTemplate tmpl = new OrgGroupTemplate();
          tmpl.idTemplate = idTemplate;
          tmpl.nameTemplate = nameTemplate;
          tmpl.descriptionTemplate = descriptionTemplate;
          tmpl.roles = roles;

          groups.add(tmpl);
        });

    return groups;
  }

  private void ensureOrgGroupsExist() {
    for (final Organization organization : organizationDirectoryService.getOrganizations()) {
      ensureOrgGroupsExist(organization);
    }
  }

  private void ensureOrgGroupsExist(Organization organization) {
    SecurityUtil.runAs(securityService, organization, SecurityUtil.createSystemUser(componentContext, organization),
        () -> {
          try {
            JpaOrganization org = getJpaOrganization(organization);
            if (org == null) {
              logger.info(
                  "Ignoring organization with id " + organization.getId() + " because it is not a JpaOrganization");
              return;
            }
            groups.forEach(g -> ensureOrgGroupExist(g, org));
          } catch (NotFoundException e) {
            logger.error("Unable to load tales.media groups", e);
          }
        });
  }

  private void ensureOrgGroupExist(OrgGroupTemplate groupTemplate, JpaOrganization org) {
    String groupId = String.format(groupTemplate.idTemplate, org.getId().toUpperCase());
    String groupName = String.format(groupTemplate.nameTemplate, org.getName());
    String groupDescription = String.format(groupTemplate.descriptionTemplate, org.getName());
    Set<JpaRole> groupRoles = groupTemplate.roles.stream()
        .map(roleName -> new JpaRole(roleName, org))
        .collect(Collectors.toSet());

    try {
      JpaGroup group = (JpaGroup) groupRoleProvider.loadGroup(groupId, org.getId());

      if (group == null) {
        logger.info("Creating group {} for organization {}", groupId, org.getId());
        group = new JpaGroup(groupId, org, groupName, groupDescription, groupRoles);
        groupRoleProvider.addGroup(group);
      } else {
        logger.info("Updating group {} for organization {}", groupId, org.getId());
        Set<String> groupMembers = new HashSet<>(group.getMembers());
        groupRoleProvider.updateGroup(groupId, groupName, groupDescription,
            StringUtils.join(groupTemplate.roles, ','), StringUtils.join(groupMembers, ','));
      }
    } catch (NotFoundException | UnauthorizedException e) {
      logger.error("Unable to create tales.media organization group {} for organization {}", groupId, org, e);
    }
  }

  private JpaOrganization getJpaOrganization(Organization organization) throws NotFoundException {
    Organization org = organizationDirectoryService.getOrganization(organization.getId());
    if (org instanceof JpaOrganization)
      return (JpaOrganization) org;
    return null;
  }

  @Override
  public void organizationRegistered(Organization organization) {
    ensureOrgGroupsExist(organization);
  }

  @Override
  public void organizationUpdated(Organization organization) {
    ensureOrgGroupsExist(organization);
  }

  @Override
  public void organizationUnregistered(Organization organization) {
    // Nothing to do
  }

  private static class OrgGroupTemplate {
    public String idTemplate;
    public String nameTemplate;
    public String descriptionTemplate;
    public Set<String> roles;
  }
}
