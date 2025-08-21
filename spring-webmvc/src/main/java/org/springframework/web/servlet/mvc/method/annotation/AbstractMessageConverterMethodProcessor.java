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

package org.springframework.web.servlet.mvc.method.annotation;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.servlet.ServletRequest;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.core.GenericTypeResolver;
import org.springframework.core.MethodParameter;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.ResolvableType;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourceRegion;
import org.springframework.core.log.LogFormatUtils;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.converter.GenericHttpMessageConverter;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.accept.ContentNegotiationManager;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.method.support.HandlerMethodReturnValueHandler;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.UrlPathHelper;

/**
 * Extends {@link AbstractMessageConverterMethodArgumentResolver} with the ability to handle method
 * return values by writing to the response with {@link HttpMessageConverter HttpMessageConverters}.
 *
 * @author Arjen Poutsma
 * @author Rossen Stoyanchev
 * @author Brian Clozel
 * @author Juergen Hoeller
 * @since 3.1
 */
public abstract class AbstractMessageConverterMethodProcessor extends AbstractMessageConverterMethodArgumentResolver
		implements HandlerMethodReturnValueHandler {

	/* Extensions associated with the built-in message converters */
	private static final Set<String> SAFE_EXTENSIONS = new HashSet<>(Arrays.asList(
			"txt", "text", "yml", "properties", "csv",
			"json", "xml", "atom", "rss",
			"png", "jpe", "jpeg", "jpg", "gif", "wbmp", "bmp"));

	private static final Set<String> SAFE_MEDIA_BASE_TYPES = new HashSet<>(
			Arrays.asList("audio", "image", "video"));

	private static final List<MediaType> ALL_APPLICATION_MEDIA_TYPES =
			Arrays.asList(MediaType.ALL, new MediaType("application"));

	private static final Type RESOURCE_REGION_LIST_TYPE =
			new ParameterizedTypeReference<List<ResourceRegion>>() { }.getType();


	private final ContentNegotiationManager contentNegotiationManager;

	private final Set<String> safeExtensions = new HashSet<>();


	/**
	 * Constructor with list of converters only.
	 */
	protected AbstractMessageConverterMethodProcessor(List<HttpMessageConverter<?>> converters) {
		this(converters, null, null);
	}

	/**
	 * Constructor with list of converters and ContentNegotiationManager.
	 */
	protected AbstractMessageConverterMethodProcessor(List<HttpMessageConverter<?>> converters,
			@Nullable ContentNegotiationManager contentNegotiationManager) {

		this(converters, contentNegotiationManager, null);
	}

	/**
	 * Constructor with list of converters and ContentNegotiationManager as well
	 * as request/response body advice instances.
	 */
	protected AbstractMessageConverterMethodProcessor(List<HttpMessageConverter<?>> converters,
			@Nullable ContentNegotiationManager manager, @Nullable List<Object> requestResponseBodyAdvice) {

		super(converters, requestResponseBodyAdvice);

		this.contentNegotiationManager = (manager != null ? manager : new ContentNegotiationManager());
		this.safeExtensions.addAll(this.contentNegotiationManager.getAllFileExtensions());
		this.safeExtensions.addAll(SAFE_EXTENSIONS);
	}


	/**
	 * Creates a new {@link HttpOutputMessage} from the given {@link NativeWebRequest}.
	 * @param webRequest the web request to create an output message from
	 * @return the output message
	 */
	protected ServletServerHttpResponse createOutputMessage(NativeWebRequest webRequest) {
		HttpServletResponse response = webRequest.getNativeResponse(HttpServletResponse.class);
		Assert.state(response != null, "No HttpServletResponse");
		return new ServletServerHttpResponse(response);
	}

	/**
	 * Writes the given return value to the given web request. Delegates to
	 * {@link #writeWithMessageConverters(Object, MethodParameter, ServletServerHttpRequest, ServletServerHttpResponse)}
	 */
	protected <T> void writeWithMessageConverters(T value, MethodParameter returnType, NativeWebRequest webRequest)
			throws IOException, HttpMediaTypeNotAcceptableException, HttpMessageNotWritableException {

		ServletServerHttpRequest inputMessage = createInputMessage(webRequest);
		ServletServerHttpResponse outputMessage = createOutputMessage(webRequest);
		writeWithMessageConverters(value, returnType, inputMessage, outputMessage);
	}

	/**
	 * 使用HttpMessageConverter将返回值写入HTTP响应的核心方法
	 * <p>
	 * 该方法是Spring MVC处理@ResponseBody和ResponseEntity返回值的核心逻辑，负责：
	 * 1. 确定响应体内容和类型
	 * 2. 执行内容协商（Content Negotiation）
	 * 3. 选择合适的HttpMessageConverter
	 * 4. 应用ResponseBodyAdvice拦截器
	 * 5. 序列化数据并写入响应
	 * <p>
	 * 处理流程：
	 * 1. 准备响应体数据和类型信息
	 * 2. 处理资源类型和范围请求
	 * 3. 确定响应的媒体类型（内容协商）
	 * 4. 选择并使用合适的消息转换器
	 * 5. 应用安全防护措施
	 *
	 * @see RequestMappingHandlerAdapter#RequestMappingHandlerAdapter()
	 *      RequestMappingHandlerAdapter的构造方法中初始化了默认的HttpMessageConverter列表
	 *
	 * SpringMVC会有4个HttpMessageConverter：
	 * 1. ByteArrayHttpMessageConverter：处理返回值为字节数组的情况，把字节数组返回给浏览器
	 * 2. StringHttpMessageConverter：处理返回值为字符串的情况，把字符串按指定的编码序列号后返回给浏览器
	 * 3. SourceHttpMessageConverter：处理返回值为XML对象的情况，比如把DOMSource对象返回给浏览器
	 * 4. AllEncompassingFormHttpMessageConverter：处理返回值为MultiValueMap对象的情况
	 *
	 * @param value         要写入响应的返回值对象
	 * @param returnType    方法返回值的参数信息
	 * @param inputMessage  封装了HTTP请求的输入消息
	 * @param outputMessage 封装了HTTP响应的输出消息
	 * @param <T>           返回值的泛型类型
	 * @throws IOException                         IO操作异常
	 * @throws HttpMediaTypeNotAcceptableException 客户端Accept头指定的媒体类型不被支持
	 * @throws HttpMessageNotWritableException     返回值无法被任何消息转换器序列化
	 */
	@SuppressWarnings({"rawtypes", "unchecked"})
	protected <T> void writeWithMessageConverters(@Nullable T value, MethodParameter returnType,
												  ServletServerHttpRequest inputMessage, ServletServerHttpResponse outputMessage)
			throws IOException, HttpMediaTypeNotAcceptableException, HttpMessageNotWritableException {

		// 初始化响应体内容和类型信息
		Object body;
		Class<?> valueType;
		Type targetType;

		// 处理字符序列类型（String、StringBuilder等）
		if (value instanceof CharSequence) {
			// 直接转换为String
			body = value.toString();
			valueType = String.class;
			targetType = String.class;
		}
		// 处理其他类型
		else {
			body = value;
			// 获取返回值的实际类型
			valueType = getReturnValueType(body, returnType);
			// 解析泛型类型信息
			targetType = GenericTypeResolver.resolveType(getGenericType(returnType), returnType.getContainingClass());
		}

		// 处理资源类型返回值（如文件下载）和HTTP范围请求
		if (isResourceType(value, returnType)) {
			// 设置支持范围请求的响应头
			outputMessage.getHeaders().set(HttpHeaders.ACCEPT_RANGES, "bytes");

			// 如果是范围请求（客户端请求部分内容）
			if (value != null && inputMessage.getHeaders().getFirst(HttpHeaders.RANGE) != null &&
					outputMessage.getServletResponse().getStatus() == 200) {

				Resource resource = (Resource) value;
				try {
					// 解析请求的范围
					List<HttpRange> httpRanges = inputMessage.getHeaders().getRange();
					// 设置206 Partial Content状态码
					outputMessage.getServletResponse().setStatus(HttpStatus.PARTIAL_CONTENT.value());
					// 将资源转换为范围区域列表
					body = HttpRange.toResourceRegions(httpRanges, resource);
					valueType = body.getClass();
					targetType = RESOURCE_REGION_LIST_TYPE;
				} catch (IllegalArgumentException ex) {
					// 范围请求无效，返回416状态码
					outputMessage.getHeaders().set(HttpHeaders.CONTENT_RANGE, "bytes */" + resource.contentLength());
					outputMessage.getServletResponse().setStatus(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE.value());
				}
			}
		}

		// 内容协商：确定响应的媒体类型
		MediaType selectedMediaType = null;
		// 检查是否已预设Content-Type
		MediaType contentType = outputMessage.getHeaders().getContentType();
		boolean isContentTypePreset = contentType != null && contentType.isConcrete();

		// 如果已预设具体的Content-Type，则直接使用
		if (isContentTypePreset) {
			if (logger.isDebugEnabled()) {
				logger.debug("Found 'Content-Type:" + contentType + "' in response");
			}
			selectedMediaType = contentType;
		}
		// 否则执行内容协商
		else {
			HttpServletRequest request = inputMessage.getServletRequest();
			List<MediaType> acceptableTypes;

			// 获取客户端可接受的媒体类型（来自Accept头）
			try {
				acceptableTypes = getAcceptableMediaTypes(request);
			} catch (HttpMediaTypeNotAcceptableException ex) {
				// 处理Accept头解析异常
				int series = outputMessage.getServletResponse().getStatus() / 100;
				if (body == null || series == 4 || series == 5) {
					// 如果是错误响应，忽略内容并返回
					if (logger.isDebugEnabled()) {
						logger.debug("Ignoring error response content (if any). " + ex);
					}
					return;
				}
				throw ex;
			}

			// 获取服务器可以生产的媒体类型
			// 如果没有设置MediaType
			// 通过HttpMessageConverter 介入、找到最合适的HttpMessageConverter， 获取对应设定的MediaType
			List<MediaType> producibleTypes = getProducibleMediaTypes(request, valueType, targetType);

			// 如果有返回值但没有可用的转换器，抛出异常
			if (body != null && producibleTypes.isEmpty()) {
				throw new HttpMessageNotWritableException(
						"No converter found for return value of type: " + valueType);
			}

			// 执行内容协商：匹配客户端接受的类型和服务器可生产的类型
			List<MediaType> mediaTypesToUse = new ArrayList<>();
			for (MediaType requestedType : acceptableTypes) {
				for (MediaType producibleType : producibleTypes) {
					// 如果客户端接受的类型与服务器可生产的类型兼容
					if (requestedType.isCompatibleWith(producibleType)) {
						// 选择最具体的媒体类型
						mediaTypesToUse.add(getMostSpecificMediaType(requestedType, producibleType));
					}
				}
			}

			// 如果没有匹配的媒体类型
			if (mediaTypesToUse.isEmpty()) {
				if (body != null) {
					// 有返回值但不支持，抛出异常
					throw new HttpMediaTypeNotAcceptableException(producibleTypes);
				}
				if (logger.isDebugEnabled()) {
					logger.debug("No match for " + acceptableTypes + ", supported: " + producibleTypes);
				}
				return;
			}

			// 按特异性和质量值排序
			MediaType.sortBySpecificityAndQuality(mediaTypesToUse);

			// 选择第一个具体的媒体类型
			for (MediaType mediaType : mediaTypesToUse) {
				if (mediaType.isConcrete()) {
					selectedMediaType = mediaType;
					break;
				} else if (mediaType.isPresentIn(ALL_APPLICATION_MEDIA_TYPES)) {
					// 如果是应用类型，使用octet-stream
					selectedMediaType = MediaType.APPLICATION_OCTET_STREAM;
					break;
				}
			}

			if (logger.isDebugEnabled()) {
				logger.debug("Using '" + selectedMediaType + "', given " +
						acceptableTypes + " and supported " + producibleTypes);
			}
		}

		// 如果确定了媒体类型，则执行序列化写入
		if (selectedMediaType != null) {
			// 移除质量值（q值）
			selectedMediaType = selectedMediaType.removeQualityValue();

			// 遍历所有消息转换器，找到合适的转换器
			for (HttpMessageConverter<?> converter : this.messageConverters) {
				// 检查是否为泛型转换器
				GenericHttpMessageConverter genericConverter = (converter instanceof GenericHttpMessageConverter ?
						(GenericHttpMessageConverter<?>) converter : null);

				// 检查转换器是否支持当前类型和媒体类型
				if (genericConverter != null ?
						((GenericHttpMessageConverter) converter).canWrite(targetType, valueType, selectedMediaType) :
						converter.canWrite(valueType, selectedMediaType)) {

					// 应用ResponseBodyAdvice拦截器
					body = getAdvice().beforeBodyWrite(body, returnType, selectedMediaType,
							(Class<? extends HttpMessageConverter<?>>) converter.getClass(),
							inputMessage, outputMessage);

					// 如果拦截器没有返回null，则继续处理
					if (body != null) {
						Object theBody = body;
						// 记录调试日志
						LogFormatUtils.traceDebug(logger, traceOn ->
								"Writing [" + LogFormatUtils.formatValue(theBody, !traceOn) + "]");

						// 添加内容处置头以防止RFD攻击
						addContentDispositionHeader(inputMessage, outputMessage);

						// 使用转换器写入响应
						if (genericConverter != null) {
							// 使用泛型转换器
							genericConverter.write(body, targetType, selectedMediaType, outputMessage);
						} else {
							// 使用普通转换器
							((HttpMessageConverter) converter).write(body, selectedMediaType, outputMessage);
						}
					} else {
						// 拦截器返回null，不写入响应
						if (logger.isDebugEnabled()) {
							logger.debug("Nothing to write: null body");
						}
					}
					return;
				}
			}
		}

		// 如果没有找到合适的转换器
		if (body != null) {
			Set<MediaType> producibleMediaTypes =
					(Set<MediaType>) inputMessage.getServletRequest()
							.getAttribute(HandlerMapping.PRODUCIBLE_MEDIA_TYPES_ATTRIBUTE);

			// 如果已预设Content-Type或有可生产类型，抛出写入异常
			if (isContentTypePreset || !CollectionUtils.isEmpty(producibleMediaTypes)) {
				throw new HttpMessageNotWritableException(
						"No converter for [" + valueType + "] with preset Content-Type '" + contentType + "'");
			}
			// 否则抛出媒体类型不可接受异常
			throw new HttpMediaTypeNotAcceptableException(getSupportedMediaTypes(body.getClass()));
		}
	}

	/**
	 * Return the type of the value to be written to the response. Typically this is
	 * a simple check via getClass on the value but if the value is null, then the
	 * return type needs to be examined possibly including generic type determination
	 * (e.g. {@code ResponseEntity<T>}).
	 */
	protected Class<?> getReturnValueType(@Nullable Object value, MethodParameter returnType) {
		return (value != null ? value.getClass() : returnType.getParameterType());
	}

	/**
	 * Return whether the returned value or the declared return type extends {@link Resource}.
	 */
	protected boolean isResourceType(@Nullable Object value, MethodParameter returnType) {
		Class<?> clazz = getReturnValueType(value, returnType);
		return clazz != InputStreamResource.class && Resource.class.isAssignableFrom(clazz);
	}

	/**
	 * Return the generic type of the {@code returnType} (or of the nested type
	 * if it is an {@link HttpEntity}).
	 */
	private Type getGenericType(MethodParameter returnType) {
		if (HttpEntity.class.isAssignableFrom(returnType.getParameterType())) {
			return ResolvableType.forType(returnType.getGenericParameterType()).getGeneric().getType();
		}
		else {
			return returnType.getGenericParameterType();
		}
	}

	/**
	 * Returns the media types that can be produced.
	 * @see #getProducibleMediaTypes(HttpServletRequest, Class, Type)
	 */
	@SuppressWarnings("unused")
	protected List<MediaType> getProducibleMediaTypes(HttpServletRequest request, Class<?> valueClass) {
		return getProducibleMediaTypes(request, valueClass, null);
	}

	/**
	 * Returns the media types that can be produced. The resulting media types are:
	 * <ul>
	 * <li>The producible media types specified in the request mappings, or
	 * <li>Media types of configured converters that can write the specific return value, or
	 * <li>{@link MediaType#ALL}
	 * </ul>
	 * @since 4.2
	 */
	@SuppressWarnings("unchecked")
	protected List<MediaType> getProducibleMediaTypes(
			HttpServletRequest request, Class<?> valueClass, @Nullable Type targetType) {

		Set<MediaType> mediaTypes =
				(Set<MediaType>) request.getAttribute(HandlerMapping.PRODUCIBLE_MEDIA_TYPES_ATTRIBUTE);
		if (!CollectionUtils.isEmpty(mediaTypes)) {
			return new ArrayList<>(mediaTypes);
		}
		List<MediaType> result = new ArrayList<>();
		for (HttpMessageConverter<?> converter : this.messageConverters) {
			if (converter instanceof GenericHttpMessageConverter && targetType != null) {
				if (((GenericHttpMessageConverter<?>) converter).canWrite(targetType, valueClass, null)) {
					result.addAll(converter.getSupportedMediaTypes(valueClass));
				}
			}
			else if (converter.canWrite(valueClass, null)) {
				result.addAll(converter.getSupportedMediaTypes(valueClass));
			}
		}
		return (result.isEmpty() ? Collections.singletonList(MediaType.ALL) : result);
	}

	private List<MediaType> getAcceptableMediaTypes(HttpServletRequest request)
			throws HttpMediaTypeNotAcceptableException {

		return this.contentNegotiationManager.resolveMediaTypes(new ServletWebRequest(request));
	}

	/**
	 * Return the more specific of the acceptable and the producible media types
	 * with the q-value of the former.
	 */
	private MediaType getMostSpecificMediaType(MediaType acceptType, MediaType produceType) {
		MediaType produceTypeToUse = produceType.copyQualityValue(acceptType);
		return (MediaType.SPECIFICITY_COMPARATOR.compare(acceptType, produceTypeToUse) <= 0 ? acceptType : produceTypeToUse);
	}

	/**
	 * Check if the path has a file extension and whether the extension is either
	 * on the list of {@link #SAFE_EXTENSIONS safe extensions} or explicitly
	 * {@link ContentNegotiationManager#getAllFileExtensions() registered}.
	 * If not, and the status is in the 2xx range, a 'Content-Disposition'
	 * header with a safe attachment file name ("f.txt") is added to prevent
	 * RFD exploits.
	 */
	private void addContentDispositionHeader(ServletServerHttpRequest request, ServletServerHttpResponse response) {
		HttpHeaders headers = response.getHeaders();
		if (headers.containsKey(HttpHeaders.CONTENT_DISPOSITION)) {
			return;
		}

		try {
			int status = response.getServletResponse().getStatus();
			if (status < 200 || (status > 299 && status < 400)) {
				return;
			}
		}
		catch (Throwable ex) {
			// ignore
		}

		HttpServletRequest servletRequest = request.getServletRequest();
		String requestUri = UrlPathHelper.rawPathInstance.getOriginatingRequestUri(servletRequest);

		int index = requestUri.lastIndexOf('/') + 1;
		String filename = requestUri.substring(index);
		String pathParams = "";

		index = filename.indexOf(';');
		if (index != -1) {
			pathParams = filename.substring(index);
			filename = filename.substring(0, index);
		}

		filename = UrlPathHelper.defaultInstance.decodeRequestString(servletRequest, filename);
		String ext = StringUtils.getFilenameExtension(filename);

		pathParams = UrlPathHelper.defaultInstance.decodeRequestString(servletRequest, pathParams);
		String extInPathParams = StringUtils.getFilenameExtension(pathParams);

		if (!safeExtension(servletRequest, ext) || !safeExtension(servletRequest, extInPathParams)) {
			headers.add(HttpHeaders.CONTENT_DISPOSITION, "inline;filename=f.txt");
		}
	}

	@SuppressWarnings("unchecked")
	private boolean safeExtension(HttpServletRequest request, @Nullable String extension) {
		if (!StringUtils.hasText(extension)) {
			return true;
		}
		extension = extension.toLowerCase(Locale.ENGLISH);
		if (this.safeExtensions.contains(extension)) {
			return true;
		}
		String pattern = (String) request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
		if (pattern != null && pattern.endsWith("." + extension)) {
			return true;
		}
		if (extension.equals("html")) {
			String name = HandlerMapping.PRODUCIBLE_MEDIA_TYPES_ATTRIBUTE;
			Set<MediaType> mediaTypes = (Set<MediaType>) request.getAttribute(name);
			if (!CollectionUtils.isEmpty(mediaTypes) && mediaTypes.contains(MediaType.TEXT_HTML)) {
				return true;
			}
		}
		MediaType mediaType = resolveMediaType(request, extension);
		return (mediaType != null && (safeMediaType(mediaType)));
	}

	@Nullable
	private MediaType resolveMediaType(ServletRequest request, String extension) {
		MediaType result = null;
		String rawMimeType = request.getServletContext().getMimeType("file." + extension);
		if (StringUtils.hasText(rawMimeType)) {
			result = MediaType.parseMediaType(rawMimeType);
		}
		if (result == null || MediaType.APPLICATION_OCTET_STREAM.equals(result)) {
			result = MediaTypeFactory.getMediaType("file." + extension).orElse(null);
		}
		return result;
	}

	private boolean safeMediaType(MediaType mediaType) {
		return (SAFE_MEDIA_BASE_TYPES.contains(mediaType.getType()) ||
				mediaType.getSubtype().endsWith("+xml"));
	}

}
