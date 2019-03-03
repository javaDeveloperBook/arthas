package com.taobao.arthas.core;

import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;
import com.taobao.arthas.common.AnsiLog;
import com.taobao.arthas.common.JavaVersionUtils;
import com.taobao.arthas.core.config.Configure;
import com.taobao.middleware.cli.CLI;
import com.taobao.middleware.cli.CLIs;
import com.taobao.middleware.cli.CommandLine;
import com.taobao.middleware.cli.Option;
import com.taobao.middleware.cli.TypedOption;

import java.util.Arrays;
import java.util.Properties;

/**
 * Arthas启动器
 */
public class Arthas {

    private static final String DEFAULT_TELNET_PORT = "3658";
    private static final String DEFAULT_HTTP_PORT = "8563";

    private Arthas(String[] args) throws Exception {
        //1.先是解析入参配置参数取得 Configure，2.再进行
        attachAgent(parse(args));
    }

    /**
     * 解析启动参数作为配置，并填充到configure对象里面
     * @param args
     * @return
     */
    private Configure parse(String[] args) {
        // 要注入的进程id
        Option pid = new TypedOption<Integer>().setType(Integer.class).setShortName("pid").setRequired(true);
        // arthas-core jar 包路径
        Option core = new TypedOption<String>().setType(String.class).setShortName("core").setRequired(true);
        // arthas-agent jar 包路径
        Option agent = new TypedOption<String>().setType(String.class).setShortName("agent").setRequired(true);
        // 服务器ip地址,默认为 127.0.0.1
        Option target = new TypedOption<String>().setType(String.class).setShortName("target-ip");
        // telnet 连接方式的端口 ，默认是 3658
        Option telnetPort = new TypedOption<Integer>().setType(Integer.class)
                .setShortName("telnet-port").setDefaultValue(DEFAULT_TELNET_PORT);
        // http 连接方式的端口，默认是 8563
        Option httpPort = new TypedOption<Integer>().setType(Integer.class)
                .setShortName("http-port").setDefaultValue(DEFAULT_HTTP_PORT);
        // session 过期时间，默认 30 minutes
        Option sessionTimeout = new TypedOption<Integer>().setType(Integer.class)
                        .setShortName("session-timeout").setDefaultValue("" + Configure.DEFAULT_SESSION_TIMEOUT_SECONDS);
        // 创建 command-line interface，命令行界面，实现类是 DefaultCLI
        CLI cli = CLIs.create("arthas").addOption(pid).addOption(core).addOption(agent).addOption(target)
                .addOption(telnetPort).addOption(httpPort).addOption(sessionTimeout);
        // 命令行
        CommandLine commandLine = cli.parse(Arrays.asList(args));

        // 创建 arthas 配置类
        Configure configure = new Configure();
        // 设置 JavaPid
        configure.setJavaPid((Integer) commandLine.getOptionValue("pid"));
        // 设置arthas-agent jar 包路径
        configure.setArthasAgent((String) commandLine.getOptionValue("agent"));
        // 设置arthas-core jar 包路径
        configure.setArthasCore((String) commandLine.getOptionValue("core"));
        // 设置session 过期时间
        configure.setSessionTimeout((Integer)commandLine.getOptionValue("session-timeout"));
        // 判断服务器ip地址为空则抛出异常
        if (commandLine.getOptionValue("target-ip") == null) {
            throw new IllegalStateException("as.sh is too old to support web console, " +
                    "please run the following command to upgrade to latest version:" +
                    "\ncurl -sLk https://alibaba.github.io/arthas/install.sh | sh");
        }
        // 设置服务器ip地址
        configure.setIp((String) commandLine.getOptionValue("target-ip"));
        // 设置telnet 连接方式的端口
        configure.setTelnetPort((Integer) commandLine.getOptionValue("telnet-port"));
        // 设置http 连接方式的端口
        configure.setHttpPort((Integer) commandLine.getOptionValue("http-port"));
        return configure;
    }

