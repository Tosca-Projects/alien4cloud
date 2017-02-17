package alien4cloud.orchestrators.locations.services;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.annotation.Resource;

import org.apache.commons.collections4.CollectionUtils;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import com.google.common.collect.Sets;

import alien4cloud.application.ApplicationEnvironmentService;
import alien4cloud.model.application.ApplicationEnvironment;
import alien4cloud.model.orchestrators.locations.Location;
import alien4cloud.model.orchestrators.locations.LocationResourceTemplate;
import alien4cloud.security.AbstractSecurityEnabledResource;
import alien4cloud.security.AuthorizationUtil;
import alien4cloud.security.Permission;
import alien4cloud.security.ResourcePermissionService;
import alien4cloud.security.Subject;
import alien4cloud.security.model.Role;
import alien4cloud.security.model.User;

@Service
public class LocationSecurityService {

    @Resource
    private ResourcePermissionService resourcePermissionService;
    @Resource
    private ApplicationEnvironmentService applicationEnvironmentService;

    /**
     * Get subjects from context (current user, current user's groups, current environment, application ...)
     * 
     * @param environment the environment from which the request has been made
     * @return a map of subject type to subjects' ids
     */
    public Map<Subject, Set<String>> getSubjectsFromContext(ApplicationEnvironment environment) {
        Map<Subject, Set<String>> subjectsMap = new HashMap<>();
        User user = AuthorizationUtil.getCurrentUser();
        if (user != null) {
            subjectsMap.put(Subject.USER, Sets.newHashSet(user.getUsername()));
            Set<String> userGroups = AuthorizationUtil.getUserGroups(user);
            subjectsMap.put(Subject.GROUP, userGroups);
        }
        if (environment != null) {
            subjectsMap.put(Subject.ENVIRONMENT, Sets.newHashSet(environment.getId()));
            subjectsMap.put(Subject.APPLICATION, Sets.newHashSet(environment.getApplicationId()));
        }
        return subjectsMap;
    }

    /**
     * Check whether the resource is authorised with the current context
     * 
     * @param resource the resource to check for authorisation
     * @param environment the environment
     * @return true if the location is authorised, false otherwise
     */
    public boolean isAuthorised(AbstractSecurityEnabledResource resource, ApplicationEnvironment environment) {
        if (AuthorizationUtil.hasOneRoleIn(Role.ADMIN)) {
            return true;
        }
        Map<Subject, Set<String>> subjectsMap = getSubjectsFromContext(environment);
        return resourcePermissionService.anyHasPermission(resource, subjectsMap);
    }

    /**
     * Check whether the resource is authorised with the current context
     *
     * @param resource the resource to check for authorisation
     * @param environmentId the id of the environment
     * @return true if the location is authorised, false otherwise
     */
    public boolean isAuthorised(AbstractSecurityEnabledResource resource, String environmentId) {
        return isAuthorised(resource, environmentId != null ? applicationEnvironmentService.getOrFail(environmentId) : null);
    }

    public void checkAuthorisation(Location location, ApplicationEnvironment environment) {
        if (!isAuthorised(location, environment)) {
            throw new AccessDeniedException("Current context does not have access to the location [" + location.getName() + "]");
        }
    }

    public void checkAuthorisation(Location location, String environmentId) {
        if (!isAuthorised(location, environmentId)) {
            throw new AccessDeniedException("Current context does not have access to the location [" + location.getName() + "]");
        }
    }

    /**
     * Check if all subjects have the authorization on the location .
     *
     * @param location The location on which to check
     * @param subjectType The type of the subjects to check for authorizations
     * @param grantAccess whether or not we want to grant access to unauthorized subjects. Fails with an {@link java.nio.file.AccessDeniedException} if set to
     *            false.
     * @param subjects The subjects to process
     */
    public void checkAuthorizations(Location location, Subject subjectType, boolean grantAccess, String... subjects) {
        Set<String> unauthorized = Arrays.stream(subjects).filter(name -> !location.getPermissions(subjectType, name).contains(Permission.ADMIN))
                .collect(Collectors.toSet());
        if (CollectionUtils.isNotEmpty(unauthorized)) {
            if (grantAccess) {
                resourcePermissionService.grantPermission(location, subjectType, unauthorized.toArray(new String[unauthorized.size()]));
            } else {
                throw new AccessDeniedException("At least one of the current <" + subjectType + "> has no authorization on location <" + location.getName()
                        + "> to perform the requested operation: " + unauthorized.toString());
            }
        }
    }

    public void checkAuthorisation(LocationResourceTemplate resourceTemplate, ApplicationEnvironment environment) {
        if (!isAuthorised(resourceTemplate, environment)) {
            throw new AccessDeniedException("Current context does not have access to the location resource [" + resourceTemplate.getLocationId() + "]["
                    + resourceTemplate.getName() + "]");
        }
    }

    public void checkAuthorisation(LocationResourceTemplate resourceTemplate, String environmentId) {
        if (!isAuthorised(resourceTemplate, environmentId)) {
            throw new AccessDeniedException("Current context does not have access to the location resource [" + resourceTemplate.getLocationId() + "]["
                    + resourceTemplate.getName() + "]");
        }
    }
}
