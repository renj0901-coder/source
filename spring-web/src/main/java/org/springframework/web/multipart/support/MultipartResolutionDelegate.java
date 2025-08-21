/*
 * Copyright 2002-2020 the original author or authors.
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

package org.springframework.web.multipart.support;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.Part;

import org.springframework.core.MethodParameter;
import org.springframework.core.ResolvableType;
import org.springframework.lang.Nullable;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.multipart.MultipartRequest;
import org.springframework.web.util.WebUtils;

/**
 * A common delegate for {@code HandlerMethodArgumentResolver} implementations
 * which need to resolve {@link MultipartFile} and {@link Part} arguments.
 *
 * @author Juergen Hoeller
 * @since 4.3
 */
public final class MultipartResolutionDelegate {

	/**
	 * Indicates an unresolvable value.
	 */
	public static final Object UNRESOLVABLE = new Object();


	private MultipartResolutionDelegate() {
	}


	@Nullable
	public static MultipartRequest resolveMultipartRequest(NativeWebRequest webRequest) {
		MultipartRequest multipartRequest = webRequest.getNativeRequest(MultipartRequest.class);
		if (multipartRequest != null) {
			return multipartRequest;
		}
		HttpServletRequest servletRequest = webRequest.getNativeRequest(HttpServletRequest.class);
		if (servletRequest != null && isMultipartContent(servletRequest)) {
			return new StandardMultipartHttpServletRequest(servletRequest);
		}
		return null;
	}

	public static boolean isMultipartRequest(HttpServletRequest request) {
		return (WebUtils.getNativeRequest(request, MultipartHttpServletRequest.class) != null ||
				isMultipartContent(request));
	}

	private static boolean isMultipartContent(HttpServletRequest request) {
		String contentType = request.getContentType();
		return (contentType != null && contentType.toLowerCase().startsWith("multipart/"));
	}

	static MultipartHttpServletRequest asMultipartHttpServletRequest(HttpServletRequest request) {
		MultipartHttpServletRequest unwrapped = WebUtils.getNativeRequest(request, MultipartHttpServletRequest.class);
		if (unwrapped != null) {
			return unwrapped;
		}
		return new StandardMultipartHttpServletRequest(request);
	}


	public static boolean isMultipartArgument(MethodParameter parameter) {
		Class<?> paramType = parameter.getNestedParameterType();
		return (MultipartFile.class == paramType ||
				isMultipartFileCollection(parameter) || isMultipartFileArray(parameter) ||
				(Part.class == paramType || isPartCollection(parameter) || isPartArray(parameter)));
	}

