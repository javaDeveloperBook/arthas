package com.taobao.arthas.core.shell.system.impl;

import com.taobao.arthas.core.GlobalOptions;
import com.taobao.arthas.core.shell.cli.CliToken;
import com.taobao.arthas.core.shell.command.Command;
import com.taobao.arthas.core.shell.command.internal.RedirectHandler;
import com.taobao.arthas.core.shell.command.internal.StdoutHandler;
import com.taobao.arthas.core.shell.command.internal.TermHandler;
import com.taobao.arthas.core.shell.future.Future;
import com.taobao.arthas.core.shell.handlers.Handler;
import com.taobao.arthas.core.shell.impl.ShellImpl;
import com.taobao.arthas.core.shell.system.Job;
import com.taobao.arthas.core.shell.system.JobController;
import com.taobao.arthas.core.shell.system.Process;
import com.taobao.arthas.core.shell.system.impl.ProcessImpl.ProcessOutput;
import com.taobao.arthas.core.shell.term.Term;
import com.taobao.arthas.core.util.Constants;
import com.taobao.arthas.core.util.TokenUtils;

import io.termd.core.function.Function;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author <a href="mailto:julien@julienviet.com">Julien Viet</a>
 */
public class JobControllerImpl implements JobController {

    private final SortedMap<Integer, JobImpl> jobs = new TreeMap<Integer, JobImpl>();
    private final AtomicInteger idGenerator = new AtomicInteger(0);
    private boolean closed = false;

    public JobControllerImpl() {
    }

    public synchronized Set<Job> jobs() {
        return new HashSet<Job>(jobs.values());
    }

    public synchronized Job getJob(int id) {
        return jobs.get(id);
    }

    synchronized boolean removeJob(int id) {
        return jobs.remove(id) != null;
    }

    /**
     * 1.创建 Job 时，会根据具体客户端传递的命令，找到对应的 Command，并包装成 Process, Process 再被包装成 Job。
     * 2.运行 Job 时，反向先调用 Process，再找到对应的 Command，最终调用 Command 的 process 处理请求。
     * @param commandManager command manager
     * @param tokens    the command tokens
     * @param shell     the current shell
     * @return
     */
    @Override
    public Job createJob(InternalCommandManager commandManager, List<CliToken> tokens, ShellImpl shell) {
        // jobId
        int jobId = idGenerator.incrementAndGet();
        StringBuilder line = new StringBuilder();
        for (CliToken arg : tokens) {
            line.append(arg.raw());
        }
        // 是否后台运行
        boolean runInBackground = runInBackground(tokens);
        //创建由shell管理的进程
        Process process = createProcess(tokens, commandManager, jobId, shell.term());
        process.setJobId(jobId);
        // 创建 job 对象，重要的 process 参数
        JobImpl job = new JobImpl(jobId, this, process, line.toString(), runInBackground, shell);
        jobs.put(jobId, job);
        return job;
    }

    private int getRedirectJobCount() {
        int count = 0;
        for (Job job : jobs.values()) {
            if (job.process() != null && job.process().cacheLocation() != null) {
                count++;
            }
        }
        return count;
    }

    @Override
    public void close(final Handler<Void> completionHandler) {
        List<JobImpl> jobs;
        synchronized (this) {
            if (closed) {
                jobs = Collections.emptyList();
            } else {
                jobs = new ArrayList<JobImpl>(this.jobs.values());
                closed = true;
            }
        }
        if (jobs.isEmpty()) {
            if (completionHandler!= null) {
                completionHandler.handle(null);
            }
        } else {
            final AtomicInteger count = new AtomicInteger(jobs.size());
            for (JobImpl job : jobs) {
                job.terminateFuture.setHandler(new Handler<Future<Void>>() {
                    @Override
                    public void handle(Future<Void> v) {
                        if (count.decrementAndGet() == 0 && completionHandler != null) {
                            completionHandler.handle(null);
                        }
                    }
                });
                job.terminate();
            }
        }
    }

