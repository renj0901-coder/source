package com.zhouyu;

import com.zhouyu.service.UserService;
import com.zhouyu.service.UserServiceInterface;

import java.lang.reflect.Proxy;

/**
 * 文件描述
 *
 * @ProductName: Hundsun am4-ins3.0-zx
 * @ProjectName: spring
 * @Package: com.zhouyu
 * @Description: note
 * @Author: panjw-38059
 * @Date: 2025/8/10 21:15
 * @UpdateUser: 15822
 * @UpdateDate: 2025/8/10 21:15
 * @UpdateRemark: The modified content
 * @Version: 1.0* @Version: 1.0
 * *
 * Copyright © 2025 Hundsun Technologies Inc. All Rights Reserved* Copyright © 2025 Hundsun Technologies Inc. All Rights Reserved
 **/
public class JDKProxyTest {
	public static void main(String[] args) {
		UserService target = new UserService();
		UserServiceInterface proxyInstance = (UserServiceInterface)Proxy.newProxyInstance(JDKProxyTest.class.getClassLoader(), new Class[]{UserServiceInterface.class}, (o, method, objects) -> {
			// o是代理对象，method是方法，objects是参数
			System.out.println("before..." + method.getName());
			Object result = method.invoke(target, objects);
			System.out.println("after..." + method.getName());
			return result;
		});
		proxyInstance.test();
		proxyInstance.print();
	}
}
