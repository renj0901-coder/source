package com.zhouyu.mybatis.spring;

import com.zhouyu.mapper.UserMapper;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * @author 周瑜
 */

public class ZhouyuFactoryBean implements FactoryBean { // zhouyuFactoryBean--->UserMapper代理对象

	private Class mapperInterface;

	private SqlSession sqlSession;

	public ZhouyuFactoryBean(Class mapperInterface) {
		this.mapperInterface = mapperInterface;
	}

	@Autowired
	public void setSqlSession(SqlSessionFactory sqlSessionFactory) {
		sqlSessionFactory.getConfiguration().addMapper(mapperInterface);
		this.sqlSession = sqlSessionFactory.openSession();
	}

	@Override
	public Object getObject() throws Exception {
		return sqlSession.getMapper(mapperInterface);
	}

	@Override
	public Class<?> getObjectType() {
		return mapperInterface;
	}
}
