package com.zhouyu;

import com.zhouyu.boot.ZhouyuSpringApplication;
import com.zhouyu.boot.ZhouyuSpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.DispatcherServlet;

@ZhouyuSpringBootApplication
public class Test {

	public static void main(String[] args) {
		ZhouyuSpringApplication.run(Test.class);
	}

}
