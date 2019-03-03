package com.taobao.arthas.core.server;

import com.taobao.arthas.core.config.Configure;
import com.taobao.arthas.core.command.BuiltinCommandPack;
import com.taobao.arthas.core.shell.ShellServer;
import com.taobao.arthas.core.shell.ShellServerOptions;
import com.taobao.arthas.core.shell.command.CommandResolver;
import com.taobao.arthas.core.shell.handlers.BindHandler;
import com.taobao.arthas.core.shell.impl.ShellServerImpl;
import com.taobao.arthas.core.shell.term.impl.HttpTermServer;
import com.taobao.arthas.core.shell.term.impl.TelnetTermServer;
import com.taobao.arthas.core.util.Constants;
import com.taobao.arthas.core.util.LogUtil;
import com.taobao.arthas.core.util.UserStatUtil;
import com.taobao.middleware.logger.Logger;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;


/**
 * @author vlinux on 15/5/2.
 */
public class ArthasBootstrap {

    private static Logger logger = LogUtil.getArthasLogger();
    private static ArthasBootstrap arthasBootstrap;

    // 线程安全,利用CAS(Compare And Swap)来实现线程安全的
    private AtomicBoolean isBindRef = new AtomicBoolean(false);
    private int pid;
    private Instrumentation instrumentation;
    private Thread shutdown;
    private ShellServer shellServer;
    private ExecutorService executorService;

    private ArthasBootstrap(int pid, Instrumentation instrumentation) {
        this.pid = pid;
        this.instrumentation = instrumentation;

        executorService = Executors.newCachedThreadPool(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                final Thread t = new Thread(r, "as-command-execute-daemon");
                t.setDaemon(true);
                return t;
            }
        });

        shutdown = new Thread("as-shutdown-hooker") {

            @Override
            public void run() {
                ArthasBootstrap.this.destroy();
            }
        };

        Runtime.getRuntime().addShutdownHook(shutdown);
    }

    /**
     * Bootstrap arthas server
     *
     * @param configure 配置信息
     * @throws IOException 服务器启动失败
     */
    public void bind(Configure configure) throws Throwable {

        long start = System.currentTimeMillis();

        // 保证启动 arthas server 执行一次
        if (!isBindRef.compareAndSet(false, true)) {
            throw new IllegalStateException("already bind");
        }

        try {
            // 创建 Shell 服务选项对象
            ShellServerOptions options = new ShellServerOptions()
                            .setInstrumentation(instrumentation)
                            .setPid(pid)
                            .setSessionTimeout(configure.getSessionTimeout() * 1000);
            // 创建 shellServer 对象，ShellServer 是整个服务端的门面类，由ShellServerImpl 具体实现
            shellServer = new ShellServerImpl(options, this);
            // 创建内置命令包对象， 重点看一下（BuiltinCommandPack implements CommandResolver）
            BuiltinCommandPack builtinCommands = new BuiltinCommandPack();
            // 加入命令解析器
            List<CommandResolver> resolvers = new ArrayList<CommandResolver>();
            resolvers.add(builtinCommands);
            // TODO: discover user provided command resolver 发现用户提供的命令解析器
            //如果 Telnet 端口大于0，也就是 as.sh 启动命令设置有 telnet-port 参数
            if (configure.getTelnetPort() > 0) {
                //注册 TelnetTermServer 通讯方式
                shellServer.registerTermServer(new TelnetTermServer(configure.getIp(), configure.getTelnetPort(),
                                options.getConnectionTimeout()));
            } else {
                logger.info("telnet port is {}, skip bind telnet server.", configure.getTelnetPort());
            }
            //如果 Http 端口大于0，也就是 as.sh 启动命令设置有 http-port 参数
            if (configure.getHttpPort() > 0) {
                //注册 HttpTermServer 通讯方式
                shellServer.registerTermServer(new HttpTermServer(configure.getIp(), configure.getHttpPort(),
                                options.getConnectionTimeout()));
            } else {
                logger.info("http port is {}, skip bind http server.", configure.getHttpPort());
            }

            // 遍历所有命令解析器
            for (CommandResolver resolver : resolvers) {
                // 向 shellServer 设置命令解析器，这里是 BuiltinCommandPack 对象
                shellServer.registerCommandResolver(resolver);
            }

            /**
             * 监听启动方法，监听客户端传来的命令
             *
             * 会调用所有注册的TermServer的listen方法，
             * 比如 TelnetTermServer。然后 TelnetTermServer 的 listen 方法会注册一个回调类，
             * 该回调类在有新的客户端连接时会调用 TermServerTermHandler 的 handle 方法处理
             */
            shellServer.listen(new BindHandler(isBindRef));

            logger.info("as-server listening on network={};telnet={};http={};timeout={};", configure.getIp(),
                    configure.getTelnetPort(), configure.getHttpPort(), options.getConnectionTimeout());
            // 异步回报启动次数
            UserStatUtil.arthasStart();

            logger.info("as-server started in {} ms", System.currentTimeMillis() - start );
        } catch (Throwable e) {
            logger.error(null, "Error during bind to port " + configure.getTelnetPort(), e);
            if (shellServer != null) {
                // 如果异常则关闭 shellServer
                shellServer.close();
            }
            throw e;
        }
    }

    /**
     * 判断服务端是否已经启动
     *
     * @return true:服务端已经启动;false:服务端关闭
     */
    public boolean isBind() {
        return isBindRef.get();
    }

    public void destroy() {
        executorService.shutdownNow();
        UserStatUtil.destroy();
        // clear the reference in Spy class.
        cleanUpSpyReference();
        try {
            Runtime.getRuntime().removeShutdownHook(shutdown);
        } catch (Throwable t) {
            // ignore
        }
        logger.info("as-server destroy completed.");
        // see https://github.com/alibaba/arthas/issues/319
        LogUtil.closeResultLogger();
    }

    /**
     * 单例
     *
     * @param instrumentation JVM增强
     * @return ArthasServer单例
     */
    public synchronized static ArthasBootstrap getInstance(int javaPid, Instrumentation instrumentation) {
        if (arthasBootstrap == null) {
            arthasBootstrap = new ArthasBootstrap(javaPid, instrumentation);
        }
        return arthasBootstrap;
    }
    /**
     * @return ArthasServer单例
     */
    public static ArthasBootstrap getInstance() {
        if (arthasBootstrap == null) {
            throw new IllegalStateException("ArthasBootstrap must be initialized before!");
        }
        return arthasBootstrap;
    }

    /**
     * 执行命令任务
     * @param command
     */
    public void execute(Runnable command) {
        // 线程池执行任务
        executorService.execute(command);
    }

    /**
     * 清除spy中对classloader的引用，避免内存泄露
     */
    private void cleanUpSpyReference() {
        try {
            // 从ArthasClassLoader中加载Spy
            Class<?> spyClass = this.getClass().getClassLoader().loadClass(Constants.SPY_CLASSNAME);
            Method agentDestroyMethod = spyClass.getMethod("destroy");
            agentDestroyMethod.invoke(null);
        } catch (ClassNotFoundException e) {
            logger.error(null, "Spy load failed from ArthasClassLoader, which should not happen", e);
        } catch (Exception e) {
            logger.error(null, "Spy destroy failed: ", e);
        }
    }
}
