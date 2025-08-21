/*
 * Copyright 2002-2021 the original author or authors.
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

package org.springframework.web.method.annotation;

import java.beans.PropertyEditor;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.Part;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.core.MethodParameter;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.core.convert.converter.Converter;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ValueConstants;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.UriComponentsContributor;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartRequest;
import org.springframework.web.multipart.MultipartResolver;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.multipart.support.MultipartResolutionDelegate;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Resolves method arguments annotated with @{@link RequestParam}, arguments of
 * type {@link MultipartFile} in conjunction with Spring's {@link MultipartResolver}
 * abstraction, and arguments of type {@code javax.servlet.http.Part} in conjunction
 * with Servlet 3.0 multipart requests. This resolver can also be created in default
 * resolution mode in which simple types (int, long, etc.) not annotated with
 * {@link RequestParam @RequestParam} are also treated as request parameters with
 * the parameter name derived from the argument name.
 *
 * <p>If the method parameter type is {@link Map}, the name specified in the
 * annotation is used to resolve the request parameter String value. The value is
 * then converted to a {@link Map} via type conversion assuming a suitable
 * {@link Converter} or {@link PropertyEditor} has been registered.
 * Or if a request parameter name is not specified the
 * {@link RequestParamMapMethodArgumentResolver} is used instead to provide
 * access to all request parameters in the form of a map.
 *
 * <p>A {@link WebDataBinder} is invoked to apply type conversion to resolved request
 * header values that don't yet match the method parameter type.
 *
 * @author Arjen Poutsma
 * @author Rossen Stoyanchev
 * @author Brian Clozel
 * @since 3.1
 * @see RequestParamMapMethodArgumentResolver
 */
