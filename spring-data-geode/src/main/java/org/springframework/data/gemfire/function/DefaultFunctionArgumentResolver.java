/*
 * Copyright 2002-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package org.springframework.data.gemfire.function;

import java.lang.reflect.Method;

import org.apache.geode.cache.execute.Function;
import org.apache.geode.cache.execute.FunctionContext;

/**
 * {@link FunctionArgumentResolver} used to resolve {@link Object[] arguments} passed to
 * an Apache Geode {@link Function} during execution.
 *
 * @author David Turanski
 * @author John Blum
 * @see org.apache.geode.cache.execute.Function
 * @see org.apache.geode.cache.execute.FunctionContext
 * @see org.springframework.data.gemfire.function.FunctionArgumentResolver
 * @since 1.3.0
 */
class DefaultFunctionArgumentResolver implements FunctionArgumentResolver {

	private static final Object[] EMPTY_ARRAY = new Object[0];

	@Override
	public Method getFunctionAnnotatedMethod() {
		throw new UnsupportedOperationException("Not Implemented!");
	}

	@Override
	public Object[] resolveFunctionArguments(final FunctionContext functionContext) {

		return isArray(functionContext.getArguments())
			? toObjectArray((Object[]) functionContext.getArguments())
			: getArguments(functionContext);
	}

	private boolean isArray(final Object value) {
		return value != null && value.getClass().isArray();
	}

	private Object[] toObjectArray(final Object[] arguments) {

		Object[] result = new Object[arguments.length];

		System.arraycopy(arguments, 0, result, 0, arguments.length);

		return result;
	}

	private Object[] getArguments(final FunctionContext context) {

		Object arguments = context.getArguments();

		return arguments != null ? new Object[] { arguments } : EMPTY_ARRAY;
	}
}
