package com.tuling.aop;

import com.tuling.aop.advice.ZhouyuBeforeAdvice;
import org.aopalliance.aop.Advice;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.ClassFilter;
import org.springframework.aop.MethodMatcher;
import org.springframework.aop.Pointcut;
import org.springframework.aop.PointcutAdvisor;

import java.lang.reflect.Method;

/**
 * 文件描述
 *
 * @ProductName: Hundsun am4-ins3.0-zx
 * @ProjectName: spring
 * @Package: com.tuling.aop
 * @Description: note
 * @Author: panjw-38059
 * @Date: 2025/8/11 22:49
 * @UpdateUser: 15822
 * @UpdateDate: 2025/8/11 22:49
 * @UpdateRemark: The modified content
 * @Version: 1.0* @Version: 1.0
 * *
 * Copyright © 2025 Hundsun Technologies Inc. All Rights Reserved* Copyright © 2025 Hundsun Technologies Inc. All Rights Reserved
 **/
public class MyPointcutAdvisor implements PointcutAdvisor {
	/**
	 * Return the advice part of this aspect. An advice may be an
	 * interceptor, a before advice, a throws advice, etc.
	 *
	 * @return the advice that should apply if the pointcut matches
	 * @see MethodInterceptor
	 * @see BeforeAdvice
	 * @see ThrowsAdvice
	 * @see AfterReturningAdvice
	 */
	@Override
	public Advice getAdvice() {
		return new ZhouyuBeforeAdvice();
	}

	/**
	 * Return whether this advice is associated with a particular instance
	 * (for example, creating a mixin) or shared with all instances of
	 * the advised class obtained from the same Spring bean factory.
	 * <p><b>Note that this method is not currently used by the framework.</b>
	 * Typical Advisor implementations always return {@code true}.
	 * Use singleton/prototype bean definitions or appropriate programmatic
	 * proxy creation to ensure that Advisors have the correct lifecycle model.
	 *
	 * @return whether this advice is associated with a particular target instance
	 */
	@Override
	public boolean isPerInstance() {
		return false;
	}

	/**
	 * Get the Pointcut that drives this advisor.
	 */
	@Override
	public Pointcut getPointcut() {
		return new Pointcut() {
			@Override
			public ClassFilter getClassFilter() {
				return null;
			}

			@Override
			public MethodMatcher getMethodMatcher() {
				return new MethodMatcher() {
					@Override
					public boolean matches(Method method, Class<?> targetClass) {
						// 判断方法是否匹配
						return method.getName().equals("test") || method.getName().equals("b");
					}

					@Override
					public boolean isRuntime() {
						// 判断是否是运行时参数进行匹配，返回true才会执行下面的matches方法
						return true;
					}

					@Override
					public boolean matches(Method method, Class<?> targetClass, Object... args) {
						// 运行时参数进行匹配
						return false;
					}
				};
			}
		};
	}
}
