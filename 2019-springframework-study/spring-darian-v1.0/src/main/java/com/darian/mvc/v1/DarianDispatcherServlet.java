
package com.darian.mvc.v1;

import com.darian.mvc.v1.annotation.DarianAutowrited;
import com.darian.mvc.v1.annotation.DarianController;
import com.darian.mvc.v1.annotation.DarianRequestMapping;
import com.darian.mvc.v1.annotation.DarianService;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public class DarianDispatcherServlet extends HttpServlet {

    private static String SCANN_PACKAGE_KEY = "scannPackage";
    private Properties contenxtConfig = new Properties();

    private List<String> classNameList = new ArrayList<>();
    private Map<String, Object> ioc_map = new HashMap<>();
    private Map<String, Method> handler_mappings = new HashMap<>();

    @Override
    public void init(ServletConfig config) throws ServletException {
        // 1. 加载配置文件
        doLoadConfig(config.getInitParameter("contextConfigLocation"));
        // 2. 扫描相关的类
        doScaner(contenxtConfig.getProperty(SCANN_PACKAGE_KEY));
        // 3. 初始化扫描到的类，并且将他们放入到 IOC 容器中
        doInstance();
        // 4. 完成依赖注入
        doAutowrited();
        // 5. 初始化 HandlerMapping
        initHandlerMapping();

        System.err.println("Darian small Spring Framework is init");
    }

    @Override
    public void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        // 6. 调用，运行阶段
        try {
            doDispatcher(req, resp);
        } catch (Exception e) {
            System.err.println(e);

            resp.getWriter().write("{\"code\":500,\"msg\":\"自定义DispatcherServlet#doDispatcher发生错误！！！\"}\n\n\n" +
                    "ExceptionMessage:[" + e.getMessage() + "]");
        }
    }

    /***
     *
     * @param servletConfigPath classpath:application.properties
     */
    private void doLoadConfig(String servletConfigPath) {
        try (InputStream fis = this.getClass().getClassLoader()
                .getResourceAsStream(servletConfigPath.replace("classpath:", ""))) {
            contenxtConfig.load(fis);
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    private void doScaner(String scanPackage) {
        URL url = this.getClass().getClassLoader().getResource("/" +
                scanPackage.replaceAll("\\.", "/"));

        File classPath = new File(url.getFile());
        for (File file : classPath.listFiles()) {
            if (file.isDirectory()) {
                // 递归调用自己
                doScaner(scanPackage + "." + file.getName());
            } else {
                if (file.getName().endsWith(".class")) {
                    String className = scanPackage + "."
                            + file.getName().replaceAll(".class", "");
                    classNameList.add(className);
                }
            }
        }

    }

    private void doInstance() {
        // 初始化，为 DI 做准备
        if (classNameList.isEmpty()) {
            return;
        }
        try {
            for (String className : classNameList) {
                Class<?> clazz = Class.forName(className);
                if (clazz.isAnnotationPresent(DarianController.class)) {
                    Object instance = clazz.newInstance();
                    String beanName = toLowerfistCase(clazz.getSimpleName());
                    ioc_map.put(beanName, instance);
                } else if (clazz.isAnnotationPresent(DarianService.class)) {
                    DarianService darianService = clazz.getAnnotation(DarianService.class);
                    String beanName = darianService.value();
                    // 1. 默认类名小写
                    // 2. 自定义 beanName

                    if ("".equals(beanName)) {
                        beanName = toLowerfistCase(clazz.getSimpleName());
                    }

                    Object instance = clazz.newInstance();
                    ioc_map.put(beanName, instance);
                    // 3. 根据类型自动赋值
                    for (Class<?> i : clazz.getInterfaces()) {
                        if (ioc_map.containsKey(i.getName())) {
                            throw new RuntimeException("The beanName [" + i.getName() + "] is exists !");
                        }
                        ioc_map.put(i.getName(), instance);
                    }
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private String toLowerfistCase(String className) {
        char[] chars = className.toCharArray();
        // 大写和小写 ASCII 码相差 32，只看 类名首字母大写
        chars[0] += 32;
        return String.valueOf(chars);
    }

    private void doAutowrited() {
        if (ioc_map.isEmpty()) return;
        for (Map.Entry<String, Object> entry : ioc_map.entrySet()) {
            // Declared 所有的字段，private / protected / public
            Field[] fields = entry.getValue().getClass().getDeclaredFields();
            for (Field field : fields) {
                if (field.isAnnotationPresent(DarianAutowrited.class)) {
                    DarianAutowrited darianAutowrited = field.getAnnotation(DarianAutowrited.class);
                    String beanName = darianAutowrited.value().trim();
                    if ("".equals(beanName)) {
                        beanName = field.getType().getName();
                    }
                    // 强制赋值，
                    field.setAccessible(Boolean.TRUE);
                    try {
                        field.set(entry.getValue(), ioc_map.get(beanName));
                    } catch (IllegalAccessException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    // 初始话 url 和 method 的一对一 对应关系
    private void initHandlerMapping() {
        if (ioc_map.isEmpty()) return;
        for (Map.Entry<String, Object> entry : ioc_map.entrySet()) {
            Class<?> clazz = entry.getValue().getClass();
            if (!clazz.isAnnotationPresent(DarianController.class)) continue;

            // 保存在类上边的 @DarianRequestMapping("/demo")
            String baseUrl = "";
            if (clazz.isAnnotationPresent(DarianRequestMapping.class)) {
                baseUrl = clazz.getAnnotation(DarianRequestMapping.class).value();
            }

            // 默认获取所有的 public 方法
            for (Method method : clazz.getMethods()) {
                if (!method.isAnnotationPresent(DarianRequestMapping.class)) continue;

                String url = baseUrl + "/" + method.getAnnotation(DarianRequestMapping.class).value();
                url = url.replaceAll("/+", "/");
                handler_mappings.put(url, method);
                System.err.println("mapping   url:[" + url + "], method:[" + method + "]");
            }
        }
    }

    private void doDispatcher(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        {
            // 绝对路径
            String url = req.getRequestURI();
            // 处理相对路径
            String contextPath = req.getContextPath();
            url = url.replace(contextPath, "").replaceAll("/+", "/");

            if (!this.handler_mappings.containsKey(url)) {
                throw new Exception("{\"code\":404,\"msg\":\"找不到对应的 mappings ！！！Mapping:[" + url + "]\"}");
            }

            Method method = this.handler_mappings.get(url);
            String beanName = toLowerfistCase(method.getDeclaringClass().getSimpleName());

            Map<String, String[]> parameterMap = req.getParameterMap();
            Object returnObject = method.invoke(ioc_map.get(beanName), new Object[]{req, resp, parameterMap.get("name")[0]});

            resp.getWriter().write(returnObject.toString());

        }

    }

    @Override
    public void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        this.doPost(req, resp);
    }

    @Override
    public void service(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        this.doPost(req, resp);
    }
}