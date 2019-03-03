package com.taobao.arthas.agent;

import java.arthas.Spy;
import java.io.*;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.jar.JarFile;

/**
 * 代理启动类
 * agent的启动类
 * @author vlinux on 15/5/19.
 */
public class AgentBootstrap {

    // 通知编织者
    private static final String ADVICEWEAVER = "com.taobao.arthas.core.advisor.AdviceWeaver";
    private static final String ON_BEFORE = "methodOnBegin";
    private static final String ON_RETURN = "methodOnReturnEnd";
    private static final String ON_THROWS = "methodOnThrowingEnd";
    private static final String BEFORE_INVOKE = "methodOnInvokeBeforeTracing";
    private static final String AFTER_INVOKE = "methodOnInvokeAfterTracing";
    private static final String THROW_INVOKE = "methodOnInvokeThrowTracing";
    private static final String RESET = "resetArthasClassLoader";
    // 间谍类 jar 包
    private static final String ARTHAS_SPY_JAR = "arthas-spy.jar";
    // 配置类
    private static final String ARTHAS_CONFIGURE = "com.taobao.arthas.core.config.Configure";
    // arthas 服务端启动类
    private static final String ARTHAS_BOOTSTRAP = "com.taobao.arthas.core.server.ArthasBootstrap";
    private static final String TO_CONFIGURE = "toConfigure";
    private static final String GET_JAVA_PID = "getJavaPid";
    private static final String GET_INSTANCE = "getInstance";
    private static final String IS_BIND = "isBind";
    private static final String BIND = "bind";

    private static PrintStream ps = System.err;
    static {
        try {
            // 日志文件
            File log = new File(System.getProperty("user.home") + File.separator + "logs" + File.separator
                    + "arthas" + File.separator + "arthas.log");
            if (!log.exists()) {
                log.getParentFile().mkdirs();
                log.createNewFile();
            }
            ps = new PrintStream(new FileOutputStream(log, true));
        } catch (Throwable t) {
            t.printStackTrace(ps);
        }
    }

    // 自定义类加载器 ，全局持有classloader用于隔离 Arthas 实现
    private static volatile ClassLoader arthasClassLoader;

    /**
     * 运行前的 agent 加载
     * @param args
     * @param inst
     */
    public static void premain(String args, Instrumentation inst) {
        main(args, inst);
    }

    /**
     * 运行时的 agent 加载
     * @param args
     * @param inst
     */
    public static void agentmain(String args, Instrumentation inst) {
        main(args, inst);
    }

    /**
     * 让下次再次启动时有机会重新加载
     */
    public synchronized static void resetArthasClassLoader() {
        arthasClassLoader = null;
    }

    /**
     * Spy添加到BootstrapClassLoader,取得自定义 ArthasClassloader
     * @param inst
     * @param spyJarFile
     * @param agentJarFile
     * @return
     * @throws Throwable
     */
    private static ClassLoader getClassLoader(Instrumentation inst, File spyJarFile, File agentJarFile) throws Throwable {
        /**
         * 将Spy添加到BootstrapClassLoader
         * 为什么要使用 BootstrapClassLoader ？
         * 目的：使目标进程的java应用（AppClassLoader 加载的）可以访问Spy类，
         * 而且自定义 ArthasClassloader 也可以访问 Spy类
         * （classloader双亲委派特性，子classloader可以访问父classloader加载的类）
         *
         * appendToBootstrapClassLoaderSearch() 方法是由 InstrumentationImpl 实现的
         */
        inst.appendToBootstrapClassLoaderSearch(new JarFile(spyJarFile));

        /**
         * 构造自定义的类加载器，尽量减少Arthas对现有工程的侵蚀
         * ArthasClassloader extends URLClassLoader
         */
        return loadOrDefineClassLoader(agentJarFile);
    }

    private static ClassLoader loadOrDefineClassLoader(File agentJar) throws Throwable {
        if (arthasClassLoader == null) {
            arthasClassLoader = new ArthasClassloader(new URL[]{agentJar.toURI().toURL()});
        }
        return arthasClassLoader;
    }

    private static void initSpy(ClassLoader classLoader) throws ClassNotFoundException, NoSuchMethodException {
        // 使用 ArthasClassloader#loadClass方法，加载com.taobao.arthas.core.advisor.AdviceWeaver类
        Class<?> adviceWeaverClass = classLoader.loadClass(ADVICEWEAVER);
        // 将AdviceWeaver类里面的methodOnBegin、methodOnReturnEnd、methodOnThrowingEnd等方法取出赋值给Spy类对应的方法
        Method onBefore = adviceWeaverClass.getMethod(ON_BEFORE, int.class, ClassLoader.class, String.class,
                String.class, String.class, Object.class, Object[].class);
        Method onReturn = adviceWeaverClass.getMethod(ON_RETURN, Object.class);
        Method onThrows = adviceWeaverClass.getMethod(ON_THROWS, Throwable.class);
        Method beforeInvoke = adviceWeaverClass.getMethod(BEFORE_INVOKE, int.class, String.class, String.class, String.class);
        Method afterInvoke = adviceWeaverClass.getMethod(AFTER_INVOKE, int.class, String.class, String.class, String.class);
        Method throwInvoke = adviceWeaverClass.getMethod(THROW_INVOKE, int.class, String.class, String.class, String.class);
        Method reset = AgentBootstrap.class.getMethod(RESET);
        /**
         * Spy类里面的方法又会通过ASM字节码增强的方式，编织到目标代码的方法里面。
         * 使得Spy 间谍类可以关联由AppClassLoader加载的目标进程的业务类和ArthasClassloader加载的arthas类，
         * 因此Spy类可以看做两者之间的桥梁
         */
        Spy.initForAgentLauncher(classLoader, onBefore, onReturn, onThrows, beforeInvoke, afterInvoke, throwInvoke, reset);
    }

