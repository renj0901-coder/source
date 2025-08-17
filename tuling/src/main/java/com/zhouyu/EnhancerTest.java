package com.zhouyu;

import com.zhouyu.service.UserService;
import org.springframework.cglib.proxy.Callback;
import org.springframework.cglib.proxy.CallbackFilter;
import org.springframework.cglib.proxy.Enhancer;
import org.springframework.cglib.proxy.MethodInterceptor;
import org.springframework.cglib.proxy.MethodProxy;
import org.springframework.cglib.proxy.NoOp;

import java.lang.reflect.Method;

/**
 * 文件描述
 *
 * @ProductName: Hundsun am4-ins3.0-zx
 * @ProjectName: spring
 * @Package: com.zhouyu
 * @Description: note
 * @Author: panjw-38059
 * @Date: 2025/8/10 20:52
 * @UpdateUser: 15822
 * @UpdateDate: 2025/8/10 20:52
 * @UpdateRemark: The modified content
 * @Version: 1.0* @Version: 1.0
 * *
 * Copyright © 2025 Hundsun Technologies Inc. All Rights Reserved* Copyright © 2025 Hundsun Technologies Inc. All Rights Reserved
 **/
public class EnhancerTest {
	public static void main(String[] args) {
		UserService target = new UserService();

		// 通过cglib技术
		Enhancer enhancer = new Enhancer();
		enhancer.setSuperclass(UserService.class);

		// 定义额外逻辑，也就是代理逻辑
		enhancer.setCallbacks(new Callback[]{new MethodInterceptor() {
			@Override
			public Object intercept(Object o, Method method, Object[] objects, MethodProxy methodProxy) throws Throwable {
				// o是代理对象，target是目标对象，objects是参数，methodProxy是代理方法，method是目标方法
				System.out.println("before..." + method.getName());
				// 执行目标方法 3种执行
				// Object result = methodProxy.invoke(target, objects);
				// Object result = method.invoke(target, objects);
				Object result = methodProxy.invokeSuper(o, objects);

				System.out.println("after..." + method.getName());
				return result;
			}
		}, NoOp.INSTANCE});

		// 根据方法名进行拦截，返回上面回调方法数组的索引
		enhancer.setCallbackFilter(new CallbackFilter() {
			@Override
			public int accept(Method method) {
				if ("print".equals(method.getName())) {
					return 0;
				} else if ("test".equals(method.getName())) {
					return 1;
				}
				return 1;
			}
		});
		// 动态代理所创建出来的UserService对象
		UserService userService = (UserService) enhancer.create();

		// 执行这个userService的test方法时，就会额外会执行一些其他逻辑
		userService.test();
		userService.print();


	}
}
