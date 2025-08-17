package com.tuling.xml.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.context.request.async.WebAsyncTask;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/***
 * @Author 徐庶   QQ:1092002729
 * @Slogan 致敬大师，致敬未来的你
 *
 * 注意：异步模式对前端来说，是无感知的，这是后端的一种技术。
 * 所以这个和我们自己开启一个线程处理，立马返回给前端是有非常大的不同的，需要注意~
 */
@Controller
@RequestMapping("/async")
public class AsyncController {

    @ResponseBody
    @GetMapping("/async1")
    public Callable<String> helloGet() throws Exception {
        System.out.println(Thread.currentThread().getName() + " 主线程start");

        // 前端页面等待5秒出现结果。
        Callable<String> callable = () -> {
            System.out.println(Thread.currentThread().getName() + " 子线程start");
            TimeUnit.SECONDS.sleep(5); //模拟处理业务逻辑，花费了5秒钟
            System.out.println(Thread.currentThread().getName() + " 子线程end");

            // 这里稍微小细节一下：最终返回的不是Callable对象，而是它里面的内容
            return "hello world";
        };

        System.out.println(Thread.currentThread().getName() + " 主线程end");
        return callable;
    }


    @ResponseBody
    @GetMapping("/async2")
    public WebAsyncTask<String> asyncTask() throws Exception {
        System.out.println(Thread.currentThread().getName() + " 主线程start");

        Callable<String> callable = () -> {
            System.out.println(Thread.currentThread().getName() + " 子线程start");
            TimeUnit.SECONDS.sleep(5); //模拟处理业务逻辑，话费了5秒钟
            System.out.println(Thread.currentThread().getName() + " 子线程end");

            return "hello world";
        };

        // 采用WebAsyncTask 返回 这样可以处理超时和错误 同时也可以指定使用的Excutor名称

        WebAsyncTask<String> webAsyncTask = new WebAsyncTask<>(3000, callable);
        // 注意：onCompletion表示完成，不管你是否超时、是否抛出异常，这个函数都会执行的
        webAsyncTask.onCompletion(() -> System.out.println("程序[正常执行]完成的回调"));

        // 由于我们设置了超时时间为3000ms，而业务处理是5s，所以会执行onTimeout这个回调函数。
        // 因此页面是会显示“程序[超时]的回调”这几个字。其执行的过程同Callback。
        webAsyncTask.onTimeout(() -> "程序[超时]的回调");
        // 备注：这个是Spring5新增的
        webAsyncTask.onError(() -> "程序[出现异常]的回调");


        System.out.println(Thread.currentThread().getName() + " 主线程end");
        return webAsyncTask;
    }




    private List<DeferredResult<String>> deferredResultList = new ArrayList<>();

    /*
    DeferredResult使用方式与Callable类似，但在返回结果上不一样，
    它返回的时候实际结果可能没有生成，实际的结果可能会在另外的线程里面设置到DeferredResult中去。
     */
    @ResponseBody
    @GetMapping("/async3")
    public DeferredResult<String> deferredResult() throws Exception {
        DeferredResult<String> deferredResult = new DeferredResult<>();


        new Thread(() -> {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            deferredResult.setResult("say hello");
        }).start();

        // 可以先存起来，等待触发,配合下面的deferredResultSet看效果
        // 可以是MQ触发、定时任务触发、或者其佘情况触发
        //deferredResultList.add(deferredResult);

        System.out.println("over...");
        return deferredResult;
    }

    // 我们第一个请求/hello，会先deferredResult存起来，然后前端页面是一直等待（转圈状态）的。
    // 直到我发第二个请求：setHelloToAll，所有的相关页面才会有响应~~
    @ResponseBody
    @GetMapping("/async3/set")
    public void deferredResultSet() throws Exception {
        // 让所有hold住的请求给与响应
        deferredResultList.forEach(d -> d.setResult("say hello to all"));
    }
}