    private static synchronized void main(final String args, final Instrumentation inst) {
        try {
            ps.println("Arthas server agent start...");
            // 传递的args参数分两个部分:agentJar路径和agentArgs, 分别是Agent的JAR包路径和期望传递到服务端的参数
            int index = args.indexOf(';');
            String agentJar = args.substring(0, index);
            final String agentArgs = args.substring(index, args.length());

            // Agent jar file
            File agentJarFile = new File(agentJar);
            if (!agentJarFile.exists()) {
                ps.println("Agent jar file does not exist: " + agentJarFile);
                return;
            }

            // arthas-spy.jar
            File spyJarFile = new File(agentJarFile.getParentFile(), ARTHAS_SPY_JAR);
            if (!spyJarFile.exists()) {
                ps.println("Spy jar file does not exist: " + spyJarFile);
                return;
            }

            /**
             * Use a dedicated thread to run the binding logic to prevent possible memory leak. #195
             * 使用专用线程运行绑定逻辑以防止可能的内存泄漏
             */
            final ClassLoader agentLoader = getClassLoader(inst, spyJarFile, agentJarFile);
            initSpy(agentLoader);

            Thread bindingThread = new Thread() {
                @Override
                public void run() {
                    try {
                        // 启动 arthas server
                        bind(inst, agentLoader, agentArgs);
                    } catch (Throwable throwable) {
                        throwable.printStackTrace(ps);
                    }
                }
            };

            // 设置启动线程的名称
            bindingThread.setName("arthas-binding-thread");
            // 启动线程启动
            bindingThread.start();
            //等待该线程终止,join()方法后面的代码，只有等到子线程结束了才能执行
            bindingThread.join();
        } catch (Throwable t) {
            t.printStackTrace(ps);
            try {
                if (ps != System.err) {
                    ps.close();
                }
            } catch (Throwable tt) {
                // ignore
            }
            throw new RuntimeException(t);
        }
    }

    /**
     * 异步调用bind方法，该方法最终启动server监听线程，监听客户端的连接，包括telnet和websocket两种通信方式
     * 
     * 1. 使用ArthasClassloader加载com.taobao.arthas.core.config.Configure类(位于arthas-core.jar)，
     *    并将传递过来的序列化之后的config，反序列化成对应的Configure对象。
     * 2. 使用ArthasClassloader加载com.taobao.arthas.core.server.ArthasBootstrap类（位于arthas-core.jar），并调用bind方法。
     * @param inst Instrumentation
     * @param agentLoader ArthasClassloader
     * @param args arthas 参数
     * @throws Throwable
     */
    private static void bind(Instrumentation inst, ClassLoader agentLoader, String args) throws Throwable {
        /**
         * <pre>
         * Configure configure = Configure.toConfigure(args);
         * int javaPid = configure.getJavaPid();
         * ArthasBootstrap bootstrap = ArthasBootstrap.getInstance(javaPid, inst);
         * </pre>
         */
        // ArthasClassloader 加载 com.taobao.arthas.core.config.Configure
        Class<?> classOfConfigure = agentLoader.loadClass(ARTHAS_CONFIGURE);
        // 调用 Configure.toConfigure(args) ，反序列化字符串成 Configure 对象，设置 arthas 参数
        Object configure = classOfConfigure.getMethod(TO_CONFIGURE, String.class).invoke(null, args);
        // 调用 getJavaPid() 取得 javaPid
        int javaPid = (Integer) classOfConfigure.getMethod(GET_JAVA_PID).invoke(configure);
        //  ArthasClassloader 加载arthas启动类 com.taobao.arthas.core.server.ArthasBootstrap
        Class<?> bootstrapClass = agentLoader.loadClass(ARTHAS_BOOTSTRAP);
        // 调用 ArthasBootstrap 单例 getInstance() 方法取得 arthasBootstrap 对象
        Object bootstrap = bootstrapClass.getMethod(GET_INSTANCE, int.class, Instrumentation.class).invoke(null, javaPid, inst);
        // 调用 arthasBootstrap 对象的 isBind() 方法，返回判断服务端是否已经启动结果
        boolean isBind = (Boolean) bootstrapClass.getMethod(IS_BIND).invoke(bootstrap);
        //判断服务端是否已经启动，true:服务端已经启动;false:服务端关闭
        if (!isBind) {
            try {
                // 如果没有启动，则调用 arthasBootstrap 对象的 bind() 方法启动 arthas 服务
                // 传入参数为 Configure 对象
                ps.println("Arthas start to bind...");
                bootstrapClass.getMethod(BIND, classOfConfigure).invoke(bootstrap, configure);
                ps.println("Arthas server bind success.");
                return;
            } catch (Exception e) {
                ps.println("Arthas server port binding failed! Please check $HOME/logs/arthas/arthas.log for more details.");
                throw e;
            }
        }
        ps.println("Arthas server already bind.");
    }
}
