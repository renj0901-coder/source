package com.zhouyu.mybatis.spring;

import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;

import java.util.Set;

/**
 * @author 周瑜
 */
public class ZhouyuBeanDefinitionScanner extends ClassPathBeanDefinitionScanner {

	public ZhouyuBeanDefinitionScanner(BeanDefinitionRegistry registry) {
		super(registry);
	}

	@Override
	protected Set<BeanDefinitionHolder> doScan(String... basePackages) {
		Set<BeanDefinitionHolder> beanDefinitionHolders = super.doScan(basePackages);

		for (BeanDefinitionHolder beanDefinitionHolder : beanDefinitionHolders) {
			BeanDefinition beanDefinition = beanDefinitionHolder.getBeanDefinition();
			// 把mapper接口类名设置给ZhouyuFactoryBean的构造参数
			beanDefinition.getConstructorArgumentValues().addGenericArgumentValue(beanDefinition.getBeanClassName());
			// 把beanDefinition的beanClassName设置成ZhouyuFactoryBean
			beanDefinition.setBeanClassName(ZhouyuFactoryBean.class.getName());
		}

		return beanDefinitionHolders;
	}

	@Override
	protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
		return beanDefinition.getMetadata().isInterface();
	}
}
