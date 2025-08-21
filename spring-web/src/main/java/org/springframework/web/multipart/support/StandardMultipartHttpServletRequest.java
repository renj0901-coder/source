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

package org.springframework.web.multipart.support;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import javax.mail.internet.MimeUtility;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.Part;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.lang.Nullable;
import org.springframework.util.FileCopyUtils;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.MultipartFile;

/**
 * Spring MultipartHttpServletRequest adapter, wrapping a Servlet 3.0 HttpServletRequest
 * and its Part objects. Parameters get exposed through the native request's getParameter
 * methods - without any custom processing on our side.
 *
 * @author Juergen Hoeller
 * @author Rossen Stoyanchev
 * @since 3.1
 * @see StandardServletMultipartResolver
 */
public class StandardMultipartHttpServletRequest extends AbstractMultipartHttpServletRequest {

	@Nullable
	private Set<String> multipartParameterNames;


	/**
	 * Create a new StandardMultipartHttpServletRequest wrapper for the given request,
	 * immediately parsing the multipart content.
	 * @param request the servlet request to wrap
	 * @throws MultipartException if parsing failed
	 */
	public StandardMultipartHttpServletRequest(HttpServletRequest request) throws MultipartException {
		this(request, false);
	}

	/**
	 * Create a new StandardMultipartHttpServletRequest wrapper for the given request.
	 * @param request the servlet request to wrap
	 * @param lazyParsing whether multipart parsing should be triggered lazily on
	 * first access of multipart files or parameters
	 * @throws MultipartException if an immediate parsing attempt failed
	 * @since 3.2.9
	 */
	public StandardMultipartHttpServletRequest(HttpServletRequest request, boolean lazyParsing)
			throws MultipartException {

		super(request);
		if (!lazyParsing) {
			// 会取出来请求parts进行解析
			parseRequest(request);
		}
	}


	/**
	 * 解析Servlet 3.0多部分请求，将Part对象转换为Spring的MultipartFile对象
	 * <p>
	 * 该方法是StandardMultipartHttpServletRequest的核心处理逻辑，负责：
	 * 1. 从HttpServletRequest中获取所有Part对象
	 * 2. 遍历每个Part并根据是否包含文件名来区分文件上传字段和普通表单字段
	 * 3. 将文件上传Part包装为StandardMultipartFile对象
	 * 4. 记录普通表单参数名称
	 * 5. 将解析结果存储到父类的multipartFiles映射中
	 * <p>
	 * 解析过程：
	 * 1. 调用request.getParts()获取所有上传部分
	 * 2. 为每个Part解析Content-Disposition头部获取文件名
	 * 3. 如果有文件名，则视为文件上传字段，创建StandardMultipartFile
	 * 4. 如果没有文件名，则视为普通表单参数字段，记录参数名
	 * 5. 将文件映射和参数名集合存储到相应属性中
	 *
	 * @param request 当前的HttpServletRequest对象，必须支持Servlet 3.0多部分处理
	 * @throws MultipartException 当解析过程中发生任何异常时抛出
	 */
	private void parseRequest(HttpServletRequest request) {
		try {
			// 从Servlet 3.0请求中获取所有Part对象
			// 这会触发Servlet容器的多部分解析机制
			Collection<Part> parts = request.getParts();

			// 初始化多部分参数名称集合，用于存储普通表单字段名称
			// 使用LinkedHashSet保持插入顺序并避免重复
			this.multipartParameterNames = new LinkedHashSet<>(parts.size());

			// 创建存储文件的多值映射，键为字段名称，值为MultipartFile列表
			// LinkedMultiValueMap保证了字段名称的插入顺序
			MultiValueMap<String, MultipartFile> files = new LinkedMultiValueMap<>(parts.size());

			// 遍历所有Part对象进行分类处理
			for (Part part : parts) {
				// 获取Content-Disposition头部，包含字段名称和文件名等信息
				String headerValue = part.getHeader(HttpHeaders.CONTENT_DISPOSITION);

				// 解析Content-Disposition头部，提取相关信息
				ContentDisposition disposition = ContentDisposition.parse(headerValue);

				// 获取文件名，如果为null则表示这不是文件上传字段
				String filename = disposition.getFilename();

				// **** 判断是否为文件上传字段
				if (filename != null) {
					// 处理文件名编码问题
					// 如果文件名以"=?"开头并以"?="结尾，说明是MIME编码的文件名
					if (filename.startsWith("=?") && filename.endsWith("?=")) {
						// 使用JavaMail API解码MIME编码的文件名
						filename = MimeDelegate.decode(filename);
					}

					// 将Part包装为Spring的MultipartFile接口实现
					// StandardMultipartFile提供了对Part对象的适配
					files.add(part.getName(), new StandardMultipartFile(part, filename));
				}
				// 普通表单参数字段处理
				else {
					// 将参数名称添加到多部分参数名称集合中
					// 这些参数可以通过getParameter等方法访问
					this.multipartParameterNames.add(part.getName());
				}
			}

			// 将解析出的文件映射设置到父类AbstractMultipartHttpServletRequest中
			// 使得可以通过getFiles、getFile等方法访问上传的文件
			setMultipartFiles(files);
		}
		// 捕获解析过程中可能发生的任何异常
		catch (Throwable ex) {
			// 统一处理解析失败情况
			handleParseFailure(ex);
		}
	}


