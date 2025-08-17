package com.zhouyu;

import com.zhouyu.service.UserService;
import com.zhouyu.service.UserServiceInterface;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.aop.MethodBeforeAdvice;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.cglib.proxy.MethodInterceptor;
import org.springframework.cglib.proxy.MethodProxy;

import java.lang.reflect.Method;

/**
 * 文件描述
 *
 * @ProductName: Hundsun am4-ins3.0-zx
 * @ProjectName: spring
 * @Package: com.zhouyu
 * @Description: note
 * @Author: panjw-38059
 * @Date: 2025/8/10 21:31
 * @UpdateUser: 15822
 * @UpdateDate: 2025/8/10 21:31
 * @UpdateRemark: The modified content
 * @Version: 1.0* @Version: 1.0
 * *
 * Copyright © 2025 Hundsun Technologies Inc. All Rights Reserved* Copyright © 2025 Hundsun Technologies Inc. All Rights Reserved
 **/
public class ProxyFactoryTest {
	public static void main(String[] args) {
		UserService target = new UserService();
		ProxyFactory proxyFactory = new ProxyFactory();
		proxyFactory.setTarget(target);
		proxyFactory.setInterfaces(UserServiceInterface.class);
		proxyFactory.addAdvice(new MethodBeforeAdvice() {

			@Override
			public void before(Method method, Object[] args, Object target) throws Throwable {
				System.out.println("before...");
			}

		});

		UserServiceInterface userService = (UserServiceInterface) proxyFactory.getProxy();
		userService.test();
	}
}
