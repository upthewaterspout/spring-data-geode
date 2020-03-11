/*
 * Copyright 2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.gemfire.repository.query;

import static org.springframework.data.gemfire.util.CollectionUtils.nullSafeSize;
import static org.springframework.data.gemfire.util.RuntimeExceptionFactory.newIllegalStateException;

import java.util.Collection;
import java.util.Collections;

import org.apache.geode.cache.query.SelectResults;

import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.data.gemfire.GemfireTemplate;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.ParametersParameterAccessor;
import org.springframework.data.repository.query.QueryMethod;
import org.springframework.data.repository.query.RepositoryQuery;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

/**
 * {@link GemfireRepositoryQuery} using plain {@link String} based OQL queries.
 * <p>
 * @author Oliver Gierke
 * @author David Turanski
 * @author John Blum
 */
public class StringBasedGemfireRepositoryQuery extends GemfireRepositoryQuery {

	private static final String INVALID_QUERY = "Paging and modifying queries are not supported";

	private boolean userDefinedQuery = false;

	private final GemfireTemplate template;

	private final QueryString query;

	/*
	 * (non-Javadoc)
	 * Constructor used for testing purposes only!
	 */
	StringBasedGemfireRepositoryQuery() {

		this.query = null;
		this.template = null;

		register(ProvidedQueryPostProcessors.LIMIT
			.processBefore(ProvidedQueryPostProcessors.IMPORT)
			.processBefore(ProvidedQueryPostProcessors.HINT)
			.processBefore(ProvidedQueryPostProcessors.TRACE));
	}

	/**
	 * Creates a new {@link StringBasedGemfireRepositoryQuery} using the given {@link GemfireQueryMethod} and
	 * {@link GemfireTemplate}. The actual query {@link String} will be looked up from the query method.
	 *
	 * @param queryMethod must not be {@literal null}.
	 * @param template must not be {@literal null}.
	 */
	public StringBasedGemfireRepositoryQuery(GemfireQueryMethod queryMethod, GemfireTemplate template) {
		this(queryMethod.getAnnotatedQuery(), queryMethod, template);
	}

	/**
	 * Creates a new {@link StringBasedGemfireRepositoryQuery} using the given query {@link String},
	 * {@link GemfireQueryMethod} and {@link GemfireTemplate}.
	 *
	 * @param query will fall back to the query annotated to the given {@link GemfireQueryMethod} if {@literal null}.
	 * @param queryMethod must not be {@literal null}.
	 * @param template must not be {@literal null}.
	 */
	public StringBasedGemfireRepositoryQuery(String query, GemfireQueryMethod queryMethod, GemfireTemplate template) {

		super(queryMethod);

		Assert.notNull(template, "GemfireTemplate must not be null");
		Assert.state(!(queryMethod.isModifyingQuery() || queryMethod.isPageQuery()), INVALID_QUERY);

		this.userDefinedQuery |= !StringUtils.hasText(query);
		this.query = QueryString.of(StringUtils.hasText(query) ? query : queryMethod.getAnnotatedQuery());
		this.template = template;

		register(ProvidedQueryPostProcessors.LIMIT
			.processBefore(ProvidedQueryPostProcessors.IMPORT)
			.processBefore(ProvidedQueryPostProcessors.HINT)
			.processBefore(ProvidedQueryPostProcessors.TRACE));
	}

	/**
	 * Sets this {@link RepositoryQuery} to be user-defined.
	 *
	 * @return this {@link RepositoryQuery}.
	 */
	public StringBasedGemfireRepositoryQuery asUserDefinedQuery() {
		this.userDefinedQuery = true;
		return this;
	}

	/**
	 * Determines whether the query represented by this {@link RepositoryQuery} is user-defined or was generated by
	 * the Spring Data {@link Repository} infrastructure.
	 *
	 * @return a boolean value indicating whether this {@link RepositoryQuery} is user-defined or was generated by
	 * the Spring Data {@link Repository} infrastructure.
	 */
	public boolean isUserDefinedQuery() {
		return this.userDefinedQuery;
	}

	/**
	 * Returns a reference to the {@link QueryString managed query}.
	 *
	 * @return a reference to the {@link QueryString managed query}.
	 * @see org.springframework.data.gemfire.repository.query.QueryString
	 */
	protected QueryString getQuery() {
		return this.query;
	}

