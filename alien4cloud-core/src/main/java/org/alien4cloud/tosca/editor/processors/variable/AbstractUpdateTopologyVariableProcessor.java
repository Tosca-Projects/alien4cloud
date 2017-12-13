package org.alien4cloud.tosca.editor.processors.variable;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import org.alien4cloud.tosca.editor.EditionContextManager;
import org.alien4cloud.tosca.editor.operations.variable.AbstractUpdateTopologyVariableOperation;
import org.alien4cloud.tosca.editor.processors.AbstractUpdateFileProcessor;
import org.alien4cloud.tosca.model.Csar;
import org.alien4cloud.tosca.model.templates.Topology;

import alien4cloud.utils.YamlParserUtil;
import lombok.SneakyThrows;

/**
 * Process an {@link AbstractUpdateTopologyVariableOperation} to update the content of a file.
 */
public abstract class AbstractUpdateTopologyVariableProcessor<T extends AbstractUpdateTopologyVariableOperation> extends AbstractUpdateFileProcessor<T> {

    @Override
    @SneakyThrows
    public void process(Csar csar, Topology topology, T operation) {
        // load if exists the corresponding variables file
        operation.setPath(getRelativeVariablesFilePath(operation));
        Properties variables = loadVariables(EditionContextManager.get().getCsar().getId(), operation);

        // update the value of the variable
        variables.put(operation.getName(), YamlParserUtil.load(operation.getExpression()));
        variables.entrySet().removeIf(entry -> ((String) entry.getKey()).contains("."));
        if (operation.getTempFileId() == null) {
            operation.setArtifactStream(new ByteArrayInputStream(YamlParserUtil.dumpAsMap(variables).getBytes(StandardCharsets.UTF_8)));
        }
        super.process(csar, topology, operation);
    }

    protected abstract String getRelativeVariablesFilePath(T operation);

    protected abstract Properties loadVariables(String archiveId, T operation);
}