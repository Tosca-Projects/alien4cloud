package alien4cloud.rest.suggestion;

import java.util.List;
import java.util.Set;

import javax.annotation.Resource;

import org.elasticsearch.mapping.MappingBuilder;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import alien4cloud.component.model.IndexedArtifactType;
import alien4cloud.component.model.IndexedCapabilityType;
import alien4cloud.component.model.IndexedNodeType;
import alien4cloud.component.model.IndexedRelationshipType;
import alien4cloud.component.model.IndexedToscaElement;
import alien4cloud.component.model.Tag;
import alien4cloud.dao.IGenericSearchDAO;
import alien4cloud.dao.model.FetchContext;
import alien4cloud.dao.model.GetMultipleDataResult;
import alien4cloud.model.application.Application;
import alien4cloud.rest.model.RestResponse;
import alien4cloud.rest.model.RestResponseBuilder;
import alien4cloud.tosca.container.model.ToscaElement;

import com.google.common.collect.Sets;
import com.mangofactory.swagger.annotations.ApiIgnore;

/**
 * Handle Suggestion requests.
 *
 * @author 'Igor Ngouagna'
 */
@RestController
@RequestMapping("/rest/suggest")
public class SuggestionController {
    private static final int SUGGESTION_COUNT = 10;
    private static final String TAG_FIELD = "tags";
    private static final String[] INDEXES = new String[] { ToscaElement.class.getSimpleName().toLowerCase(), Application.class.getSimpleName().toLowerCase() };
    private static final Class<?>[] CLASSES = new Class<?>[] { Application.class, IndexedNodeType.class, IndexedArtifactType.class,
            IndexedCapabilityType.class, IndexedRelationshipType.class };

    @Resource(name = "alien-es-dao")
    private IGenericSearchDAO dao;

    /**
     * Get suggestion for tags based on current tags defined on the components.
     *
     * @param tagName The name of the tag for which to get suggestion.
     * @param searchPrefix The current prefix for the tag suggestion.
     * @return A {@link RestResponse} that contains a list of suggestions for the tag key.
     */
    @ApiIgnore
    @RequestMapping(value = "/tag/{tagName}/{searchPrefix}", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public RestResponse<String[]> tagSuggest(@PathVariable String tagName, @PathVariable String searchPrefix) {
        String suggestFieldPath = TAG_FIELD.concat(".").concat(tagName);
        GetMultipleDataResult searchResult = dao.suggestSearch(INDEXES, CLASSES, suggestFieldPath, searchPrefix, FetchContext.TAG_SUGGESTION, 0,
                SUGGESTION_COUNT);
        String[] types = searchResult.getTypes();
        Set<String> tagsSuggestions = Sets.newHashSet();
        for (int i = 0; i < types.length; i++) {
            List<Tag> tags;
            if (types[i].equals(MappingBuilder.indexTypeFromClass(Application.class))) {
                Application app = (Application) searchResult.getData()[i];
                tags = app.getTags();
            } else {
                IndexedToscaElement indexedToscaElement = (IndexedToscaElement) searchResult.getData()[i];
                tags = indexedToscaElement.getTags();
            }
            addSuggestedTag(tags, tagName, searchPrefix, tagsSuggestions);
        }

        return RestResponseBuilder.<String[]> builder().data(tagsSuggestions.toArray(new String[tagsSuggestions.size()])).build();
    }

    private void addSuggestedTag(List<Tag> tags, String path, String searchPrefix, Set<String> tagsSuggestions) {
        for (Tag tag : tags) {
            String suggestion = "";
            if (path.equals("name")) {
                suggestion = tag.getName();
            } else if (path.equals("value")) {
                suggestion = tag.getValue();
            }
            if (suggestion.startsWith(searchPrefix)) {
                tagsSuggestions.add(suggestion);
            }
        }
    }
}
