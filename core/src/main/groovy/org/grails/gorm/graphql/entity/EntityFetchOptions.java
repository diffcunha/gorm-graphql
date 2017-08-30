package org.grails.gorm.graphql.entity;

import graphql.language.Field;
import graphql.language.Selection;
import graphql.language.SelectionSet;
import graphql.schema.DataFetchingEnvironment;
import org.grails.datastore.gorm.GormEnhancer;
import org.grails.datastore.mapping.model.PersistentEntity;
import org.grails.datastore.mapping.model.types.Association;
import org.grails.datastore.mapping.model.types.ToMany;
import org.grails.datastore.mapping.model.types.ToOne;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Helper class to determine which properties should be eagerly
 * fetched based on the fields in a {@link DataFetchingEnvironment}.
 *
 * @author James Kleeh
 * @since 1.0.0
 */
public class EntityFetchOptions {

    private Map<String, Association> associations = new LinkedHashMap<>();
    protected PersistentEntity entity;
    protected Set<String> associationNames;
    protected String propertyName;

    private static final String JOIN = "join";
    private static final String FETCH = "fetch";

    public EntityFetchOptions(Class<?> entityClass) {
        this(GormEnhancer.findStaticApi(entityClass).getGormPersistentEntity(), null);
    }

    public EntityFetchOptions(PersistentEntity entity) {
        this(entity, null);
    }

    /**
     * Designed for use when a projection query is used. The fetch arguments
     * need prepended with the projection property name.
     *
     * @param entity The {@link PersistentEntity} being queried
     * @param projectionName The name of the property being projected
     */
    public EntityFetchOptions(PersistentEntity entity, String projectionName) {
        if (entity == null) {
            throw new IllegalArgumentException("Cannot retrieve fetch options for a null entity. Is GORM initialized?");
        }

        this.entity = entity;
        this.propertyName = projectionName;
        for (Association association : entity.getAssociations()) {
            associations.put(association.getName(), association);
        }

        associationNames = associations.keySet();
    }

    /**
     * @return The associations of the {@link PersistentEntity}. The key
     * is the property name and the value is the association.
     */
    public Map<String, Association> getAssociations() {
        return associations;
    }

    protected boolean isForeignKeyInChild(Association association) {
        return association instanceof ToOne && ((ToOne) association).isForeignKeyInChild() || association instanceof ToMany;
    }

    protected void handleField(String parentName, Field selectedField, Set<String> joinProperties) {
        String resolvedName;

        if (parentName != null) {
            resolvedName = parentName + "." + selectedField.getName();
        } else {
            resolvedName = selectedField.getName();
        }

        Association association = associations.get(selectedField.getName());
        PersistentEntity entity = association.getAssociatedEntity();

        if (entity == null) {
            joinProperties.add(resolvedName);
            return;
        }

        final SelectionSet set = selectedField.getSelectionSet();
        List<Selection> selections = (set == null ? new ArrayList<>() : set.getSelections());

        if (!association.isEmbedded()) {
            if (isForeignKeyInChild(association)) {
                joinProperties.add(resolvedName);
            }
            else if (selections.size() == 1 && selections.get(0) instanceof Field) {
                Field field = (Field) selections.get(0);
                if (!entity.isIdentityName(field.getName())) {
                    joinProperties.add(resolvedName);
                }
            }
            else {
                joinProperties.add(resolvedName);
            }
        }

        List<Field> fields = new ArrayList<>();

        selections.parallelStream()
                .filter(Field.class::isInstance)
                .map(Field.class::cast)
                .forEach((Field field) -> {
                    if (field.getName().equals(association.getReferencedPropertyName())) {
                        if (field.getSelectionSet() != null) {

                            List<Field> nestedFields = field
                                    .getSelectionSet()
                                    .getSelections()
                                    .parallelStream()
                                    .filter(Field.class::isInstance)
                                    .map(Field.class::cast)
                                    .collect(Collectors.toList());

                            joinProperties.addAll(getJoinProperties(nestedFields));
                        }
                    }
                    else {
                        fields.add(field);
                    }
                });

        joinProperties.addAll(new EntityFetchOptions(entity, resolvedName).getJoinProperties(fields));
    }

    /**
     * Designed for internal use to inspect nested selections
     *
     * @param fields The list of fields to search
     * @return The list of properties to eagerly fetch
     */
    public Set<String> getJoinProperties(List<Field> fields) {
        Set<String> joinProperties = new HashSet<>();

        if (fields != null) {
            fields.stream()
                    .filter(field -> associationNames.contains(field.getName()))
                    .forEach(field -> handleField(propertyName, field, joinProperties));
        }

        return joinProperties;
    }

    /**
     * Inspects the environment for requested fields and compares
     * against the {@link PersistentEntity} associations to determine
     * which fields should be eagerly fetched.
     *
     * @param environment The data fetching environment
     * @return The list of properties to eagerly fetch
     */
    public Set<String> getJoinProperties(DataFetchingEnvironment environment) {
        List<Field> fields = new ArrayList<>();
        List<Field> environmentFields = environment.getFields();

        if (environmentFields != null) {
            fields = environmentFields.parallelStream()
                    .filter(field -> field.getSelectionSet() != null)
                    .flatMap(field -> field.getSelectionSet().getSelections().parallelStream())
                    .filter(Field.class::isInstance)
                    .map(Field.class::cast)
                    .collect(Collectors.toList());
        }

        return getJoinProperties(fields);
    }

    /**
     * Creates the fetch argument prepared to pass to {@link grails.gorm.DetachedCriteria#list(Map)}
     *
     * @param properties The properties to fetch
     * @return The fetch argument
     */
    public Map<String, Map> getFetchArgument(Set<String> properties) {
        if (properties.isEmpty()) {
            return new LinkedHashMap<>();
        }
        Map<String, Map> arguments = new LinkedHashMap<>(1);
        Map<String, String> joins = new LinkedHashMap<>(properties.size());

        for (String prop: properties) {
            joins.put(prop, JOIN);
        }
        arguments.put(FETCH, joins);
        return arguments;
    }

    /**
     * Inspects the environment for requested fields and compares
     * against the {@link PersistentEntity} associations to determine
     * which fields should be eagerly fetched. Creates the fetch argument
     * prepared to pass to {@link grails.gorm.DetachedCriteria#list(Map)}
     *
     * @param environment The fetching environment
     * @return The fetch argument
     */
    public Map<String, Map> getFetchArgument(DataFetchingEnvironment environment) {
        return getFetchArgument(getJoinProperties(environment));
    }

}