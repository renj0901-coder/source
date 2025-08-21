/*
 * Copyright 2002-2018 the original author or authors.
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

package org.springframework.web.servlet.mvc.method.annotation;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.List;

import javax.servlet.http.HttpServletRequest;

import org.springframework.core.Conventions;
import org.springframework.core.MethodParameter;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.validation.BindingResult;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.accept.ContentNegotiationManager;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.mvc.support.DefaultHandlerExceptionResolver;

/**
 * Resolves method arguments annotated with {@code @RequestBody} and handles return
 * values from methods annotated with {@code @ResponseBody} by reading and writing
 * to the body of the request or response with an {@link HttpMessageConverter}.
 *
 * <p>An {@code @RequestBody} method argument is also validated if it is annotated
 * with any
 * {@linkplain org.springframework.validation.annotation.ValidationAnnotationUtils#determineValidationHints
 * annotations that trigger validation}. In case of validation failure,
 * {@link MethodArgumentNotValidException} is raised and results in an HTTP 400
 * response status code if {@link DefaultHandlerExceptionResolver} is configured.
 *
 * @author Arjen Poutsma
 * @author Rossen Stoyanchev
 * @author Juergen Hoeller
 * @since 3.1
 */
public class RequestResponseBodyMethodProcessor extends AbstractMessageConverterMethodProcessor {

	/**
	 * Basic constructor with converters only. Suitable for resolving
	 * {@code @RequestBody}. For handling {@code @ResponseBody} consider also
	 * providing a {@code ContentNegotiationManager}.
	 */
	public RequestResponseBodyMethodProcessor(List<HttpMessageConverter<?>> converters) {
		super(converters);
	}

	/**
	 * Basic constructor with converters and {@code ContentNegotiationManager}.
	 * Suitable for resolving {@code @RequestBody} and handling
	 * {@code @ResponseBody} without {@code Request~} or
	 * {@code ResponseBodyAdvice}.
	 */
	public RequestResponseBodyMethodProcessor(List<HttpMessageConverter<?>> converters,
			@Nullable ContentNegotiationManager manager) {

		super(converters, manager);
	}

	/**
	 * Complete constructor for resolving {@code @RequestBody} method arguments.
	 * For handling {@code @ResponseBody} consider also providing a
	 * {@code ContentNegotiationManager}.
	 * @since 4.2
	 */
	public RequestResponseBodyMethodProcessor(List<HttpMessageConverter<?>> converters,
			@Nullable List<Object> requestResponseBodyAdvice) {

		super(converters, null, requestResponseBodyAdvice);
	}

	/**
	 * Complete constructor for resolving {@code @RequestBody} and handling
	 * {@code @ResponseBody}.
	 */
	public RequestResponseBodyMethodProcessor(List<HttpMessageConverter<?>> converters,
			@Nullable ContentNegotiationManager manager, @Nullable List<Object> requestResponseBodyAdvice) {

		super(converters, manager, requestResponseBodyAdvice);
	}


	@Override
	public boolean supportsParameter(MethodParameter parameter) {
		return parameter.hasParameterAnnotation(RequestBody.class);
	}
	// 判断方法或者类上面有没有ResponseBody
	@Override
	public boolean supportsReturnType(MethodParameter returnType) {
		return (AnnotatedElementUtils.hasAnnotation(returnType.getContainingClass(), ResponseBody.class) ||
				returnType.hasMethodAnnotation(ResponseBody.class));
	}

	/**
	 * Throws MethodArgumentNotValidException if validation fails.
	 * @throws HttpMessageNotReadableException if {@link RequestBody#required()}
	 * is {@code true} and there is no body content or if there is no suitable
	 * converter to read the content with.
	 */
	@Override
	public Object resolveArgument(MethodParameter parameter, @Nullable ModelAndViewContainer mavContainer,
			NativeWebRequest webRequest, @Nullable WebDataBinderFactory binderFactory) throws Exception {

		parameter = parameter.nestedIfOptional();
		Object arg = readWithMessageConverters(webRequest, parameter, parameter.getNestedGenericParameterType());
		String name = Conventions.getVariableNameForParameter(parameter);

		if (binderFactory != null) {
			WebDataBinder binder = binderFactory.createBinder(webRequest, arg, name);
			if (arg != null) {
				validateIfApplicable(binder, parameter);
				if (binder.getBindingResult().hasErrors() && isBindExceptionRequired(binder, parameter)) {
					throw new MethodArgumentNotValidException(parameter, binder.getBindingResult());
				}
			}
			if (mavContainer != null) {
				mavContainer.addAttribute(BindingResult.MODEL_KEY_PREFIX + name, binder.getBindingResult());
			}
		}

		return adaptArgumentIfNecessary(arg, parameter);
	}

