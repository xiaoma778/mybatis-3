package org.apache.ibatis.executor.loader;

import java.lang.reflect.Method;

import net.sf.cglib.proxy.Enhancer;
import net.sf.cglib.proxy.MethodInterceptor;
import net.sf.cglib.proxy.MethodProxy;
import org.junit.jupiter.api.Test;

/**
 * cglib 简单用法测试
 * @Author: maqi
 * @Date: 2020-03-05 03:00
 * @Version 1.0
 */
public class CGLibTest {

    private class CglibProxy implements MethodInterceptor {

        private Enhancer enhancer = new Enhancer();

        public Object getProxy(Class clazz) {
            enhancer.setSuperclass(clazz);// 指定生成的代理类的父类
            enhancer.setCallback(this);// 设置 Callback 对象
            return enhancer.create();// 通过字节码技术动态创建子类实例
        }

        @Override
        public Object intercept(Object obj, Method method, Object[] args, MethodProxy methodProxy) throws Throwable {
            System.out.println("前置处理");
            Object result = methodProxy.invokeSuper(obj, args);// 调用父类中的方法
            System.out.println("后置处理");
            return result;
        }
    }

    protected String method(String str) {
        System.out.println(str);
        return "CGLibTest.method():" + str;
    }

    //interface Person {
    //    String eat();
    //}

    @Test
    public void test() {
        CglibProxy proxy = new CglibProxy();
        CGLibTest proxyImpl = (CGLibTest)proxy.getProxy(CGLibTest.class);

        // 调用代理对象的 method() 方法
        String result = proxyImpl.method("test");
        System.out.println(result);

        //Person personProxy = (Person)proxy.getProxy(Person.class);
        //personProxy.eat();
    }
}
