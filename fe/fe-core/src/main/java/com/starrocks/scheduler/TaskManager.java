// This file is licensed under the Elastic License 2.0. Copyright 2021-present, StarRocks Limited.

package com.starrocks.scheduler;


import com.clearspring.analytics.util.Lists;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import com.starrocks.catalog.Column;
import com.starrocks.catalog.ScalarType;
import com.starrocks.common.DdlException;
import com.starrocks.common.util.QueryableReentrantLock;
import com.starrocks.common.util.Util;
import com.starrocks.qe.ConnectContext;
import com.starrocks.qe.ShowResultSet;
import com.starrocks.qe.ShowResultSetMetaData;
import com.starrocks.sql.ast.SubmitTaskStmt;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

public class TaskManager {

    private static final Logger LOG = LogManager.getLogger(TaskManager.class);

    public static final long TASK_EXISTS = -1L;
    public static final long DUPLICATE_CREATE_TASK = -2L;
    public static final long GET_TASK_LOCK_FAILED = -3L;

    // taskId -> Task , Task may have Manual Task, Periodical Task
    // every TaskRun must be generated by a Task
    private final Map<Long, Task> manualTaskMap;
    // taskName -> Task, include Manual Task, Periodical Task
    private final Map<String, Task> nameToTaskMap;

    // include PENDING/RUNNING taskRun;
    private final TaskRunManager taskRunManager;

    // The dispatchTaskScheduler is responsible for periodically checking whether the running TaskRun is completed
    // and updating the status. It is also responsible for placing pending TaskRun in the running TaskRun queue.
    // This operation need to consider concurrency.
    // This scheduler can use notify/wait to optimize later.
    private final ScheduledExecutorService dispatchScheduler = Executors.newScheduledThreadPool(1);

    // Use to concurrency control
    private final QueryableReentrantLock lock;

    private AtomicBoolean isStart = new AtomicBoolean(false);

    public TaskManager() {
        manualTaskMap = Maps.newConcurrentMap();
        nameToTaskMap = Maps.newConcurrentMap();
        taskRunManager = new TaskRunManager();
        lock = new QueryableReentrantLock(true);
    }

    public void start() {
        if (isStart.compareAndSet(false, true)) {
            dispatchScheduler.scheduleAtFixedRate(() -> {
                if (!tryLock()) {
                    return;
                }
                try {
                    taskRunManager.checkRunningTaskRun();
                    taskRunManager.scheduledPendingTaskRun();
                } catch (Exception ex) {
                    LOG.warn("failed to dispatch job.", ex);
                } finally {
                    unlock();
                }
            }, 0, 1, TimeUnit.SECONDS);
        }
    }

    public long createTask(Task task) {
        if (!tryLock()) {
            return GET_TASK_LOCK_FAILED;
        }
        try {
            if (nameToTaskMap.containsKey(task.getName())) {
                return TASK_EXISTS;
            }
            nameToTaskMap.put(task.getName(), task);
            if (manualTaskMap.containsKey(task.getId())) {
                return DUPLICATE_CREATE_TASK;
            }
            manualTaskMap.put(task.getId(), task);
            // GlobalStateMgr.getCurrentState().getEditLog().logCreateTask(task);
            return task.getId();
        } finally {
            unlock();
        }
    }

    public String executeTask(String taskName) {
        Task task = nameToTaskMap.get(taskName);
        if (task == null) {
            return null;
        }
        return taskRunManager.addTaskRun(TaskRunBuilder.newBuilder(task).build());
    }

    public void dropTask(String taskName) {
        Task task = nameToTaskMap.get(taskName);
        if (task == null) {
            return;
        }
        nameToTaskMap.remove(taskName);
        manualTaskMap.remove(task.getId());
        // GlobalStateMgr.getCurrentState().getEditLog().logDropTask(taskName);
    }

    public List<Task> showTasks(String dbName) {
        List<Task> taskList = Lists.newArrayList();
        if (dbName == null) {
            taskList.addAll(manualTaskMap.values());
        } else {
            taskList.addAll(manualTaskMap.values().stream()
                    .filter(u -> u.getDbName().equals(dbName)).collect(Collectors.toList()));
        }
        return taskList;
    }

    private boolean tryLock() {
        try {
            if (!lock.tryLock(1, TimeUnit.SECONDS)) {
                Thread owner = lock.getOwner();
                if (owner != null) {
                    LOG.warn("task lock is held by: {}", Util.dumpThread(owner, 50));
                } else {
                    LOG.warn("task lock owner is null");
                }
                return false;
            }
            return true;
        } catch (InterruptedException e) {
            LOG.warn("got exception while getting task lock", e);
        }
        return lock.isHeldByCurrentThread();
    }

    private void unlock() {
        this.lock.unlock();
    }

    public void replayCreateTask(Task task) {
        createTask(task);
    }

    public void replayDropTask(String taskName) {
        dropTask(taskName);
    }

    public TaskRunManager getTaskRunManager() {
        return taskRunManager;
    }

    public ShowResultSet handleSubmitTaskStmt(SubmitTaskStmt submitTaskStmt) throws DdlException {
        Task task = TaskBuilder.buildTask(submitTaskStmt, ConnectContext.get());
        Long createResult = this.createTask(task);

        String taskName = task.getName();
        if (createResult < 0) {
            if (createResult == TASK_EXISTS) {
                throw new DdlException("Task " +  taskName + " already exist.");
            }
            throw new DdlException("Failed to create Task: " +  taskName + ", ErrorCode: " + createResult);
        }
        String queryId = this.executeTask(taskName);

        ShowResultSetMetaData.Builder builder = ShowResultSetMetaData.builder();
        builder.addColumn(new Column("TaskName", ScalarType.createVarchar(40)));
        builder.addColumn(new Column("Status", ScalarType.createVarchar(10)));
        List<String> item = ImmutableList.of(taskName, "Submitted");
        List<List<String>> result = ImmutableList.of(item);
        return new ShowResultSet(builder.build(), result);
    }

}
