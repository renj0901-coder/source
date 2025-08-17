package com.zhouyu;

import com.zhouyu.mapper.OrderMapper;
import com.zhouyu.mapper.UserMapper;
import com.zhouyu.mybatis.spring.ZhouyuFactoryBean;
import com.zhouyu.service.UserService;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

public class Test {

	public static void main(String[] args) {

		AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
		context.register(AppConfig.class);
		context.refresh();

		UserService userService = (UserService) context.getBean("userService");
		userService.test();


	}
}
