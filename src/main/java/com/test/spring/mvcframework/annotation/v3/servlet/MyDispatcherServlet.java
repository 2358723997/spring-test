package com.test.spring.mvcframework.annotation.v3.servlet;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.test.spring.mvcframework.annotation.MyAutowired;
import com.test.spring.mvcframework.annotation.MyController;
import com.test.spring.mvcframework.annotation.MyRequestMapping;
import com.test.spring.mvcframework.annotation.MyRequestParam;
import com.test.spring.mvcframework.annotation.MyService;

/**
 * MyDispatcherServlet类
 *
 * @author wangjixue
 * @date 7/11/21 11:30 AM
 */
public class MyDispatcherServlet extends HttpServlet {

    //保存application.properties配置文件中的内容
    private Properties contextConfig = new Properties();

    //保存扫描的所有的类名
    private Set<String> classNames = new HashSet<String>();

    //传说中的IOC容器，我们来揭开它的神秘面纱
    //为了简化程序，暂时不考虑ConcurrentHashMap
    // 主要还是关注设计思想和原理
    private Map<String, Object> ioc = new HashMap<String, Object>();

    //思考：为什么不用Map
    //你用Map的话，key，只能是url
    //Handler 本身的功能就是把url和method对应关系，已经具备了Map的功能
    //根据设计原则：冗余的感觉了，单一职责，最少知道原则，帮助我们更好的理解
    private List<Handler> handlerMapping = new ArrayList<Handler>();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        this.doPost(req, resp);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        try {
            doDispatcher(req,resp);
        }catch (Exception e){
            resp.getWriter().write("500 Internal Error!");
        }

    }

    //委派模式
    private void doDispatcher(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        Handler handler = getHandler(req);

        if(handler == null){
            resp.getWriter().write("404 Not Found!");
            return;
        }
        //获得方法的形参列表
        Class<?>[] paramTypes = handler.getParamTypes();

        //保存所有需要自动赋值的参数
        Object[] paramValues = new Object[paramTypes.length];

        Map<String,String[]> parameterMap = req.getParameterMap();

        for (Map.Entry<String, String[]> entry : parameterMap.entrySet()) {
            String value = Arrays.toString(entry.getValue()).replaceAll("\\[|\\]","").replaceAll(",\\s",",");

            //数据填充
            if(!handler.paramIndexMapping.containsKey(entry.getKey())){continue;}

            int index = handler.paramIndexMapping.get(entry.getKey());

            paramValues[index] = convert(paramTypes[index],value);

        }

        //设置方法的request 和response对象
        int reqIndex = handler.paramIndexMapping.get(HttpServletRequest.class.getName());
        paramValues[reqIndex] = req;

        int respIndex = handler.paramIndexMapping.get(HttpServletResponse.class.getName());
        paramValues[respIndex] = resp;

        Object returnValue = handler.getMethod().invoke(handler.getController(), paramValues);

        if(returnValue == null || returnValue instanceof Void){
            return;
        }
        resp.getWriter().write(returnValue.toString());
    }

    //url传过来的参数都是String类型的，HTTP是基于字符串协议
    //只需要把String转换为任意类型就好
    private Object convert(Class<?> type,String value){
        if(Integer.class == type){
            return Integer.valueOf(value);
        }

        if(Double.class == type){
            return Double.valueOf(value);
        }
        //TODO 此处可通过策略模式
        return value;
    }

    private Handler getHandler(HttpServletRequest req) {
        if(handlerMapping.isEmpty()){
            return null;
        }
        //绝对路径
        String uri = req.getRequestURI();
        String contextPath = req.getContextPath();
        //相对路径
        uri = uri.replaceAll(contextPath,"").replaceAll("/+","/");
        for (Handler handler : handlerMapping) {
            if(handler.getPattern().matcher(uri).matches()){
                return handler;
            }
        }

        return null;
    }

    @Override
    public void init(ServletConfig config) throws ServletException {
        //1. 加载配置文件
        loadConfig(config.getInitParameter("contextConfigLocation"));
        //2.扫描相关类
        scanner(config.getInitParameter("scanPackage"));
        //3.初始化扫描的类放入IOC容器中
        initInstance();
        //4.完成自动化注入
        doAutowired();
        //5.初始化HandlerMapping
        initHandlerMapping();

    }

    //策略模式
    //初始化url和Method的一对一对应关系
    private void initHandlerMapping() {
        if (ioc.isEmpty()) {
            return;
        }

        for (Map.Entry<String, Object> entry : ioc.entrySet()) {
            Class<?> clazz = entry.getValue().getClass();
            //保存写在类上面的@GPRequestMapping("/demo")
            if (!clazz.isAnnotationPresent(MyController.class)) {
                continue;
            }
            String baseUrl = "";
            if (clazz.isAnnotationPresent(MyRequestMapping.class)) {
                MyRequestMapping requestParam = clazz.getAnnotation(MyRequestMapping.class);
                baseUrl = requestParam.value();
            }
            //默认获取所有的public方法
            Method[] methods = clazz.getMethods();
            for (Method method : methods) {
                if (method.isAnnotationPresent(MyRequestMapping.class)) {
                    MyRequestMapping requestParam = method.getAnnotation(MyRequestMapping.class);
                    String regix = "/" + baseUrl + "/" + requestParam.value();
                    Pattern pattern = Pattern.compile(regix);
                    handlerMapping.add(new Handler(pattern, entry.getValue(), method));
                }
            }
        }
    }

    private void doAutowired() {
        if (ioc.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Object> entry : ioc.entrySet()) {
            //Declared 所有的，特定的 字段，包括private/protected/default
            //正常来说，普通的OOP编程只能拿到public的属性
            Field[] fields = entry.getValue().getClass().getDeclaredFields();
            for (Field field : fields) {
                if (!field.isAnnotationPresent(MyAutowired.class)) {continue;}
                MyAutowired annotation = field.getAnnotation(MyAutowired.class);

                String beanName = toLowerFirstCase(annotation.value());
                //如果用户没有自定义beanName，默认就根据类型注入
                if ("".equals(beanName.trim())) {
                    //获得接口的类型，作为key待会拿这个key到ioc容器中去取值
                    beanName = field.getType().getName();
                }
                //暴力访问
                field.setAccessible(true);
                //用反射机制，动态给字段赋值
                try {
                    field.set(entry.getValue(), ioc.get(beanName));
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    // 工厂模式
    private void initInstance() {
        if (classNames.isEmpty()) {
            return;
        }
        try {
            for (String className : classNames) {
                Class<?> clazz = Class.forName(className);
                //什么样的类才需要初始化呢？
                //加了注解的类，才初始化，怎么判断？
                //为了简化代码逻辑，主要体会设计思想，只举例 @Controller和@Service,
                // @Componment...就一一举例了
                if (clazz.isAnnotationPresent(MyController.class)) {
                    Object bean = clazz.newInstance();
                    //Spring默认类名首字母小写
                    String beanName = toLowerFirstCase(clazz.getSimpleName());
                    ioc.put(beanName, bean);
                } else if (clazz.isAnnotationPresent(MyService.class)) {
                    //1、自定义的beanName
                    MyService service = clazz.getAnnotation(MyService.class);
                    String beanName = service.value();
                    //2、默认类名首字母小写
                    if ("".equals(beanName.trim())) {
                        beanName = toLowerFirstCase(clazz.getSimpleName());
                    }
                    //3、根据类型自动赋值,投机取巧的方式
                    for (Class<?> i : clazz.getInterfaces()) {
                        if (ioc.containsKey(i.getName())) {
                            throw new Exception("The beanName is existed !");
                        }
                        beanName = i.getName();
                    }

                    Object bean = clazz.newInstance();

                    ioc.put(beanName, bean);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    private String toLowerFirstCase(String simpleName) {
        char[] chars = simpleName.toCharArray();
        chars[0] += 32;
        return String.valueOf(chars);
    }

    private void scanner(String scanPackage) {
        //scanPackage = com.gupaoedu.demo ，存储的是包路径
        //转换为文件路径，实际上就是把.替换为/就OK了
        //classpath
        URL url = this.getClass().getClassLoader().getResource("/" + scanPackage.replace("\\.", "/"));
        File file = new File(url.getFile());
        if (file.isDirectory()) {
            scanner(scanPackage + "." + file.getName());
        } else {
            if (!file.getName().endsWith(".class")) {
                return;
            }

            classNames.add(scanPackage + "." + file.getName().replace(".class", ""));
        }
    }

    private void loadConfig(String contextConfigLocation) {
        //直接从类路径下找到Spring主配置文件所在的路径
        //并且将其读取出来放到Properties对象中
        //相对于scanPackage=com.gupaoedu.demo 从文件中保存到了内存中
        InputStream resource = this.getClass().getClassLoader().getResourceAsStream(contextConfigLocation);
        try {
            contextConfig.load(resource);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                if (resource != null) {
                    resource.close();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

        }
    }
}

//保存一个url和一个Method的关系
 class Handler {
    //必须把url放到HandlerMapping才好理解吧
    private Pattern pattern;  //正则
    private Method method;
    private Object controller;
    private Class<?>[] paramTypes;

    public Pattern getPattern() {
        return pattern;
    }

    public Method getMethod() {
        return method;
    }

    public Object getController() {
        return controller;
    }

    public Class<?>[] getParamTypes() {
        return paramTypes;
    }

    //形参列表
    //参数的名字作为key,参数的顺序，位置作为值
    public Map<String, Integer> paramIndexMapping;

    public Handler(Pattern pattern, Object controller, Method method) {
        this.pattern = pattern;
        this.method = method;
        this.controller = controller;

        paramTypes = method.getParameterTypes();

        paramIndexMapping = new HashMap<String, Integer>();
        putParamIndexMapping(method);
    }

    private void putParamIndexMapping(Method method) {

        //提取方法中加了注解的参数
        //把方法上的注解拿到，得到的是一个二维数组
        //因为一个参数可以有多个注解，而一个方法又有多个参数
        Annotation[][] pa = method.getParameterAnnotations();
        for (int i = 0; i < pa.length; i++) {
            for (Annotation a : pa[i]) {
                if (a instanceof MyRequestParam) {
                    String paramName = ((MyRequestParam) a).value();
                    if (!"".equals(paramName.trim())) {
                        paramIndexMapping.put(paramName, i);
                    }
                }
            }
        }

        //提取方法中的request和response参数
        Class<?>[] paramsTypes = method.getParameterTypes();
        for (int i = 0; i < paramsTypes.length; i++) {
            Class<?> type = paramsTypes[i];
            if (type == HttpServletRequest.class ||
                type == HttpServletResponse.class) {
                paramIndexMapping.put(type.getName(), i);
            }
        }

    }

}
