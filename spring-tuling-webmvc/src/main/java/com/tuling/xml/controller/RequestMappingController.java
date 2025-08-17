package com.tuling.xml.controller;

import com.tuling.javaconfig.initbinder.User;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.ModelAndView;

import java.util.Arrays;
import java.util.List;

/***
 * @Author 徐庶   QQ:1092002729
 * @Slogan 致敬大师，致敬未来的你
 */
@Controller
@RequestMapping("/request")
public class RequestMappingController {

    @RequestMapping("/mapping")
    public ModelAndView mapping(){
        System.out.println("RequestMappingController Working.");
        ModelAndView modelAndView = new ModelAndView("a");
        modelAndView.addObject("source","RequestMappingController");
        return modelAndView;
    }

    // localhost:8080/springmvc/mapping/ant1

    @RequestMapping(value="/mappin*")
    public String mapping02(){
        System.out.println("通配符——*");
        return "a";
    }
    @RequestMapping(value="/mappin?")
    public String mapping03(){
        System.out.println("通配符——？");
        return "a";
    }
    @RequestMapping(value="/**")
    public String mapping04(){
        System.out.println("**");
        return "a";
    }
    @RequestMapping("/updateUser")
    @ResponseBody
    public User updateUser(User user) {
        //返回修改后的 那么可能会把数据库中的年龄更新为空
        return user;
    }

    @ResponseBody
    @RequestMapping("/updateUser2")
    public User updateUser2(Integer id, String lastName) {
        User user=new User(id,lastName,null,null);

        return user;
    }



}