    /**
     * Try to create a process from the command line tokens.
     * 尝试从命令行标记创建进程。
     * @param line the command line tokens
     * @param commandManager command manager
     * @param jobId job id
     * @param term term
     * @return the created process
     */
    private Process createProcess(List<CliToken> line, InternalCommandManager commandManager, int jobId, Term term) {
        try {
            ListIterator<CliToken> tokens = line.listIterator();
            while (tokens.hasNext()) {
                CliToken token = tokens.next();
                if (token.isText()) {
                    // 取得 Command 对象
                    Command command = commandManager.getCommand(token.value());
                    if (command != null) {
                        // 取得 Process 对象
                        return createCommandProcess(command, tokens, jobId, term);
                    } else {
                        throw new IllegalArgumentException(token.value() + ": command not found");
                    }
                }
            }
            throw new IllegalArgumentException();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private boolean runInBackground(List<CliToken> tokens) {
        boolean runInBackground = false;
        CliToken last = TokenUtils.findLastTextToken(tokens);
        if (last != null && "&".equals(last.value())) {
            runInBackground = true;
            tokens.remove(last);
        }
        return runInBackground;
    }

    /**
     * 创建 Process，重要参数是 command
     * @param command
     * @param tokens
     * @param jobId
     * @param term
     * @return
     * @throws IOException
     */
    private Process createCommandProcess(Command command, ListIterator<CliToken> tokens, int jobId, Term term) throws IOException {
        List<CliToken> remaining = new ArrayList<CliToken>();
        List<CliToken> pipelineTokens = new ArrayList<CliToken>();
        // 是否有管道
        boolean isPipeline = false;
        // 重定向处理程序
        RedirectHandler redirectHandler = null;
        List<Function<String, String>> stdoutHandlerChain = new ArrayList<Function<String, String>>();
        String cacheLocation = null;
        while (tokens.hasNext()) {
            CliToken remainingToken = tokens.next();
            if (remainingToken.isText()) {
                String tokenValue = remainingToken.value();
                // 判断是管道命令
                if ("|".equals(tokenValue)) {
                    isPipeline = true;
                    // 将管道符|之后的部分注入为输出链上的handler
                    injectHandler(stdoutHandlerChain, pipelineTokens);
                    continue;
                } else if (">>".equals(tokenValue) || ">".equals(tokenValue)) {
                    // 重定向命令文件名
                    String name = getRedirectFileName(tokens);
                    if (name == null) {
                        // 如果没有指定重定向文件名，那么重定向到以jobid命名的缓存中
                        name = Constants.PID + File.separator + jobId;
                        cacheLocation = Constants.CACHE_ROOT + File.separator + name;

                        if (getRedirectJobCount() == 8) {
                            throw new IllegalStateException("The amount of async command that saving result to file can't > 8");
                        }
                    }
                    // 重定向处理handler
                    redirectHandler = new RedirectHandler(name, ">>".equals(tokenValue));
                    break;
                }
            }
            if (isPipeline) {
                pipelineTokens.add(remainingToken);
            } else {
                remaining.add(remainingToken);
            }
        }
        injectHandler(stdoutHandlerChain, pipelineTokens);
        if (redirectHandler != null) {
            stdoutHandlerChain.add(redirectHandler);
        } else {
            stdoutHandlerChain.add(new TermHandler(term));
            if (GlobalOptions.isSaveResult) {
                stdoutHandlerChain.add(new RedirectHandler());
            }
        }
        // 处理命令结果输出
        ProcessOutput ProcessOutput = new ProcessOutput(stdoutHandlerChain, cacheLocation, term);
        // 创建 process，注意 command.processHandler() ：command 对应的 processHandler 赋值给ProcessImpl属性,后面可以找到对应的处理命令程序
        return new ProcessImpl(command, remaining, command.processHandler(), ProcessOutput);
    }

    private String getRedirectFileName(ListIterator<CliToken> tokens) {
        while (tokens.hasNext()) {
            CliToken token = tokens.next();
            if (token.isText()) {
                return token.value();
            }
        }
        return null;
    }

    private void injectHandler(List<Function<String, String>> stdoutHandlerChain, List<CliToken> pipelineTokens) {
        if (!pipelineTokens.isEmpty()) {
            StdoutHandler handler = StdoutHandler.inject(pipelineTokens);
            if (handler != null) {
                stdoutHandlerChain.add(handler);
            }
            pipelineTokens.clear();
        }
    }

    @Override
    public void close() {
        close(null);
    }
}
