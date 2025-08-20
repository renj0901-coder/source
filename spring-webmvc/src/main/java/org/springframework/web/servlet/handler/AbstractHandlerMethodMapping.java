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

package org.springframework.web.servlet.handler;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;

import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.MethodIntrospector;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Abstract base class for {@link HandlerMapping} implementations that define
 * a mapping between a request and a {@link HandlerMethod}.
 *
 * <p>For each registered handler method, a unique mapping is maintained with
 * subclasses defining the details of the mapping type {@code <T>}.
 *
 * @author Arjen Poutsma
 * @author Rossen Stoyanchev
 * @author Juergen Hoeller
 * @author Sam Brannen
 * @since 3.1
 * @param <T> the mapping for a {@link HandlerMethod} containing the conditions
 * needed to match the handler method to an incoming request.
 */
public abstract class AbstractHandlerMethodMapping<T> extends AbstractHandlerMapping implements InitializingBean {

	/**
	 * Bean name prefix for target beans behind scoped proxies. Used to exclude those
	 * targets from handler method detection, in favor of the corresponding proxies.
	 * <p>We're not checking the autowire-candidate status here, which is how the
	 * proxy target filtering problem is being handled at the autowiring level,
	 * since autowire-candidate may have been turned to {@code false} for other
	 * reasons, while still expecting the bean to be eligible for handler methods.
	 * <p>Originally defined in {@link org.springframework.aop.scope.ScopedProxyUtils}
	 * but duplicated here to avoid a hard dependency on the spring-aop module.
	 */
	private static final String SCOPED_TARGET_NAME_PREFIX = "scopedTarget.";

	private static final HandlerMethod PREFLIGHT_AMBIGUOUS_MATCH =
			new HandlerMethod(new EmptyHandler(), ClassUtils.getMethod(EmptyHandler.class, "handle"));

	private static final CorsConfiguration ALLOW_CORS_CONFIG = new CorsConfiguration();

	static {
		ALLOW_CORS_CONFIG.addAllowedOriginPattern("*");
		ALLOW_CORS_CONFIG.addAllowedMethod("*");
		ALLOW_CORS_CONFIG.addAllowedHeader("*");
		ALLOW_CORS_CONFIG.setAllowCredentials(true);
	}


	private boolean detectHandlerMethodsInAncestorContexts = false;

	@Nullable
	private HandlerMethodMappingNamingStrategy<T> namingStrategy;

	private final MappingRegistry mappingRegistry = new MappingRegistry();


	@Override
	public void setPatternParser(PathPatternParser patternParser) {
		Assert.state(this.mappingRegistry.getRegistrations().isEmpty(),
				"PathPatternParser must be set before the initialization of " +
						"request mappings through InitializingBean#afterPropertiesSet.");
		super.setPatternParser(patternParser);
	}

	/**
	 * Whether to detect handler methods in beans in ancestor ApplicationContexts.
	 * <p>Default is "false": Only beans in the current ApplicationContext are
	 * considered, i.e. only in the context that this HandlerMapping itself
	 * is defined in (typically the current DispatcherServlet's context).
	 * <p>Switch this flag on to detect handler beans in ancestor contexts
	 * (typically the Spring root WebApplicationContext) as well.
	 * @see #getCandidateBeanNames()
	 */
	public void setDetectHandlerMethodsInAncestorContexts(boolean detectHandlerMethodsInAncestorContexts) {
		this.detectHandlerMethodsInAncestorContexts = detectHandlerMethodsInAncestorContexts;
	}

	/**
	 * Configure the naming strategy to use for assigning a default name to every
	 * mapped handler method.
	 * <p>The default naming strategy is based on the capital letters of the
	 * class name followed by "#" and then the method name, e.g. "TC#getFoo"
	 * for a class named TestController with method getFoo.
	 */
	public void setHandlerMethodMappingNamingStrategy(HandlerMethodMappingNamingStrategy<T> namingStrategy) {
		this.namingStrategy = namingStrategy;
	}

	/**
	 * Return the configured naming strategy or {@code null}.
	 */
	@Nullable
	public HandlerMethodMappingNamingStrategy<T> getNamingStrategy() {
		return this.namingStrategy;
	}