	/**
	 * 解析多部分请求参数，根据方法参数类型返回相应的文件或部分对象
	 * <p>
	 * 该方法是Spring MVC处理文件上传参数的核心解析逻辑，支持多种参数类型：
	 * 1. 单个MultipartFile参数
	 * 2. MultipartFile集合参数
	 * 3. MultipartFile数组参数
	 * 4. 单个Part参数
	 * 5. Part集合参数
	 * 6. Part数组参数
	 * <p>
	 * 解析过程：
	 * 1. 首先检查请求是否为多部分请求
	 * 2. 根据方法参数的具体类型选择相应的解析策略
	 * 3. 如果需要，将普通HttpServletRequest包装为MultipartHttpServletRequest
	 * 4. 从请求中获取指定名称的文件或部分并返回
	 *
	 * @param name      请求参数名称，对应HTML表单中的name属性
	 * @param parameter 方法参数的元数据信息，包含参数类型、注解等
	 * @param request   当前的HTTP请求对象
	 * @return 解析出的参数值，可能为：
	 * - MultipartFile对象（单个文件）
	 * - List<MultipartFile>对象（多个文件）
	 * - MultipartFile[]数组（多个文件）
	 * - Part对象（单个部分）
	 * - List<Part>对象（多个部分）
	 * - Part[]数组（多个部分）
	 * - null（未找到对应参数或非多部分请求）
	 * - UNRESOLVABLE（不支持的参数类型）
	 * @throws Exception 解析过程中可能抛出的异常，如IO异常、Servlet异常等
	 */
	@Nullable
	public static Object resolveMultipartArgument(String name, MethodParameter parameter, HttpServletRequest request)
			throws Exception {

		// 尝试从请求中获取已存在的MultipartHttpServletRequest
		// 这可能是在请求处理早期由过滤器或其他组件创建的
		MultipartHttpServletRequest multipartRequest =
				WebUtils.getNativeRequest(request, MultipartHttpServletRequest.class);

		// 检查当前请求是否为多部分请求（包含文件上传）
		// 条件：已存在MultipartHttpServletRequest或Content-Type以"multipart/"开头
		boolean isMultipart = (multipartRequest != null || isMultipartContent(request));

		// 处理单个MultipartFile参数类型
		// 例如：public String upload(@RequestParam("file") MultipartFile file)
		if (MultipartFile.class == parameter.getNestedParameterType()) {
			// 如果不是多部分请求，无法获取文件，返回null
			if (!isMultipart) {
				return null;
			}
			// 如果还没有MultipartHttpServletRequest，则创建一个新的
			// 这会在首次访问多部分数据时触发解析
			if (multipartRequest == null) {
				multipartRequest = new StandardMultipartHttpServletRequest(request);
			}
			// 从请求中获取指定名称的单个文件
			// 如果有多个同名文件，只返回第一个
			return multipartRequest.getFile(name);
		}
		// 处理MultipartFile集合参数类型
		// 例如：public String upload(@RequestParam("file") List<MultipartFile> files)
		else if (isMultipartFileCollection(parameter)) {
			// 如果不是多部分请求，无法获取文件，返回null
			if (!isMultipart) {
				return null;
			}
			// 如果还没有MultipartHttpServletRequest，则创建一个新的
			if (multipartRequest == null) {
				multipartRequest = new StandardMultipartHttpServletRequest(request);
			}
			// 获取指定名称的所有文件，返回文件列表
			// 返回一个只包含MultipartFile对象的列表
			List<MultipartFile> files = multipartRequest.getFiles(name);
			// 如果列表不为空则返回，否则返回null
			return (!files.isEmpty() ? files : null);
		}
		// 处理MultipartFile数组参数类型
		// 例如：public String upload(@RequestParam("file") MultipartFile[] files)
		else if (isMultipartFileArray(parameter)) {
			// 如果不是多部分请求，无法获取文件，返回null
			if (!isMultipart) {
				return null;
			}
			// 如果还没有MultipartHttpServletRequest，则创建一个新的
			if (multipartRequest == null) {
				multipartRequest = new StandardMultipartHttpServletRequest(request);
			}
			// 获取指定名称的所有文件
			List<MultipartFile> files = multipartRequest.getFiles(name);
			// 如果列表不为空则转换为数组返回，否则返回null
			return (!files.isEmpty() ? files.toArray(new MultipartFile[0]) : null);
		}
		// 处理单个Part参数类型
		// 例如：public String upload(@RequestParam("file") Part file)
		else if (Part.class == parameter.getNestedParameterType()) {
			// 如果不是多部分请求，无法获取部分，返回null
			if (!isMultipart) {
				return null;
			}
			// 直接从Servlet 3.0请求中获取指定名称的部分
			// 这是直接使用Servlet API的方式
			return request.getPart(name);
		}
		// 处理Part集合参数类型
		// 例如：public String upload(@RequestParam("file") List<Part> parts)
		else if (isPartCollection(parameter)) {
			// 如果不是多部分请求，无法获取部分，返回null
			if (!isMultipart) {
				return null;
			}
			// 解析并获取指定名称的所有Part对象列表
			List<Part> parts = resolvePartList(request, name);
			// 如果列表不为空则返回，否则返回null
			return (!parts.isEmpty() ? parts : null);
		}
		// 处理Part数组参数类型
		// 例如：public String upload(@RequestParam("file") Part[] parts)
		else if (isPartArray(parameter)) {
			// 如果不是多部分请求，无法获取部分，返回null
			if (!isMultipart) {
				return null;
			}
			// 解析并获取指定名称的所有Part对象列表
			List<Part> parts = resolvePartList(request, name);
			// 如果列表不为空则转换为数组返回，否则返回null
			return (!parts.isEmpty() ? parts.toArray(new Part[0]) : null);
		}
		// 不支持的参数类型，返回特殊标记值
		else {
			// 返回UNRESOLVABLE表示该参数类型不支持多部分解析
			// 调用方可以根据此值决定是否使用其他解析策略
			return UNRESOLVABLE;
		}
	}


	private static boolean isMultipartFileCollection(MethodParameter methodParam) {
		return (MultipartFile.class == getCollectionParameterType(methodParam));
	}

	private static boolean isMultipartFileArray(MethodParameter methodParam) {
		return (MultipartFile.class == methodParam.getNestedParameterType().getComponentType());
	}

	private static boolean isPartCollection(MethodParameter methodParam) {
		return (Part.class == getCollectionParameterType(methodParam));
	}

	private static boolean isPartArray(MethodParameter methodParam) {
		return (Part.class == methodParam.getNestedParameterType().getComponentType());
	}

	@Nullable
	private static Class<?> getCollectionParameterType(MethodParameter methodParam) {
		Class<?> paramType = methodParam.getNestedParameterType();
		if (Collection.class == paramType || List.class.isAssignableFrom(paramType)) {
			Class<?> valueType = ResolvableType.forMethodParameter(methodParam).asCollection().resolveGeneric();
			if (valueType != null) {
				return valueType;
			}
		}
		return null;
	}

	private static List<Part> resolvePartList(HttpServletRequest request, String name) throws Exception {
		Collection<Part> parts = request.getParts();
		List<Part> result = new ArrayList<>(parts.size());
		for (Part part : parts) {
			if (part.getName().equals(name)) {
				result.add(part);
			}
		}
		return result;
	}

}