	protected void handleParseFailure(Throwable ex) {
		String msg = ex.getMessage();
		if (msg != null && msg.contains("size") && msg.contains("exceed")) {
			throw new MaxUploadSizeExceededException(-1, ex);
		}
		throw new MultipartException("Failed to parse multipart servlet request", ex);
	}

	@Override
	protected void initializeMultipart() {
		parseRequest(getRequest());
	}

	@Override
	public Enumeration<String> getParameterNames() {
		if (this.multipartParameterNames == null) {
			initializeMultipart();
		}
		if (this.multipartParameterNames.isEmpty()) {
			return super.getParameterNames();
		}

		// Servlet 3.0 getParameterNames() not guaranteed to include multipart form items
		// (e.g. on WebLogic 12) -> need to merge them here to be on the safe side
		Set<String> paramNames = new LinkedHashSet<>();
		Enumeration<String> paramEnum = super.getParameterNames();
		while (paramEnum.hasMoreElements()) {
			paramNames.add(paramEnum.nextElement());
		}
		paramNames.addAll(this.multipartParameterNames);
		return Collections.enumeration(paramNames);
	}

	@Override
	public Map<String, String[]> getParameterMap() {
		if (this.multipartParameterNames == null) {
			initializeMultipart();
		}
		if (this.multipartParameterNames.isEmpty()) {
			return super.getParameterMap();
		}

		// Servlet 3.0 getParameterMap() not guaranteed to include multipart form items
		// (e.g. on WebLogic 12) -> need to merge them here to be on the safe side
		Map<String, String[]> paramMap = new LinkedHashMap<>(super.getParameterMap());
		for (String paramName : this.multipartParameterNames) {
			if (!paramMap.containsKey(paramName)) {
				paramMap.put(paramName, getParameterValues(paramName));
			}
		}
		return paramMap;
	}

	@Override
	public String getMultipartContentType(String paramOrFileName) {
		try {
			Part part = getPart(paramOrFileName);
			return (part != null ? part.getContentType() : null);
		}
		catch (Throwable ex) {
			throw new MultipartException("Could not access multipart servlet request", ex);
		}
	}

	@Override
	public HttpHeaders getMultipartHeaders(String paramOrFileName) {
		try {
			Part part = getPart(paramOrFileName);
			if (part != null) {
				HttpHeaders headers = new HttpHeaders();
				for (String headerName : part.getHeaderNames()) {
					headers.put(headerName, new ArrayList<>(part.getHeaders(headerName)));
				}
				return headers;
			}
			else {
				return null;
			}
		}
		catch (Throwable ex) {
			throw new MultipartException("Could not access multipart servlet request", ex);
		}
	}


	/**
	 * Spring MultipartFile adapter, wrapping a Servlet 3.0 Part object.
	 */
	@SuppressWarnings("serial")
	private static class StandardMultipartFile implements MultipartFile, Serializable {

		private final Part part;

		private final String filename;

		public StandardMultipartFile(Part part, String filename) {
			this.part = part;
			this.filename = filename;
		}

		@Override
		public String getName() {
			return this.part.getName();
		}

		@Override
		public String getOriginalFilename() {
			return this.filename;
		}

		@Override
		public String getContentType() {
			return this.part.getContentType();
		}

		@Override
		public boolean isEmpty() {
			return (this.part.getSize() == 0);
		}

		@Override
		public long getSize() {
			return this.part.getSize();
		}

		@Override
		public byte[] getBytes() throws IOException {
			return FileCopyUtils.copyToByteArray(this.part.getInputStream());
		}

		@Override
		public InputStream getInputStream() throws IOException {
			return this.part.getInputStream();
		}

		@Override
		public void transferTo(File dest) throws IOException, IllegalStateException {
			this.part.write(dest.getPath());
			if (dest.isAbsolute() && !dest.exists()) {
				// Servlet 3.0 Part.write is not guaranteed to support absolute file paths:
				// may translate the given path to a relative location within a temp dir
				// (e.g. on Jetty whereas Tomcat and Undertow detect absolute paths).
				// At least we offloaded the file from memory storage; it'll get deleted
				// from the temp dir eventually in any case. And for our user's purposes,
				// we can manually copy it to the requested location as a fallback.
				FileCopyUtils.copy(this.part.getInputStream(), Files.newOutputStream(dest.toPath()));
			}
		}

		@Override
		public void transferTo(Path dest) throws IOException, IllegalStateException {
			FileCopyUtils.copy(this.part.getInputStream(), Files.newOutputStream(dest));
		}
	}


	/**
	 * Inner class to avoid a hard dependency on the JavaMail API.
	 */
	private static class MimeDelegate {

		public static String decode(String value) {
			try {
				return MimeUtility.decodeText(value);
			}
			catch (UnsupportedEncodingException ex) {
				throw new IllegalStateException(ex);
			}
		}
	}

}
