package com.zhouyu;


import com.zhouyu.mapper.UserMapper;
import com.zhouyu.mybatis.spring.ZhouyuImportBeanDefinitionRegistrar;
import com.zhouyu.mybatis.spring.ZhoyuMapperScan;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.annotation.MapperScan;
import org.mybatis.spring.mapper.MapperScannerConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;

@ComponentScan("com.zhouyu")
// @ZhoyuMapperScan("com.zhouyu.mapper")
@MapperScan("com.zhouyu.mapper")
@EnableAspectJAutoProxy
@EnableTransactionManagement
public class AppConfig {

	@Bean
	public SqlSessionFactory sqlSessionFactory() throws IOException {
		InputStream inputStream = Resources.getResourceAsStream("mybatis.xml");
		SqlSessionFactory sqlSessionFactory = new SqlSessionFactoryBuilder().build(inputStream);
		return sqlSessionFactory;
	}

//	@Bean
//	public JdbcTemplate jdbcTemplate() {
//		return new JdbcTemplate(dataSource());
//	}

	@Bean
	public PlatformTransactionManager transactionManager() {
		DataSourceTransactionManager transactionManager = new DataSourceTransactionManager();
		transactionManager.setDataSource(dataSource());
		// 设置全局事务回滚
		/**
		 * 1. 默认行为（true）
			 * 内层事务抛出异常时，即使外层事务捕获了该异常，外层事务也会被强制标记为 rollback-only。
			 * 外层事务提交时，因检测到 rollback-only标记，会抛出 UnexpectedRollbackException，导致整个事务回滚。
			 * 适用场景：需严格保证事务原子性，内层失败必须全局回滚。
		 * 2. 关闭全局回滚（false）
			 * 内层事务失败后，外层事务不会被强制标记为回滚。
			 * 外层事务可自主决定提交或回滚（如捕获异常后继续执行业务）。
			 * 风险：可能导致数据不一致（如内层已回滚，外层部分提交）
		 */
		transactionManager.setGlobalRollbackOnParticipationFailure(false);
		return transactionManager;
	}



	@Bean
	public DataSource dataSource() {
		DriverManagerDataSource dataSource = new DriverManagerDataSource();
		dataSource.setUrl("jdbc:mysql://127.0.0.1:3306/tuling?characterEncoding=utf-8&amp;useSSL=false");
		dataSource.setUsername("root");
		dataSource.setPassword("Zhouyu123456***");
		return dataSource;
	}


//	@Bean
//	public MapperScannerConfigurer configurer(){
//		MapperScannerConfigurer configurer = new MapperScannerConfigurer();
//		configurer.setBasePackage("com.zhouyu.mapper");
//
//		return configurer;
//	}

//	@Bean
//	public SqlSessionFactory sqlSessionFactory() throws Exception {
//		SqlSessionFactoryBean sessionFactoryBean = new SqlSessionFactoryBean();
//		sessionFactoryBean.setDataSource(dataSource());
//		return sessionFactoryBean.getObject();
//	}




}
