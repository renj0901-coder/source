package com.tuling.web;

import com.tuling.config.TulingSpringbootConfig;
import com.tuling.servlet.TulingServlet;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.core.StandardContext;
import org.apache.catalina.startup.Tomcat;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;

/**
 * Created by xsls on 2019/8/18.
 */
public class TulingSpringBootApplication {

    public static void run() throws Exception {

        // 创建Tomcat应用对象
        Tomcat tomcat = new Tomcat();
        // 设置Tomcat的端口号
        tomcat.setPort(8080);

        tomcat.addWebapp("/tuling","D:\\server");

        // 创建Servlet
        tomcat.addServlet("/tuling", "tulingServlet", new TulingServlet());
        // Servlet映射
        //standardContext.addServletMappingDecoded("/hello", "tulingServlet");
        //启动tomcat容器
        tomcat.start();
        //等待
        tomcat.getServer().await();
    }

}