    /**
     * attach到目标进程
     * @param configure
     * @throws Exception
     */
    private void attachAgent(Configure configure) throws Exception {
        // 描述虚拟机的容器类,配合 VirtualMachine 类完成各种功能
        VirtualMachineDescriptor virtualMachineDescriptor = null;
        // 获取一个VirtualMachineDescriptor 列表
        for (VirtualMachineDescriptor descriptor : VirtualMachine.list()) {
            String pid = descriptor.id();
            // 与入参的 JavaPid 比较，如果相等即表示要 attach 的 VirtualMachine 的容器是当前容器
            // 这步操作主要是因为: VirtualMachine.attach(virtualMachineDescriptor)
            if (pid.equals(Integer.toString(configure.getJavaPid()))) {
                virtualMachineDescriptor = descriptor;
            }
        }
        // Java 虚拟机
        VirtualMachine virtualMachine = null;
        try {
            // 判断 virtualMachineDescriptor 是否为空，使用不同的方法获取 virtualMachine
            if (null == virtualMachineDescriptor) { // 使用 attach(String pid) 这种方式
                virtualMachine = VirtualMachine.attach("" + configure.getJavaPid());
            } else {
                virtualMachine = VirtualMachine.attach(virtualMachineDescriptor);
            }

            // 目标虚拟机中的当前系统属性
            Properties targetSystemProperties = virtualMachine.getSystemProperties();
            // 目标Java虚拟机版本
            String targetJavaVersion = JavaVersionUtils.javaVersionStr(targetSystemProperties);
            // 当前Java虚拟机版本
            String currentJavaVersion = JavaVersionUtils.javaVersionStr();
            // 判断如果目标Java虚拟机版本和当前Java虚拟机版本都不为null
            if (targetJavaVersion != null && currentJavaVersion != null) {
                // 如果两个版本不相等则打印异常信息
                if (!targetJavaVersion.equals(currentJavaVersion)) {
                    AnsiLog.warn("Current VM java version: {} do not match target VM java version: {}, attach may fail.",
                                    currentJavaVersion, targetJavaVersion);
                    AnsiLog.warn("Target VM JAVA_HOME is {}, arthas-boot JAVA_HOME is {}, try to set the same JAVA_HOME.",
                                    targetSystemProperties.getProperty("java.home"), System.getProperty("java.home"));
                }
            }
            // 参考文章https://blog.csdn.net/youyou1543724847/article/details/84952218
            /**
             * 加载代理(加载arthas-agent.jar包，并运行)，HotSpotVirtualMachine 具体实现
             * 参数：
             *  1.arthas-agent.jar 包路径,eg.D:\\Program\\arthas\\arthas-agent.jar
             *  2.arthas-core.jar 包路径和 configure 序列化之后的字符串 ,
             *      D:\\Program\\arthas\\arthas-core.jar;;telnetPort=3658;httpPort=8563;ip=127.0.0.1;arthasAgent=D:\\Program\\arthas\\arthas-agent.jar;sessionTimeout=1800;arthasCore=D:\\Program\\arthas\\arthas-core.jar;javaPid=21972;
             */
            virtualMachine.loadAgent(configure.getArthasAgent(),
                            configure.getArthasCore() + ";" + configure.toString());
        } finally {
            if (null != virtualMachine) {
                // 如果虚拟机不为null，则从虚拟机中脱离
                virtualMachine.detach();
            }
        }
    }


    public static void main(String[] args) {
        try {
            /**
             * ${JAVA_HOME}"/bin/java \
             *      ${opts}  \
             *      -jar "${arthas_lib_dir}/arthas-core.jar" \
             *          -pid ${TARGET_PID} \             要注入的进程id
             *          -target-ip ${TARGET_IP} \       服务器ip地址
             *          -telnet-port ${TELNET_PORT} \  服务器telnet服务端口号
             *          -http-port ${HTTP_PORT} \      websocket服务端口号
             *          -core "${arthas_lib_dir}/arthas-core.jar" \      arthas-core目录
             *          -agent "${arthas_lib_dir}/arthas-agent.jar"    arthas-agent目录
             */
            //启动类，注意 args 入参
            new Arthas(args);
        } catch (Throwable t) {
            AnsiLog.error("Start arthas failed, exception stack trace: ");
            t.printStackTrace();
            System.exit(-1);
        }
    }
}
