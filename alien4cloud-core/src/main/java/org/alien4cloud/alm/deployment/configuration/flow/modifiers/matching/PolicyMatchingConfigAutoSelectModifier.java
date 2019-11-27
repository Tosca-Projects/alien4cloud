package org.alien4cloud.alm.deployment.configuration.flow.modifiers.matching;

import java.util.List;
import java.util.Map;

import org.alien4cloud.alm.deployment.configuration.flow.FlowExecutionContext;
import org.alien4cloud.alm.deployment.configuration.model.DeploymentMatchingConfiguration;
import org.alien4cloud.alm.deployment.configuration.model.DeploymentMatchingConfiguration.ResourceMatching;
import org.springframework.stereotype.Component;

import alien4cloud.model.orchestrators.locations.PolicyLocationResourceTemplate;
import alien4cloud.topology.task.AbstractTask;

/**
 * This matching modifier is responsible for automatic selection of location resources that are not yet selected by the user.
 *
 * It does not update topology or matching configurations, these operations are done in sub-sequent modifiers.
 */
@Component
public class PolicyMatchingConfigAutoSelectModifier extends AbstractMatchingConfigAutoSelectModifier<PolicyLocationResourceTemplate> {

    @Override
    protected Map<String, ResourceMatching> getLastUserMatches(DeploymentMatchingConfiguration matchingConfiguration) {
        return matchingConfiguration.getMatchedPolicies();
    }

    @Override
    protected Map<String, List<PolicyLocationResourceTemplate>> getAvailableMatches(FlowExecutionContext context) {
        return (Map<String, List<PolicyLocationResourceTemplate>>) context.getExecutionCache()
                .get(FlowExecutionContext.MATCHED_POLICY_LOCATION_TEMPLATES_BY_NODE_ID_MAP);
    }

    @Override
    protected void consumeNoMatchingFound(FlowExecutionContext context, AbstractTask task) {
        context.getLog().warn(task);
    }
}