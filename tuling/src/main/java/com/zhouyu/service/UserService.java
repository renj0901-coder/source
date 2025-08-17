package com.zhouyu.service;

import com.zhouyu.mapper.MemberMapper;
import com.zhouyu.mapper.OrderMapper;
import com.zhouyu.mapper.UserMapper;
import org.apache.ibatis.session.SqlSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * @author 周瑜
 */
@Component
public class UserService  implements UserServiceInterface{

	@Autowired
	private UserMapper userMapper; // Mybatis UserMapper代理对象-->Bean

	@Autowired
	private OrderMapper orderMapper;

	@Autowired
	private MemberMapper memberMapper;

	@Transactional(propagation = Propagation.REQUIRED)
	public void test() {
		System.out.println(userMapper.selectById());
		// System.out.println(orderMapper.selectById());
		// System.out.println(memberMapper.selectById());
		System.out.println("test");
	}

	public void print() {
		System.out.println("Hello, World!");
	}

}
