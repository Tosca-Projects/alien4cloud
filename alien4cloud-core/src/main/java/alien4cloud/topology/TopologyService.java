package alien4cloud.topology;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.annotation.Resource;
import javax.inject.Inject;

import org.alien4cloud.tosca.catalog.ArchiveDelegateType;
import org.alien4cloud.tosca.catalog.index.ICsarDependencyLoader;
import org.alien4cloud.tosca.catalog.index.IToscaTypeSearchService;
import org.alien4cloud.tosca.model.CSARDependency;
import org.alien4cloud.tosca.model.Csar;
import org.alien4cloud.tosca.model.templates.NodeTemplate;
import org.alien4cloud.tosca.model.templates.RelationshipTemplate;
import org.alien4cloud.tosca.model.templates.Topology;
import org.alien4cloud.tosca.model.types.AbstractToscaType;
import org.alien4cloud.tosca.model.types.NodeType;
import org.alien4cloud.tosca.model.types.RelationshipType;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.elasticsearch.common.collect.Lists;
import org.elasticsearch.mapping.FilterValuesStrategy;
import org.springframework.stereotype.Service;

import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import alien4cloud.application.ApplicationService;
import alien4cloud.dao.IGenericSearchDAO;
import alien4cloud.dao.model.GetMultipleDataResult;
import alien4cloud.exception.AlreadyExistException;
import alien4cloud.exception.VersionConflictException;
import alien4cloud.model.application.Application;
import alien4cloud.security.AuthorizationUtil;
import alien4cloud.security.model.ApplicationRole;
import alien4cloud.security.model.Role;
import alien4cloud.topology.exception.UpdateTopologyException;
import alien4cloud.topology.task.SuggestionsTask;
import alien4cloud.topology.task.TaskCode;
import alien4cloud.tosca.container.ToscaTypeLoader;
import alien4cloud.tosca.context.ToscaContext;
import alien4cloud.tosca.context.ToscaContextual;
import alien4cloud.utils.MapUtil;
import alien4cloud.utils.VersionUtil;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class TopologyService {
    @Resource
    private IToscaTypeSearchService toscaTypeSearchService;
    @Resource(name = "alien-es-dao")
    private IGenericSearchDAO alienDAO;
    @Resource
    private ICsarDependencyLoader csarDependencyLoader;
    @Resource
    private TopologyServiceCore topologyServiceCore;
    @Inject
    private ApplicationService appService;

    public static final Pattern NODE_NAME_PATTERN = Pattern.compile("^\\w+$");
    public static final Pattern NODE_NAME_REPLACE_PATTERN = Pattern.compile("\\W");

    /**
     * Checks if a dependency or its transitives will conflicts with the topology's dependencies.
     * If so, throws an unchecked exception.
     *
     * @param newDependency The new dependency CSAR fo witch to check the conflict
     * @param initialDependencies The dependencies within which to check the conflict
     * @param topology The topology undergoing the change
     */
    public void checkDependenciesConflict(CSARDependency newDependency, Set<CSARDependency> initialDependencies, Topology topology) {

        // Well... if the given dependency already exists in a different version in the given set, then no need to proceed further!
        initialDependencies.stream().filter(dep -> dep.getName().equals(newDependency.getName()) && !dep.getVersion().equals(newDependency.getVersion()))
                .findFirst().ifPresent(dependency -> {
                    throw new VersionConflictException(
                            "Dependency conflict between [" + toString(newDependency) + "] and another dependency [" + toString(dependency) + "].");
                });

        // All transitives dependencies of the given set
        final Set<CSARDependency> transitiveDependencies = initialDependencies
                .stream()
                .map(dependency -> csarDependencyLoader.getDependencies(dependency.getName(), dependency.getVersion()))
                .reduce(Sets::union)
                .orElse(Collections.emptySet());

        // If the new dependency is used as a transitive dependency by another CSAR, then the change is not allowed
        transitiveDependencies.stream().filter(someTransitiveDependency -> someTransitiveDependency.getName().equals(newDependency.getName())
                && !someTransitiveDependency.getVersion().equals(newDependency.getVersion())).findFirst().ifPresent(dependency -> {
                    throw new VersionConflictException(
                            "Dependency conflict between [" + toString(newDependency) + "] and another transitive dependency [" + toString(dependency) + "].");
                });

        // If the new dependency has transitives dependencies, and if those are already used in the topology, then they must be of the same version
        final Set<CSARDependency> newTransitivesDependencies = csarDependencyLoader.getDependencies(newDependency.getName(), newDependency.getVersion());
        if (!newTransitivesDependencies.isEmpty()) {
            // Issue : a new dependency changes the version of a transitive dependency which only affects itself should be accepted
            // Compute direct dependencies in the topology
            Set<CSARDependency> directDependencies =  Sets.union(
                topologyServiceCore.getIndexedNodeTypesFromTopology(topology, false, false, true)
                    .values()
                    .stream()
                    .map(type -> new CSARDependency(type.getArchiveName(), type.getArchiveVersion()))
                    .collect(Collectors.toSet()),
                topologyServiceCore.getIndexedRelationshipTypesFromTopology(topology, true)
                    .values()
                    .stream()
                    .map(type -> new CSARDependency(type.getArchiveName(), type.getArchiveVersion()))
                    .collect(Collectors.toSet())
            );

            // If a transitive dependency induced by the changed is also a dependency in the topology, then they should be of the same version.
            newTransitivesDependencies.forEach(newTransitiveDependency -> {
                Sets.union(directDependencies, transitiveDependencies).stream()
                        .filter(topologyDep -> topologyDep.getName().equals(newTransitiveDependency.getName())
                                && !topologyDep.getVersion().equals(newTransitiveDependency.getVersion()))
                        .findFirst().ifPresent(dependency -> {
                            throw new VersionConflictException("Conflict between a transitive dependency of [" + toString(newDependency) + "] >> ["
                                    + toString(newTransitiveDependency) + "] and another dependency [" + toString(dependency) + "]");
                        });
            });
        }
    }

    private ToscaTypeLoader initializeTypeLoader(Topology topology, boolean failOnTypeNotFound) {
        // FIXME we should use ToscaContext here, and why not allowing the caller to pass ona Context?
        ToscaTypeLoader loader = new ToscaTypeLoader(csarDependencyLoader);
        Map<String, NodeType> nodeTypes = topologyServiceCore.getIndexedNodeTypesFromTopology(topology, false, false, failOnTypeNotFound);
        Map<String, RelationshipType> relationshipTypes = topologyServiceCore.getIndexedRelationshipTypesFromTopology(topology, failOnTypeNotFound);
        if (topology.getNodeTemplates() != null) {
            for (NodeTemplate nodeTemplate : topology.getNodeTemplates().values()) {
                NodeType nodeType = nodeTypes.get(nodeTemplate.getType());
                // just load found types.
                // the type might be null when failOnTypeNotFound is set to false.
                if (nodeType != null) {
                    loader.loadType(nodeTemplate.getType(), csarDependencyLoader.buildDependencyBean(nodeType.getArchiveName(), nodeType.getArchiveVersion()));
                }
                if (nodeTemplate.getRelationships() != null) {

                    for (RelationshipTemplate relationshipTemplate : nodeTemplate.getRelationships().values()) {
                        RelationshipType relationshipType = relationshipTypes.get(relationshipTemplate.getType());
                        // just load found types.
                        // the type might be null when failOnTypeNotFound is set to false.
                        if (relationshipType != null) {
                            loader.loadType(relationshipTemplate.getType(),
                                    csarDependencyLoader.buildDependencyBean(relationshipType.getArchiveName(), relationshipType.getArchiveVersion()));
                        }
                    }
                }
            }
        }
        if (topology.getSubstitutionMapping() != null && topology.getSubstitutionMapping().getSubstitutionType() != null) {
            NodeType substitutionType = topology.getSubstitutionMapping().getSubstitutionType();
            loader.loadType(substitutionType.getElementId(),
                    csarDependencyLoader.buildDependencyBean(substitutionType.getArchiveName(), substitutionType.getArchiveVersion()));
        }
        return loader;
    }

    /**
     * Find replacements nodes for a node template
     *
     * @param nodeTemplateName the node to search for replacements
     * @param topology the topology
     * @return all possible replacement types for this node
     */
    @SneakyThrows(IOException.class)
    public NodeType[] findReplacementForNode(String nodeTemplateName, Topology topology) {
        NodeTemplate nodeTemplate = topology.getNodeTemplates().get(nodeTemplateName);
        Map<String, Map<String, Set<String>>> nodeTemplatesToFilters = Maps.newHashMap();
        Entry<String, NodeTemplate> nodeTempEntry = Maps.immutableEntry(nodeTemplateName, nodeTemplate);
        NodeType indexedNodeType = toscaTypeSearchService.getRequiredElementInDependencies(NodeType.class, nodeTemplate.getType(), topology.getDependencies());
        processNodeTemplate(topology, nodeTempEntry, nodeTemplatesToFilters);
        List<SuggestionsTask> topoTasks = searchForNodeTypes(topology.getWorkspace(), nodeTemplatesToFilters,
                MapUtil.newHashMap(new String[] { nodeTemplateName }, new NodeType[] { indexedNodeType }));

        if (CollectionUtils.isEmpty(topoTasks)) {
            return null;
        }
        return topoTasks.get(0).getSuggestedNodeTypes();
    }

    private void addFilters(String nodeTempName, String filterKey, String filterValueToAdd, Map<String, Map<String, Set<String>>> nodeTemplatesToFilters) {
        Map<String, Set<String>> filters = nodeTemplatesToFilters.get(nodeTempName);
        if (filters == null) {
            filters = Maps.newHashMap();
        }
        Set<String> filterValues = filters.get(filterKey);
        if (filterValues == null) {
            filterValues = Sets.newHashSet();
        }

        filterValues.add(filterValueToAdd);
        filters.put(filterKey, filterValues);
        nodeTemplatesToFilters.put(nodeTempName, filters);
    }

    /**
     * Process a node template to retrieve filters for node replacements search.
     *
     * TODO cleanup this method, better return a filter for node rather than adding it to a parameter list.
     *
     * @param topology The topology for which to search filters.
     * @param nodeTempEntry The node template for which to find replacement filter.
     * @param nodeTemplatesToFilters The map of filters in which to add the filter for the new Node.
     */
    public void processNodeTemplate(final Topology topology, final Entry<String, NodeTemplate> nodeTempEntry,
            Map<String, Map<String, Set<String>>> nodeTemplatesToFilters) {
        String capabilityFilterKey = "capabilities.type";
        String requirementFilterKey = "requirements.type";
        NodeTemplate template = nodeTempEntry.getValue();
        Map<String, RelationshipTemplate> relTemplates = template.getRelationships();

        nodeTemplatesToFilters.put(nodeTempEntry.getKey(), null);

        // process the node template source of relationships
        if (relTemplates != null && !relTemplates.isEmpty()) {
            for (RelationshipTemplate relationshipTemplate : relTemplates.values()) {
                addFilters(nodeTempEntry.getKey(), requirementFilterKey, relationshipTemplate.getRequirementType(), nodeTemplatesToFilters);
            }
        }

        // process the node template target of relationships
        List<RelationshipTemplate> relTemplatesTargetRelated = topologyServiceCore.getTargetRelatedRelatonshipsTemplate(nodeTempEntry.getKey(),
                topology.getNodeTemplates());
        for (RelationshipTemplate relationshipTemplate : relTemplatesTargetRelated) {
            addFilters(nodeTempEntry.getKey(), capabilityFilterKey, relationshipTemplate.getRequirementType(), nodeTemplatesToFilters);
        }

    }

    private NodeType[] getIndexedNodeTypesFromSearchResponse(final GetMultipleDataResult<NodeType> searchResult, final NodeType toExcludeIndexedNodeType)
            throws IOException {
        NodeType[] toReturnArray = null;
        for (int j = 0; j < searchResult.getData().length; j++) {
            NodeType nodeType = searchResult.getData()[j];
            if (toExcludeIndexedNodeType == null || !nodeType.getId().equals(toExcludeIndexedNodeType.getId())) {
                toReturnArray = ArrayUtils.add(toReturnArray, nodeType);
            }
        }
        return toReturnArray;
    }

    /**
     * Search for nodeTypes given some filters. Apply AND filter strategy when multiple values for a filter key.
     */
    public List<SuggestionsTask> searchForNodeTypes(String workspace, Map<String, Map<String, Set<String>>> nodeTemplatesToFilters,
            Map<String, NodeType> toExcludeIndexedNodeTypes) throws IOException {
        if (nodeTemplatesToFilters == null || nodeTemplatesToFilters.isEmpty()) {
            return null;
        }
        List<SuggestionsTask> toReturnTasks = Lists.newArrayList();
        for (Map.Entry<String, Map<String, Set<String>>> nodeTemplatesToFiltersEntry : nodeTemplatesToFilters.entrySet()) {
            Map<String, String[]> formattedFilters = Maps.newHashMap();
            Map<String, FilterValuesStrategy> filterValueStrategy = Maps.newHashMap();
            NodeType[] data = null;
            if (nodeTemplatesToFiltersEntry.getValue() != null) {
                for (Map.Entry<String, Set<String>> filterEntry : nodeTemplatesToFiltersEntry.getValue().entrySet()) {
                    formattedFilters.put(filterEntry.getKey(), filterEntry.getValue().toArray(new String[filterEntry.getValue().size()]));
                    // AND strategy if multiple values
                    filterValueStrategy.put(filterEntry.getKey(), FilterValuesStrategy.AND);
                }

                // retrieve only non abstract components
                formattedFilters.put("abstract", ArrayUtils.toArray("false"));
                // use topology workspace + global workspace
                formattedFilters.put("workspace", ArrayUtils.toArray(workspace, "ALIEN_GLOBAL_WORKSPACE"));

                GetMultipleDataResult<NodeType> searchResult = alienDAO.search(NodeType.class, null, formattedFilters, filterValueStrategy, 20);
                data = getIndexedNodeTypesFromSearchResponse(searchResult, toExcludeIndexedNodeTypes.get(nodeTemplatesToFiltersEntry.getKey()));
            }
            TaskCode taskCode = data == null || data.length < 1 ? TaskCode.IMPLEMENT : TaskCode.REPLACE;
            SuggestionsTask task = new SuggestionsTask();
            task.setNodeTemplateName(nodeTemplatesToFiltersEntry.getKey());
            task.setComponent(toExcludeIndexedNodeTypes.get(nodeTemplatesToFiltersEntry.getKey()));
            task.setCode(taskCode);
            task.setSuggestedNodeTypes(data);
            toReturnTasks.add(task);
        }

        return toReturnTasks;
    }

    /**
     * Check that the user has enough rights for a given topology.
     *
     * @param topology The topology for which to check roles.
     * @param applicationRoles The roles required to edit the topology for an application.
     */
    private void checkAuthorizations(Topology topology, ApplicationRole[] applicationRoles, Role[] roles) {
        Csar relatedCsar = ToscaContext.get().getArchive(topology.getArchiveName(), topology.getArchiveVersion());
        if (Objects.equals(relatedCsar.getDelegateType(), ArchiveDelegateType.APPLICATION.toString())) {
            String applicationId = relatedCsar.getDelegateId();
            Application application = appService.getOrFail(applicationId);
            AuthorizationUtil.checkAuthorizationForApplication(application, applicationRoles);
        } else {
            AuthorizationUtil.checkHasOneRoleIn(roles);
        }
    }

    /**
     * Check that the current user can retrieve the given topology.
     *
     * @param topology The topology that is subject to being updated.
     */
    @ToscaContextual
    public void checkAccessAuthorizations(Topology topology) {
        checkAuthorizations(topology,
                new ApplicationRole[] { ApplicationRole.APPLICATION_MANAGER, ApplicationRole.APPLICATION_DEVOPS, ApplicationRole.APPLICATION_USER },
                new Role[] { Role.COMPONENTS_BROWSER, Role.ARCHITECT });
    }

    /**
     * Check that the current user can update the given topology.
     *
     * @param topology The topology that is subject to being updated.
     */
    @ToscaContextual
    public void checkEditionAuthorizations(Topology topology) {
        checkAuthorizations(topology, new ApplicationRole[] { ApplicationRole.APPLICATION_MANAGER, ApplicationRole.APPLICATION_DEVOPS },
                new Role[] { Role.ARCHITECT });
    }

    /**
     * Build a node template
     *
     * @param dependencies the dependencies on which new node will be constructed
     * @param indexedNodeType the type of the node
     * @param templateToMerge the template that can be used to merge into the new node template
     * @return new constructed node template
     */
    public NodeTemplate buildNodeTemplate(Set<CSARDependency> dependencies, NodeType indexedNodeType, NodeTemplate templateToMerge) {
        return topologyServiceCore.buildNodeTemplate(dependencies, indexedNodeType, templateToMerge);
    }

    private CSARDependency getDependencyWithName(Topology topology, String archiveName) {
        for (CSARDependency dependency : topology.getDependencies()) {
            if (dependency.getName().equals(archiveName)) {
                return dependency;
            }
        }
        return null;
    }

    /**
     * Load a type into the topology (add dependency for this new type, upgrade if necessary ...)
     *
     * @param topology the topology
     * @param element the element to load
     * @param <T> tosca element type
     * @return The real loaded element if element given in argument is from older archive than topology's dependencies
     */
    @SuppressWarnings("unchecked")
    public <T extends AbstractToscaType> T loadType(Topology topology, T element) {
        String type = element.getElementId();
        String archiveName = element.getArchiveName();
        String archiveVersion = element.getArchiveVersion();
        CSARDependency toLoadDependency = csarDependencyLoader.buildDependencyBean(archiveName, archiveVersion);

        // if the dependency is not yet in the topology, then check there is no conflict with the others
        if (!topology.getDependencies().contains(toLoadDependency)) {
            this.checkDependenciesConflict(toLoadDependency, topology.getDependencies(), topology);
        }

        // FIXME Transitive dependencies could change here and thus types be affected ?
        ToscaTypeLoader typeLoader = initializeTypeLoader(topology, true);
        typeLoader.loadType(type, toLoadDependency);
        for (CSARDependency updatedDependency : typeLoader.getLoadedDependencies()) {
            ToscaContext.get().updateDependency(updatedDependency);
        }
        // TODO update csar dependencies also ?
        topology.setDependencies(typeLoader.getLoadedDependencies());
        return element;
    }

    public void unloadType(Topology topology, String... types) {
        // make sure to set the failOnTypeNotFound to false, to deal with topology recovering when a type is deleted from a dependency
        ToscaTypeLoader typeLoader = initializeTypeLoader(topology, false);
        for (String type : types) {
            typeLoader.unloadType(type);
        }
        // FIXME if a dependency is just removed don't add it back
        for (CSARDependency updatedDependency : typeLoader.getLoadedDependencies()) {
            ToscaContext.get().updateDependency(updatedDependency);
        }
        // TODO update csar dependencies also ?
        topology.setDependencies(typeLoader.getLoadedDependencies());
    }

    /**
     * Throw an UpdateTopologyException if the topology is released
     *
     * @param topology topology to be checked
     */
    public void throwsErrorIfReleased(Topology topology) {
        if (isReleased(topology)) {
            throw new UpdateTopologyException("The topology " + topology.getId() + " cannot be updated because it's released");
        }
    }

    /**
     * True when an topology is released.
     */
    public boolean isReleased(Topology topology) {
        return !VersionUtil.isSnapshot(topology.getArchiveVersion());
    }

    public void isUniqueNodeTemplateName(Topology topology, String newNodeTemplateName) {
        if (topology.getNodeTemplates() != null && topology.getNodeTemplates().containsKey(newNodeTemplateName)) {
            log.debug("Add Node Template <{}> impossible (already exists)", newNodeTemplateName);
            // a node template already exist with the given name.
            throw new AlreadyExistException(
                    "A node template with the given name " + newNodeTemplateName + " already exists in the topology " + topology.getId() + ".");
        }
    }

    public void rebuildDependencies(Topology topology) {
        ToscaTypeLoader typeLoader = initializeTypeLoader(topology, true);
        topology.setDependencies(typeLoader.getLoadedDependencies());
    }

    private String toString(CSARDependency dependency) {
        return dependency.getName() + ":" + dependency.getVersion();
    }

}