	@Override
	protected <T> Object readWithMessageConverters(NativeWebRequest webRequest, MethodParameter parameter,
			Type paramType) throws IOException, HttpMediaTypeNotSupportedException, HttpMessageNotReadableException {

		HttpServletRequest servletRequest = webRequest.getNativeRequest(HttpServletRequest.class);
		Assert.state(servletRequest != null, "No HttpServletRequest");
		ServletServerHttpRequest inputMessage = new ServletServerHttpRequest(servletRequest);

		Object arg = readWithMessageConverters(inputMessage, parameter, paramType);
		if (arg == null && checkRequired(parameter)) {
			throw new HttpMessageNotReadableException("Required request body is missing: " +
					parameter.getExecutable().toGenericString(), inputMessage);
		}
		return arg;
	}

	protected boolean checkRequired(MethodParameter parameter) {
		RequestBody requestBody = parameter.getParameterAnnotation(RequestBody.class);
		return (requestBody != null && requestBody.required() && !parameter.isOptional());
	}

	/**
	 * 处理被@ResponseBody注解标记的方法返回值，将其序列化并写入HTTP响应体
	 * <p>
	 * 该方法是Spring MVC处理RESTful API响应的核心逻辑，负责：
	 * 1. 标记请求已被处理完毕（不需要视图解析）
	 * 2. 创建输入和输出消息对象用于HTTP通信
	 * 3. 通过HttpMessageConverter将返回值序列化为适当的格式（JSON、XML等）
	 * 4. 根据客户端Accept头和服务器支持的内容类型进行内容协商
	 * <p>
	 * 处理流程：
	 * 1. 设置ModelAndViewContainer的requestHandled标志为true，表示请求处理完成
	 * 2. 创建ServletServerHttpRequest和ServletServerHttpResponse对象封装HTTP消息
	 * 3. 调用writeWithMessageConverters方法执行实际的序列化和写入操作
	 * 4. 异常处理由调用方（DispatcherServlet）统一处理
	 *
	 * @param returnValue  控制器方法的返回值，可以是任何Java对象
	 * @param returnType   返回值的方法参数信息，包含返回类型和注解等元数据
	 * @param mavContainer ModelAndView容器，用于存储模型数据和视图信息
	 * @param webRequest   当前的Web请求对象，提供对HTTP请求和响应的访问
	 * @throws IOException                         IO操作异常
	 * @throws HttpMediaTypeNotAcceptableException 客户端Accept头指定的媒体类型不被支持
	 * @throws HttpMessageNotWritableException     返回值无法被任何消息转换器序列化
	 *                                             <p>
	 *                                             使用示例：
	 *                                             <pre>{@code
	 *                                             @RestController
	 *                                             public class UserController {
	 *                                                 @GetMapping("/user/{id}")
	 *                                                 @ResponseBody
	 *                                                 public User getUser(@PathVariable Long id) {
	 *                                                     return userService.findById(id);
	 *                                                 }
	 *
	 *                                                 @PostMapping("/user")
	 *                                                 public ResponseEntity<User> createUser(@RequestBody User user) {
	 *                                                     User savedUser = userService.save(user);
	 *                                                     return ResponseEntity.ok(savedUser);
	 *                                                 }
	 *                                             }
	 *                                             }</pre>
	 * @see #writeWithMessageConverters(Object, MethodParameter, ServletServerHttpRequest, ServletServerHttpResponse)
	 * @see org.springframework.http.converter.HttpMessageConverter
	 * @see org.springframework.web.accept.ContentNegotiationManager
	 */
	@Override
	public void handleReturnValue(@Nullable Object returnValue, MethodParameter returnType,
								  ModelAndViewContainer mavContainer, NativeWebRequest webRequest)
			throws IOException, HttpMediaTypeNotAcceptableException, HttpMessageNotWritableException {

		// 标记请求已被处理，不需要进一步的视图解析和渲染
		// 这告诉DispatcherServlet不需要查找视图来渲染ModelAndView
		mavContainer.setRequestHandled(true);

		// 创建ServletServerHttpRequest对象，封装HTTP请求信息
		// 用于在消息转换过程中访问请求头、请求方法等信息
		ServletServerHttpRequest inputMessage = createInputMessage(webRequest);

		// 创建ServletServerHttpResponse对象，封装HTTP响应信息
		// 用于向客户端写入响应头和响应体数据
		ServletServerHttpResponse outputMessage = createOutputMessage(webRequest);

		// @ResponseBody注解处理返回值的核心逻辑
		// 通过HttpMessageConverters将返回值序列化并写入响应体
		// 这个方法会执行内容协商，选择合适的转换器，并设置相应的内容类型头
		writeWithMessageConverters(returnValue, returnType, inputMessage, outputMessage);
	}


}
