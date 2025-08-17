package com.tuling;

import com.tuling.config.TulingSpringbootConfig;
import com.tuling.web.TulingExternalSpringBootApplication;
import com.tuling.web.TulingSpringBootApplication;
import org.apache.catalina.LifecycleException;

import javax.servlet.ServletException;

/**3
 */
public class MainStarter {

    public static void main(String[] args) throws Exception {
        TulingExternalSpringBootApplication.run();
    }
}