	/**
	 * Return a (read-only) map with all mappings and HandlerMethod's.
	 */
	public Map<T, HandlerMethod> getHandlerMethods() {
		this.mappingRegistry.acquireReadLock();
		try {
			return Collections.unmodifiableMap(
					this.mappingRegistry.getRegistrations().entrySet().stream()
							.collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().handlerMethod)));
		}
		finally {
			this.mappingRegistry.releaseReadLock();
		}
	}

	/**
	 * Return the handler methods for the given mapping name.
	 * @param mappingName the mapping name
	 * @return a list of matching HandlerMethod's or {@code null}; the returned
	 * list will never be modified and is safe to iterate.
	 * @see #setHandlerMethodMappingNamingStrategy
	 */
	@Nullable
	public List<HandlerMethod> getHandlerMethodsForMappingName(String mappingName) {
		return this.mappingRegistry.getHandlerMethodsByMappingName(mappingName);
	}

	/**
	 * Return the internal mapping registry. Provided for testing purposes.
	 */
	MappingRegistry getMappingRegistry() {
		return this.mappingRegistry;
	}

	/**
	 * Register the given mapping.
	 * <p>This method may be invoked at runtime after initialization has completed.
	 * @param mapping the mapping for the handler method
	 * @param handler the handler
	 * @param method the method
	 */
	public void registerMapping(T mapping, Object handler, Method method) {
		if (logger.isTraceEnabled()) {
			logger.trace("Register \"" + mapping + "\" to " + method.toGenericString());
		}
		this.mappingRegistry.register(mapping, handler, method);
	}

	/**
	 * Un-register the given mapping.
	 * <p>This method may be invoked at runtime after initialization has completed.
	 * @param mapping the mapping to unregister
	 */
	public void unregisterMapping(T mapping) {
		if (logger.isTraceEnabled()) {
			logger.trace("Unregister mapping \"" + mapping + "\"");
		}
		this.mappingRegistry.unregister(mapping);
	}


	// Handler method detection

	/**
	 * Detects handler methods at initialization.
	 * @see #initHandlerMethods
	 */
	@Override
	public void afterPropertiesSet() {
		initHandlerMethods();
	}

	/**
	 * Scan beans in the ApplicationContext, detect and register handler methods.
	 * @see #getCandidateBeanNames()
	 * @see #processCandidateBean
	 * @see #handlerMethodsInitialized
	 */
	protected void initHandlerMethods() {
		// 获得所有候选beanName—— 当前容器所有的beanName
		for (String beanName : getCandidateBeanNames()) {
			if (!beanName.startsWith(SCOPED_TARGET_NAME_PREFIX)) {
				// *处理候选bean——即解析@RequestMapping和映射路径
				processCandidateBean(beanName);
			}
		}
		// 解析完所有@RequestMapping的时候调用
		handlerMethodsInitialized(getHandlerMethods());
	}

	/**
	 * Determine the names of candidate beans in the application context.
	 * @since 5.1
	 * @see #setDetectHandlerMethodsInAncestorContexts
	 * @see BeanFactoryUtils#beanNamesForTypeIncludingAncestors
	 */
	protected String[] getCandidateBeanNames() {
		return (this.detectHandlerMethodsInAncestorContexts ?
				BeanFactoryUtils.beanNamesForTypeIncludingAncestors(obtainApplicationContext(), Object.class) :
				obtainApplicationContext().getBeanNamesForType(Object.class));
	}

	/**
	 * Determine the type of the specified candidate bean and call
	 * {@link #detectHandlerMethods} if identified as a handler type.
	 * <p>This implementation avoids bean creation through checking
	 * {@link org.springframework.beans.factory.BeanFactory#getType}
	 * and calling {@link #detectHandlerMethods} with the bean name.
	 * @param beanName the name of the candidate bean
	 * @since 5.1
	 * @see #isHandler
	 * @see #detectHandlerMethods
	 */
	protected void processCandidateBean(String beanName) {
		Class<?> beanType = null;
		try {
			beanType = obtainApplicationContext().getType(beanName);
		}
		catch (Throwable ex) {
			// An unresolvable bean type, probably from a lazy bean - let's ignore it.
			if (logger.isTraceEnabled()) {
				logger.trace("Could not resolve type for bean '" + beanName + "'", ex);
			}
		}
		// 这一步判断是关键  是否有@Controller 或 RequestMapping注解（必须加@Component）
		if (beanType != null && isHandler(beanType)) {
			// 解析HandlerMethods
			detectHandlerMethods(beanName);
		}
	}

	/**
	 * 在指定的处理器bean中查找处理器方法
	 * <p>该方法负责扫描给定处理器类中的所有方法，识别带有映射注解的方法（如@RequestMapping），
	 * 并将它们注册为可处理特定请求的处理器方法
	 *
	 * @param handler 处理器bean，可以是bean名称（String类型）或实际的处理器实例（Object类型）
	 *                <p>
	 *                执行流程：
	 *                1. 获取处理器类型：根据handler是bean名称还是实例来确定处理器类型
	 *                2. 获取用户类：如果是CGLIB代理类，则获取其父类（原始类）
	 *                3. 扫描方法：使用MethodIntrospector扫描类中的所有方法
	 *                4. 解析映射：对每个方法调用getMappingForMethod()来解析映射信息
	 *                5. 注册处理器方法：将解析出的有效映射注册到映射注册表中
	 *                <p>
	 *                关键点：
	 *                - 使用MethodIntrospector.selectMethods()方法来高效地扫描和过滤方法
	 *                - 通过getMappingForMethod()模板方法让子类决定如何解析方法上的映射注解
	 *                - 使用AopUtils.selectInvocableMethod()处理可能的AOP代理方法
	 *                - 最终通过registerHandlerMethod()将映射关系注册到系统中
	 *                <p>
	 *                调用时机：
	 *                - 在Spring容器初始化阶段，当发现一个带有@Controller或@RequestMapping注解的bean时
	 *                - 由processCandidateBean()方法调用
	 * @see #getMappingForMethod(Method, Class)
	 * @see #registerHandlerMethod(Object, Method, Object)
	 * @see MethodIntrospector#selectMethods(Class, MethodIntrospector.MetadataLookup)
	 * @see AopUtils#selectInvocableMethod(Method, Class)
	 */
	protected void detectHandlerMethods(Object handler) {
		// 确定处理器类型
		// 如果handler是String类型，说明是bean名称，需要从ApplicationContext中获取类型
		// 如果handler是对象实例，则直接获取其Class类型
		Class<?> handlerType = (handler instanceof String ?
				obtainApplicationContext().getType((String) handler) : handler.getClass());

		// 确保处理器类型不为null
		if (handlerType != null) {
			// 获取用户类：如果handlerType是CGLIB代理类，ClassUtils.getUserClass会返回其父类（原始类）
			// 这样可以确保我们扫描的是用户定义的原始类，而不是Spring生成的代理类
			Class<?> userType = ClassUtils.getUserClass(handlerType);


			// methods key 是Method  value 是RequestMappingInfo
			// 循环扫描类中的所有方法，筛选出带有映射注解的方法
			// MethodIntrospector.selectMethods()是一个高效的工具方法，用于扫描类的方法并应用过滤器
			Map<Method, T> methods = MethodIntrospector.selectMethods(userType,
					// MetadataLookup函数式接口：对每个方法执行此lambda表达式来决定是否包含该方法
					(MethodIntrospector.MetadataLookup<T>) method -> {
						try {
							// 解析方法上的映射信息
							// 这是一个模板方法，由子类实现具体的映射解析逻辑
							// 例如RequestMappingHandlerMapping会检查方法上是否有@RequestMapping注解
							return getMappingForMethod(method, userType);
						} catch (Throwable ex) {
							// 如果解析映射时发生异常，抛出IllegalStateException
							throw new IllegalStateException("Invalid mapping on handler class [" +
									userType.getName() + "]: " + method, ex);
						}
					});

			if (logger.isTraceEnabled()) {
				logger.trace(formatMappings(userType, methods));
			} else if (mappingsLogger.isDebugEnabled()) {
				mappingsLogger.debug(formatMappings(userType, methods));
			}

			// 遍历所有找到的映射方法，将其注册到映射注册表中
			methods.forEach((method, mapping) -> {
				// 使用AopUtils.selectInvocableMethod()选择可调用的方法
				// 这是为了处理AOP代理的情况，确保选择到正确的方法引用
				Method invocableMethod = AopUtils.selectInvocableMethod(method, userType);

				// 注册处理器方法：将方法映射关系存储到映射注册表中
				// 这样在处理请求时就能根据URL找到对应的处理器方法
				registerHandlerMethod(handler, invocableMethod, mapping);
			});
		}
	}


	private String formatMappings(Class<?> userType, Map<Method, T> methods) {
		String formattedType = Arrays.stream(ClassUtils.getPackageName(userType).split("\\."))
				.map(p -> p.substring(0, 1))
				.collect(Collectors.joining(".", "", "." + userType.getSimpleName()));
		Function<Method, String> methodFormatter = method -> Arrays.stream(method.getParameterTypes())
				.map(Class::getSimpleName)
				.collect(Collectors.joining(",", "(", ")"));
		return methods.entrySet().stream()
				.map(e -> {
					Method method = e.getKey();
					return e.getValue() + ": " + method.getName() + methodFormatter.apply(method);
				})
				.collect(Collectors.joining("\n\t", "\n\t" + formattedType + ":" + "\n\t", ""));
	}

	/**
	 * Register a handler method and its unique mapping. Invoked at startup for
	 * each detected handler method.
	 * @param handler the bean name of the handler or the handler instance
	 * @param method the method to register
	 * @param mapping the mapping conditions associated with the handler method
	 * @throws IllegalStateException if another method was already registered
	 * under the same mapping
	 */
	protected void registerHandlerMethod(Object handler, Method method, T mapping) {
		this.mappingRegistry.register(mapping, handler, method);
	}

	/**
	 * Create the HandlerMethod instance.
	 * @param handler either a bean name or an actual handler instance
	 * @param method the target method
	 * @return the created HandlerMethod
	 */
	protected HandlerMethod createHandlerMethod(Object handler, Method method) {
		if (handler instanceof String) {
			return new HandlerMethod((String) handler,
					obtainApplicationContext().getAutowireCapableBeanFactory(),
					obtainApplicationContext(),
					method);
		}
		return new HandlerMethod(handler, method);
	}

	/**
	 * Extract and return the CORS configuration for the mapping.
	 */
	@Nullable
	protected CorsConfiguration initCorsConfiguration(Object handler, Method method, T mapping) {
		return null;
	}

	/**
	 * Invoked after all handler methods have been detected.
	 * @param handlerMethods a read-only map with handler methods and mappings.
	 */
	protected void handlerMethodsInitialized(Map<T, HandlerMethod> handlerMethods) {
		// Total includes detected mappings + explicit registrations via registerMapping
		// 这个方法没做什么， 就是记录  哪个Bean有多少个映射
		int total = handlerMethods.size();
		if ((logger.isTraceEnabled() && total == 0) || (logger.isDebugEnabled() && total > 0) ) {
			logger.debug(total + " mappings in " + formatMappingName());
		}
	}


	// Handler method lookup

	/**
	 * Look up a handler method for the given request.
	 */
	@Override
	@Nullable
	protected HandlerMethod getHandlerInternal(HttpServletRequest request) throws Exception {
		// 通过UrlPathHelper对象，用于来解析从们的request中解析出请求映射路径
		String lookupPath = initLookupPath(request);
		this.mappingRegistry.acquireReadLock();
		try {
			// 通过lookupPath解析最终的handler——HandlerMethod对象
			HandlerMethod handlerMethod = lookupHandlerMethod(lookupPath, request);
			return (handlerMethod != null ? handlerMethod.createWithResolvedBean() : null);
		}
		finally {
			this.mappingRegistry.releaseReadLock();
		}
	}

	/**
	 * Look up the best-matching handler method for the current request.
	 * If multiple matches are found, the best match is selected.
	 * @param lookupPath mapping lookup path within the current servlet mapping
	 * @param request the current request
	 * @return the best-matching handler method, or {@code null} if no match
	 * @see #handleMatch(Object, String, HttpServletRequest)
	 * @see #handleNoMatch(Set, String, HttpServletRequest)
	 */
	@Nullable
	protected HandlerMethod lookupHandlerMethod(String lookupPath, HttpServletRequest request) throws Exception {
		List<Match> matches = new ArrayList<>();
		// 根据uri从mappingRegistry.pathLookup获取 RequestMappingInfo
		// pathLookup<path,RequestMappingInfo>会在初始化阶段解析好
		List<T> directPathMatches = this.mappingRegistry.getMappingsByDirectPath(lookupPath);
		if (directPathMatches != null) {
			// 如果根据path能直接匹配的RequestMappingInfo 则用该mapping进行匹配其他条件(method、header等）
			addMatchingMappings(directPathMatches, matches, request);
		}
		if (matches.isEmpty()) {
			// 如果无path匹配，用所有的RequestMappingInfo  通过AntPathMatcher匹配
			addMatchingMappings(this.mappingRegistry.getRegistrations().keySet(), matches, request);
		}
		if (!matches.isEmpty()) {
			// 选择第一个为最匹配的
			Match bestMatch = matches.get(0);
			/**
			 * 如果匹配到多个
			 @RequestMapping(value="/mappin?")
			 @RequestMapping(value="/mappin*")
			 @RequestMapping(value="/{xxxx}")
			 @RequestMapping(value="/**")
			 */
			if (matches.size() > 1) {
				//创建MatchComparator的匹配器对象
				Comparator<Match> comparator = new MatchComparator(getMappingComparator(request));

				/** 根据精准度排序  大概是这样的： ? > * > {} >**   具体可以去看：
				 * @see org.springframework.util.AntPathMatcher.AntPatternComparator#compare(java.lang.String, java.lang.String)*/
				matches.sort(comparator);

				// 排完序后拿到优先级最高的
				bestMatch = matches.get(0);
				if (logger.isTraceEnabled()) {
					logger.trace(matches.size() + " matching mappings: " + matches);
				}
				// 是否配置CORS并且匹配
				if (CorsUtils.isPreFlightRequest(request)) {
					for (Match match : matches) {
						if (match.hasCorsConfig()) {
							return PREFLIGHT_AMBIGUOUS_MATCH;
						}
					}
				}
				else {
					//获取第二最匹配的
					Match secondBestMatch = matches.get(1);
					//若第一个和第二个是一样的 抛出异常
					if (comparator.compare(bestMatch, secondBestMatch) == 0) {
						Method m1 = bestMatch.getHandlerMethod().getMethod();
						Method m2 = secondBestMatch.getHandlerMethod().getMethod();
						String uri = request.getRequestURI();
						throw new IllegalStateException(
								"Ambiguous handler methods mapped for '" + uri + "': {" + m1 + ", " + m2 + "}");
					}
				}
			}
			//把最匹配的设置到request中
			request.setAttribute(BEST_MATCHING_HANDLER_ATTRIBUTE, bestMatch.getHandlerMethod());
			handleMatch(bestMatch.mapping, lookupPath, request);
			//返回最匹配的
			return bestMatch.getHandlerMethod();
		}
		else { // return null
			return handleNoMatch(this.mappingRegistry.getRegistrations().keySet(), lookupPath, request);
		}
	}

	private void addMatchingMappings(Collection<T> mappings, List<Match> matches, HttpServletRequest request) {
		for (T mapping : mappings) {
			T match = getMatchingMapping(mapping, request);
			if (match != null) {
				matches.add(new Match(match, this.mappingRegistry.getRegistrations().get(mapping)));
			}
		}
	}

	/**
	 * Invoked when a matching mapping is found.
	 * @param mapping the matching mapping
	 * @param lookupPath mapping lookup path within the current servlet mapping
	 * @param request the current request
	 */
	protected void handleMatch(T mapping, String lookupPath, HttpServletRequest request) {
		request.setAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE, lookupPath);
	}

	/**
	 * Invoked when no matching mapping is not found.
	 * @param mappings all registered mappings
	 * @param lookupPath mapping lookup path within the current servlet mapping
	 * @param request the current request
	 * @throws ServletException in case of errors
	 */
	@Nullable
	protected HandlerMethod handleNoMatch(Set<T> mappings, String lookupPath, HttpServletRequest request)
			throws Exception {

		return null;
	}

	@Override
	protected boolean hasCorsConfigurationSource(Object handler) {
		return super.hasCorsConfigurationSource(handler) ||
				(handler instanceof HandlerMethod &&
						this.mappingRegistry.getCorsConfiguration((HandlerMethod) handler) != null);
	}

	@Override
	protected CorsConfiguration getCorsConfiguration(Object handler, HttpServletRequest request) {
		CorsConfiguration corsConfig = super.getCorsConfiguration(handler, request);
		if (handler instanceof HandlerMethod) {
			HandlerMethod handlerMethod = (HandlerMethod) handler;
			if (handlerMethod.equals(PREFLIGHT_AMBIGUOUS_MATCH)) {
				return AbstractHandlerMethodMapping.ALLOW_CORS_CONFIG;
			}
			else {
				CorsConfiguration corsConfigFromMethod = this.mappingRegistry.getCorsConfiguration(handlerMethod);
				corsConfig = (corsConfig != null ? corsConfig.combine(corsConfigFromMethod) : corsConfigFromMethod);
			}
		}
		return corsConfig;
	}


	// Abstract template methods

	/**
	 * Whether the given type is a handler with handler methods.
	 * @param beanType the type of the bean being checked
	 * @return "true" if this a handler type, "false" otherwise.
	 */
	protected abstract boolean isHandler(Class<?> beanType);

	/**
	 * Provide the mapping for a handler method. A method for which no
	 * mapping can be provided is not a handler method.
	 * @param method the method to provide a mapping for
	 * @param handlerType the handler type, possibly a sub-type of the method's
	 * declaring class
	 * @return the mapping, or {@code null} if the method is not mapped
	 */
	@Nullable
	protected abstract T getMappingForMethod(Method method, Class<?> handlerType);

	/**
	 * Extract and return the URL paths contained in the supplied mapping.
	 * @deprecated as of 5.3 in favor of providing non-pattern mappings via
	 * {@link #getDirectPaths(Object)} instead
	 */
	@Deprecated
	protected Set<String> getMappingPathPatterns(T mapping) {
		return Collections.emptySet();
	}

	/**
	 * Return the request mapping paths that are not patterns.
	 * @since 5.3
	 */
	protected Set<String> getDirectPaths(T mapping) {
		Set<String> urls = Collections.emptySet();
		for (String path : getMappingPathPatterns(mapping)) {
			if (!getPathMatcher().isPattern(path)) {
				urls = (urls.isEmpty() ? new HashSet<>(1) : urls);
				urls.add(path);
			}
		}
		return urls;
	}

	/**
	 * Check if a mapping matches the current request and return a (potentially
	 * new) mapping with conditions relevant to the current request.
	 * @param mapping the mapping to get a match for
	 * @param request the current HTTP servlet request
	 * @return the match, or {@code null} if the mapping doesn't match
	 */
	@Nullable
	protected abstract T getMatchingMapping(T mapping, HttpServletRequest request);

	/**
	 * Return a comparator for sorting matching mappings.
	 * The returned comparator should sort 'better' matches higher.
	 * @param request the current request
	 * @return the comparator (never {@code null})
	 */
	protected abstract Comparator<T> getMappingComparator(HttpServletRequest request);


	/**
	 * A registry that maintains all mappings to handler methods, exposing methods
	 * to perform lookups and providing concurrent access.
	 * <p>Package-private for testing purposes.
	 */
	class MappingRegistry {

		private final Map<T, MappingRegistration<T>> registry = new HashMap<>();

		private final MultiValueMap<String, T> pathLookup = new LinkedMultiValueMap<>();

		private final Map<String, List<HandlerMethod>> nameLookup = new ConcurrentHashMap<>();

		private final Map<HandlerMethod, CorsConfiguration> corsLookup = new ConcurrentHashMap<>();

		private final ReentrantReadWriteLock readWriteLock = new ReentrantReadWriteLock();

		/**
		 * Return all registrations.
		 * @since 5.3
		 */
		public Map<T, MappingRegistration<T>> getRegistrations() {
			return this.registry;
		}

		/**
		 * Return matches for the given URL path. Not thread-safe.
		 * @see #acquireReadLock()
		 */
		@Nullable
		public List<T> getMappingsByDirectPath(String urlPath) {
			return this.pathLookup.get(urlPath);
		}


		/**
		 * Return handler methods by mapping name. Thread-safe for concurrent use.
		 */
		public List<HandlerMethod> getHandlerMethodsByMappingName(String mappingName) {
			return this.nameLookup.get(mappingName);
		}

		/**
		 * Return CORS configuration. Thread-safe for concurrent use.
		 */
		@Nullable
		public CorsConfiguration getCorsConfiguration(HandlerMethod handlerMethod) {
			HandlerMethod original = handlerMethod.getResolvedFromHandlerMethod();
			return this.corsLookup.get(original != null ? original : handlerMethod);
		}

		/**
		 * Acquire the read lock when using getMappings and getMappingsByUrl.
		 */
		public void acquireReadLock() {
			this.readWriteLock.readLock().lock();
		}

		/**
		 * Release the read lock after using getMappings and getMappingsByUrl.
		 */
		public void releaseReadLock() {
			this.readWriteLock.readLock().unlock();
		}

		/**
		 * 注册一个处理器方法及其映射关系
		 * <p>该方法将处理器方法与映射条件关联起来，并存储在各种索引结构中以便后续快速查找
		 *
		 * @param mapping 映射条件对象，包含匹配请求所需的所有条件（如路径、方法、参数等）
		 * @param handler 处理器对象，可以是bean名称(String)或实际的处理器实例(Object)
		 * @param method  处理器方法，即实际处理请求的Method对象
		 *                <p>
		 *                注册过程：
		 *                1. 获取写锁：确保线程安全，防止并发注册导致数据不一致
		 *                2. 创建HandlerMethod：将处理器和方法封装成HandlerMethod对象
		 *                3. 验证映射：检查是否已存在相同的映射，避免重复注册
		 *                4. 构建路径索引：将直接路径与映射关系建立索引，提高查找效率
		 *                5. 处理命名策略：如果配置了命名策略，为映射分配名称并建立名称索引
		 *                6. 处理CORS配置：初始化并存储跨域配置信息
		 *                7. 存储注册信息：将完整的映射注册信息存储到主注册表中
		 *                <p>
		 *                数据结构说明：
		 *                - registry：主注册表，存储映射到MappingRegistration的完整映射关系
		 *                - pathLookup：路径查找表，存储路径到映射的快速索引
		 *                - nameLookup：名称查找表，存储映射名称到处理器方法的索引
		 *                - corsLookup：CORS配置查找表，存储处理器方法到CORS配置的映射
		 *                <p>
		 *                线程安全：
		 *                - 使用ReentrantReadWriteLock确保并发访问的安全性
		 *                - 写操作（注册）需要获取写锁
		 *                - 读操作（查找）需要获取读锁
		 *                <p>
		 *                调用时机：
		 *                - 容器初始化阶段，扫描到@Controller等注解时自动调用
		 *                - 运行时通过registerMapping()方法手动注册
		 * @see #createHandlerMethod(Object, Method)
		 * @see #validateMethodMapping(HandlerMethod, Object)
		 * @see #getDirectPaths(Object)
		 * @see #initCorsConfiguration(Object, Method, Object)
		 */
		public void register(T mapping, Object handler, Method method) {
			// 获取写锁，确保注册过程的线程安全
			// 防止多个线程同时注册导致数据结构不一致
			this.readWriteLock.writeLock().lock();
			try {
				// 创建HandlerMethod对象，封装处理器和方法信息
				// HandlerMethod包含了处理请求所需的所有信息：bean实例、方法引用等
				HandlerMethod handlerMethod = createHandlerMethod(handler, method);

				// 验证方法映射，防止重复注册相同的映射
				// 如果已存在相同的映射且对应的处理器方法不同，则抛出异常
				validateMethodMapping(handlerMethod, mapping);

				// 获取映射的直接路径集合（不包含模式匹配的路径）
				// directPaths是精确匹配的路径，如"/users"，不包括"/users/*"这样的模式
				Set<String> directPaths = AbstractHandlerMethodMapping.this.getDirectPaths(mapping);

				// 为每个直接路径建立索引，提高请求处理时的查找效率
				// pathLookup是一个MultiValueMap，支持一个路径对应多个映射
				for (String path : directPaths) {
					// 路径 --->注解  先根据路径和请求方式找到对应的注解
					this.pathLookup.add(path, mapping);
				}

				// 处理映射命名策略
				String name = null;
				// 如果配置了命名策略，则为映射生成名称
				if (getNamingStrategy() != null) {
					// 使用命名策略生成映射名称，通常基于类名和方法名
					// 例如："UserController#getUsers"格式
					name = getNamingStrategy().getName(handlerMethod, mapping);
					// 将映射名称与处理器方法关联，建立名称索引
					addMappingName(name, handlerMethod);
				}


				CorsConfiguration corsConfig = initCorsConfiguration(handler, method, mapping);
				if (corsConfig != null) {
					corsConfig.validateAllowCredentials();
					this.corsLookup.put(handlerMethod, corsConfig);
				}

				// 将完整的映射注册信息存储到主注册表中
				// MappingRegistration封装了映射的所有相关信息
				// 路径 --->方法和注解信息 再根据路径找处理方法
				this.registry.put(mapping,
						new MappingRegistration<>(mapping, handlerMethod, directPaths, name, corsConfig != null));
			} finally {
				// 确保写锁最终被释放，避免死锁
				this.readWriteLock.writeLock().unlock();
			}
		}


		private void validateMethodMapping(HandlerMethod handlerMethod, T mapping) {
			MappingRegistration<T> registration = this.registry.get(mapping);
			HandlerMethod existingHandlerMethod = (registration != null ? registration.getHandlerMethod() : null);
			if (existingHandlerMethod != null && !existingHandlerMethod.equals(handlerMethod)) {
				throw new IllegalStateException(
						"Ambiguous mapping. Cannot map '" + handlerMethod.getBean() + "' method \n" +
						handlerMethod + "\nto " + mapping + ": There is already '" +
						existingHandlerMethod.getBean() + "' bean method\n" + existingHandlerMethod + " mapped.");
			}
		}

		private void addMappingName(String name, HandlerMethod handlerMethod) {
			List<HandlerMethod> oldList = this.nameLookup.get(name);
			if (oldList == null) {
				oldList = Collections.emptyList();
			}

			for (HandlerMethod current : oldList) {
				if (handlerMethod.equals(current)) {
					return;
				}
			}

			List<HandlerMethod> newList = new ArrayList<>(oldList.size() + 1);
			newList.addAll(oldList);
			newList.add(handlerMethod);
			this.nameLookup.put(name, newList);
		}

		public void unregister(T mapping) {
			this.readWriteLock.writeLock().lock();
			try {
				MappingRegistration<T> registration = this.registry.remove(mapping);
				if (registration == null) {
					return;
				}

				for (String path : registration.getDirectPaths()) {
					List<T> mappings = this.pathLookup.get(path);
					if (mappings != null) {
						mappings.remove(registration.getMapping());
						if (mappings.isEmpty()) {
							this.pathLookup.remove(path);
						}
					}
				}

				removeMappingName(registration);

				this.corsLookup.remove(registration.getHandlerMethod());
			}
			finally {
				this.readWriteLock.writeLock().unlock();
			}
		}

		private void removeMappingName(MappingRegistration<T> definition) {
			String name = definition.getMappingName();
			if (name == null) {
				return;
			}
			HandlerMethod handlerMethod = definition.getHandlerMethod();
			List<HandlerMethod> oldList = this.nameLookup.get(name);
			if (oldList == null) {
				return;
			}
			if (oldList.size() <= 1) {
				this.nameLookup.remove(name);
				return;
			}
			List<HandlerMethod> newList = new ArrayList<>(oldList.size() - 1);
			for (HandlerMethod current : oldList) {
				if (!current.equals(handlerMethod)) {
					newList.add(current);
				}
			}
			this.nameLookup.put(name, newList);
		}
	}


	static class MappingRegistration<T> {

		private final T mapping;

		private final HandlerMethod handlerMethod;

		private final Set<String> directPaths;

		@Nullable
		private final String mappingName;

		private final boolean corsConfig;

		public MappingRegistration(T mapping, HandlerMethod handlerMethod,
				@Nullable Set<String> directPaths, @Nullable String mappingName, boolean corsConfig) {

			Assert.notNull(mapping, "Mapping must not be null");
			Assert.notNull(handlerMethod, "HandlerMethod must not be null");
			this.mapping = mapping;
			this.handlerMethod = handlerMethod;
			this.directPaths = (directPaths != null ? directPaths : Collections.emptySet());
			this.mappingName = mappingName;
			this.corsConfig = corsConfig;
		}

		public T getMapping() {
			return this.mapping;
		}

		public HandlerMethod getHandlerMethod() {
			return this.handlerMethod;
		}

		public Set<String> getDirectPaths() {
			return this.directPaths;
		}

		@Nullable
		public String getMappingName() {
			return this.mappingName;
		}

		public boolean hasCorsConfig() {
			return this.corsConfig;
		}
	}


	/**
	 * A thin wrapper around a matched HandlerMethod and its mapping, for the purpose of
	 * comparing the best match with a comparator in the context of the current request.
	 */
	private class Match {

		private final T mapping;

		private final MappingRegistration<T> registration;

		public Match(T mapping, MappingRegistration<T> registration) {
			this.mapping = mapping;
			this.registration = registration;
		}

		public HandlerMethod getHandlerMethod() {
			return this.registration.getHandlerMethod();
		}

		public boolean hasCorsConfig() {
			return this.registration.hasCorsConfig();
		}

		@Override
		public String toString() {
			return this.mapping.toString();
		}
	}


	private class MatchComparator implements Comparator<Match> {

		private final Comparator<T> comparator;

		public MatchComparator(Comparator<T> comparator) {
			this.comparator = comparator;
		}

		@Override
		public int compare(Match match1, Match match2) {
			return this.comparator.compare(match1.mapping, match2.mapping);
		}
	}


	private static class EmptyHandler {

		@SuppressWarnings("unused")
		public void handle() {
			throw new UnsupportedOperationException("Not implemented");
		}
	}

}