	/**
	 * Returns a reference to the {@link GemfireTemplate} used to perform all data access and query operations.
	 *
	 * @return a reference to the {@link GemfireTemplate} used to perform all data access and query operations.
	 * @see org.springframework.data.gemfire.GemfireTemplate
	 */
	protected GemfireTemplate getTemplate() {
		return this.template;
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.repository.query.RepositoryQuery#execute(java.lang.Object[])
	 */
	@Override
	public Object execute(Object[] arguments) {

		QueryMethod queryMethod = getQueryMethod();

		QueryString query = preProcess(queryMethod, getQuery(), arguments);

		String queryString = query.toString();
		String processedQueryString = getQueryPostProcessor().postProcess(queryMethod, queryString, arguments);

		SelectResults<?> selectResults = getTemplate().find(processedQueryString, arguments);

		return postProcess(queryMethod, selectResults);
	}

	QueryString preProcess(QueryMethod queryMethod, QueryString query, Object[] arguments) {

		query = isUserDefinedQuery() ? query
			: query.fromRegion(queryMethod.getEntityInformation().getJavaType(), getTemplate().getRegion());

		ParametersParameterAccessor parameterAccessor =
			new ParametersParameterAccessor(queryMethod.getParameters(), arguments);

		for (Integer index : query.getInParameterIndexes()) {
			query = query.bindIn(toCollection(parameterAccessor.getBindableValue(index - 1)));
		}

		return query;
	}

	Object postProcess(QueryMethod queryMethod, SelectResults<?> selectResults) {

		Collection<?> collection = toCollection(selectResults);

		if (queryMethod.isCollectionQuery()) {
			return collection;
		}
		else if (queryMethod.isQueryForEntity()) {
			if (collection.isEmpty()) {
				return null;
			}
			else if (collection.size() == 1) {
				return collection.iterator().next();
			}
			else {
				throw new IncorrectResultSizeDataAccessException(1, collection.size());
			}
		}
		else if (isSingleNonEntityResult(queryMethod, collection)) {
			return collection.iterator().next();
		}
		else {
			throw newIllegalStateException("Unsupported query: %s", query.toString());
		}
	}

	@SuppressWarnings("all")
	boolean isSingleNonEntityResult(QueryMethod method, Collection<?> result) {

		Class<?> methodReturnType = method.getReturnedObjectType();

		methodReturnType = methodReturnType != null ? methodReturnType : Void.class;

		return nullSafeSize(result) == 1 && !Void.TYPE.equals(methodReturnType) && !method.isCollectionQuery();
	}

	/**
	 * Returns the given object as a Collection. Collections will be returned as is, Arrays will be converted into a
	 * Collection and all other objects will be wrapped into a single-element Collection.
	 *
	 * @param source the resulting object from the GemFire Query.
	 * @return the querying resulting object as a Collection.
	 * @see java.util.Arrays#asList(Object[])
	 * @see java.util.Collection
	 * @see org.springframework.util.CollectionUtils#arrayToList(Object)
	 * @see org.apache.geode.cache.query.SelectResults
	 */
	Collection<?> toCollection(Object source) {

		if (source instanceof SelectResults) {
			return ((SelectResults) source).asList();
		}

		if (source instanceof Collection) {
			return (Collection<?>) source;
		}

		if (source == null) {
			return Collections.emptyList();
		}

		return source.getClass().isArray() ? CollectionUtils.arrayToList(source) : Collections.singletonList(source);
	}

	enum ProvidedQueryPostProcessors implements QueryPostProcessor<Repository, String> {

		HINT {

			@Override
			public String postProcess(QueryMethod queryMethod, String query, Object... arguments) {

				if (queryMethod instanceof GemfireQueryMethod) {

					GemfireQueryMethod gemfireQueryMethod = (GemfireQueryMethod) queryMethod;

					if (gemfireQueryMethod.hasHint() && !QueryString.HINT_PATTERN.matcher(query).find()) {
						query = QueryString.of(query).withHints(gemfireQueryMethod.getHints()).toString();
					}
				}

				return query;
			}
		},

		IMPORT {

			@Override
			public String postProcess(QueryMethod queryMethod, String query, Object... arguments) {

				if (queryMethod instanceof GemfireQueryMethod) {

					GemfireQueryMethod gemfireQueryMethod = (GemfireQueryMethod) queryMethod;

					if (gemfireQueryMethod.hasImport() && !QueryString.IMPORT_PATTERN.matcher(query).find()) {
						query = QueryString.of(query).withImport(gemfireQueryMethod.getImport()).toString();
					}
				}

				return query;
			}
		},

		LIMIT {

			@Override
			public String postProcess(QueryMethod queryMethod, String query, Object... arguments) {

				if (queryMethod instanceof  GemfireQueryMethod) {

					GemfireQueryMethod gemfireQueryMethod = (GemfireQueryMethod) queryMethod;

					if (gemfireQueryMethod.hasLimit() && !QueryString.LIMIT_PATTERN.matcher(query).find()) {
						query = QueryString.of(query).withLimit(gemfireQueryMethod.getLimit()).toString();
					}
				}

				return query;
			}
		},

		TRACE {

			@Override
			public String postProcess(QueryMethod queryMethod, String query, Object... arguments) {

				if (queryMethod instanceof GemfireQueryMethod) {

					GemfireQueryMethod gemfireQueryMethod = (GemfireQueryMethod) queryMethod;

					if (gemfireQueryMethod.hasTrace() && !QueryString.TRACE_PATTERN.matcher(query).find()) {
						query = QueryString.of(query).withTrace().toString();
					}
				}

				return query;
			}
		}
	}
}