public class RequestParamMethodArgumentResolver extends AbstractNamedValueMethodArgumentResolver
		implements UriComponentsContributor {

	private static final TypeDescriptor STRING_TYPE_DESCRIPTOR = TypeDescriptor.valueOf(String.class);

	private final boolean useDefaultResolution;


	/**
	 * Create a new {@link RequestParamMethodArgumentResolver} instance.
	 * @param useDefaultResolution in default resolution mode a method argument
	 * that is a simple type, as defined in {@link BeanUtils#isSimpleProperty},
	 * is treated as a request parameter even if it isn't annotated, the
	 * request parameter name is derived from the method parameter name.
	 */
	public RequestParamMethodArgumentResolver(boolean useDefaultResolution) {
		this.useDefaultResolution = useDefaultResolution;
	}

	/**
	 * Create a new {@link RequestParamMethodArgumentResolver} instance.
	 * @param beanFactory a bean factory used for resolving  ${...} placeholder
	 * and #{...} SpEL expressions in default values, or {@code null} if default
	 * values are not expected to contain expressions
	 * @param useDefaultResolution in default resolution mode a method argument
	 * that is a simple type, as defined in {@link BeanUtils#isSimpleProperty},
	 * is treated as a request parameter even if it isn't annotated, the
	 * request parameter name is derived from the method parameter name.
	 */
	public RequestParamMethodArgumentResolver(@Nullable ConfigurableBeanFactory beanFactory,
			boolean useDefaultResolution) {

		super(beanFactory);
		this.useDefaultResolution = useDefaultResolution;
	}


	/**
	 * Supports the following:
	 * <ul>
	 * <li>@RequestParam-annotated method arguments.
	 * This excludes {@link Map} params where the annotation does not specify a name.
	 * See {@link RequestParamMapMethodArgumentResolver} instead for such params.
	 * <li>Arguments of type {@link MultipartFile} unless annotated with @{@link RequestPart}.
	 * <li>Arguments of type {@code Part} unless annotated with @{@link RequestPart}.
	 * <li>In default resolution mode, simple type arguments even if not with @{@link RequestParam}.
	 * </ul>
	 */
	@Override
	public boolean supportsParameter(MethodParameter parameter) {
		if (parameter.hasParameterAnnotation(RequestParam.class)) {
			// 如果参数被 @RequestParam 注解修饰
			if (Map.class.isAssignableFrom(parameter.nestedIfOptional().getNestedParameterType())) {
				// 且该参数类型是 Map 的子类（如 HashMap、LinkedHashMap 等）
				// 则需要检查 @RequestParam 是否指定了 name 属性
				RequestParam requestParam = parameter.getParameterAnnotation(RequestParam.class);
				return (requestParam != null && StringUtils.hasText(requestParam.name()));
				// 只有当 @RequestParam 指定了 name 时才支持，否则由 RequestParamMapMethodArgumentResolver 处理
			} else {
				// 对于非 Map 类型的 @RequestParam 参数，直接支持
				return true;
			}
		} else {
			// 如果没有 @RequestParam 注解，则判断是否为其他类型的参数
			if (parameter.hasParameterAnnotation(RequestPart.class)) {
				// 如果是 @RequestPart 注解，则不支持（由 RequestPartMethodArgumentResolver 处理）
				return false;
			}

			// 获取嵌套的参数类型（处理 Optional<T> 场景）
			parameter = parameter.nestedIfOptional();

			if (MultipartResolutionDelegate.isMultipartArgument(parameter)) {
				// 如果是 MultipartFile 或 Part 类型（用于文件上传），则支持
				return true;
			} else if (this.useDefaultResolution) {
				// 如果启用了默认解析模式（useDefaultResolution = true）
				// 则对简单类型（如 int、String、long 等）也进行自动解析
				// 即使没有 @RequestParam 注解也会当作请求参数处理
				return BeanUtils.isSimpleProperty(parameter.getNestedParameterType());
			} else {
				// 否则不支持
				return false;
			}
		}

	}

	@Override
	protected NamedValueInfo createNamedValueInfo(MethodParameter parameter) {
		RequestParam ann = parameter.getParameterAnnotation(RequestParam.class);
		return (ann != null ? new RequestParamNamedValueInfo(ann) : new RequestParamNamedValueInfo());
	}

	/**
	 * 根据参数名解析请求中的参数值
	 *
	 * @param name       请求参数的名称，用于从请求中查找对应的参数值
	 *                   --如果方法参数上使用了 @RequestParam("paramName") 注解，则 name 就是注解中指定的值。
	 * 					--如果没有显式指定，则默认为方法参数的名称（通过 parameter.getParameterName() 获取）
	 * @param parameter  方法参数的元数据信息，包含参数类型、注解等信息
	 * @param request    当前的请求对象，用于访问请求参数和文件上传数据
	 * @return 解析出的参数值，可能为 String、String[]、MultipartFile 或 List<MultipartFile>
	 * @throws Exception 解析过程中可能抛出的异常
	 */
	@Override
	@Nullable
	protected Object resolveName(String name, MethodParameter parameter, NativeWebRequest request) throws Exception {
		// 1. 尝试从 multipart 请求中解析参数（文件上传场景）
		HttpServletRequest servletRequest = request.getNativeRequest(HttpServletRequest.class);

		if (servletRequest != null) {
			// 使用 MultipartResolutionDelegate 解析 multipart 参数
			// 如果是文件上传请求，会尝试解析为 MultipartFile 或 List<MultipartFile>
			Object mpArg = MultipartResolutionDelegate.resolveMultipartArgument(name, parameter, servletRequest);

			if (mpArg != MultipartResolutionDelegate.UNRESOLVABLE) {
				// 如果成功解析出 multipart 参数，则直接返回
				return mpArg;
			}
		}

		// 2. 如果不是 multipart 请求，或未找到 multipart 参数，则尝试从普通请求中获取
		Object arg = null;
		MultipartRequest multipartRequest = request.getNativeRequest(MultipartRequest.class);

		if (multipartRequest != null) {
			// 获取指定名称的文件列表
			List<MultipartFile> files = multipartRequest.getFiles(name);
			if (!files.isEmpty()) {
				// 如果只有一个文件，返回单个 MultipartFile 对象
				// 如果有多个文件，返回 List<MultipartFile> 列表
				arg = (files.size() == 1 ? files.get(0) : files);
			}
		}

		// 3. 如果仍未找到文件参数，则尝试从标准 HTTP 请求参数中获取
		if (arg == null) {
			// *解析参数值：通过 request.getParameterValues 方式获取请求参数
			// 当通过多部分解析未获取到参数值时，尝试从标准HTTP请求参数中获取
			// getParameterValues 方法可以处理同名的多个参数值（如复选框等场景）
			String[] paramValues = request.getParameterValues(name);

			if (paramValues != null) {
				// 如果参数值存在，根据参数数量决定返回单个值还是数组
				// 当只有一个参数值时，返回该值本身（String 类型）
				// 当有多个同名参数值时，返回整个数组（String[] 类型）
				// 这种设计支持了 HTML 表单中多选框等可以提交多个同名参数的场景
				arg = (paramValues.length == 1 ? paramValues[0] : paramValues);
			}
		}

		return arg;
	}


	@Override
	protected void handleMissingValue(String name, MethodParameter parameter, NativeWebRequest request)
			throws Exception {

		handleMissingValueInternal(name, parameter, request, false);
	}

	@Override
	protected void handleMissingValueAfterConversion(
			String name, MethodParameter parameter, NativeWebRequest request) throws Exception {

		handleMissingValueInternal(name, parameter, request, true);
	}

	protected void handleMissingValueInternal(
			String name, MethodParameter parameter, NativeWebRequest request, boolean missingAfterConversion)
			throws Exception {

		HttpServletRequest servletRequest = request.getNativeRequest(HttpServletRequest.class);
		if (MultipartResolutionDelegate.isMultipartArgument(parameter)) {
			if (servletRequest == null || !MultipartResolutionDelegate.isMultipartRequest(servletRequest)) {
				throw new MultipartException("Current request is not a multipart request");
			}
			else {
				throw new MissingServletRequestPartException(name);
			}
		}
		else {
			throw new MissingServletRequestParameterException(name,
					parameter.getNestedParameterType().getSimpleName(), missingAfterConversion);
		}
	}

	@Override
	public void contributeMethodArgument(MethodParameter parameter, @Nullable Object value,
			UriComponentsBuilder builder, Map<String, Object> uriVariables, ConversionService conversionService) {

		Class<?> paramType = parameter.getNestedParameterType();
		if (Map.class.isAssignableFrom(paramType) || MultipartFile.class == paramType || Part.class == paramType) {
			return;
		}

		RequestParam requestParam = parameter.getParameterAnnotation(RequestParam.class);
		String name = (requestParam != null && StringUtils.hasLength(requestParam.name()) ?
				requestParam.name() : parameter.getParameterName());
		Assert.state(name != null, "Unresolvable parameter name");

		parameter = parameter.nestedIfOptional();
		if (value instanceof Optional) {
			value = ((Optional<?>) value).orElse(null);
		}

		if (value == null) {
			if (requestParam != null &&
					(!requestParam.required() || !requestParam.defaultValue().equals(ValueConstants.DEFAULT_NONE))) {
				return;
			}
			builder.queryParam(name);
		}
		else if (value instanceof Collection) {
			for (Object element : (Collection<?>) value) {
				element = formatUriValue(conversionService, TypeDescriptor.nested(parameter, 1), element);
				builder.queryParam(name, element);
			}
		}
		else {
			builder.queryParam(name, formatUriValue(conversionService, new TypeDescriptor(parameter), value));
		}
	}

	@Nullable
	protected String formatUriValue(
			@Nullable ConversionService cs, @Nullable TypeDescriptor sourceType, @Nullable Object value) {

		if (value == null) {
			return null;
		}
		else if (value instanceof String) {
			return (String) value;
		}
		else if (cs != null) {
			return (String) cs.convert(value, sourceType, STRING_TYPE_DESCRIPTOR);
		}
		else {
			return value.toString();
		}
	}


	private static class RequestParamNamedValueInfo extends NamedValueInfo {

		public RequestParamNamedValueInfo() {
			super("", false, ValueConstants.DEFAULT_NONE);
		}

		public RequestParamNamedValueInfo(RequestParam annotation) {
			super(annotation.name(), annotation.required(), annotation.defaultValue());
		}
	}

}
