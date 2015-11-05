/*
 * The contents of this file are subject to the terms of the Common Development and
 * Distribution License (the License). You may not use this file except in compliance with the
 * License.
 *
 * You can obtain a copy of the License at legal/CDDLv1.0.txt. See the License for the
 * specific language governing permission and limitations under the License.
 *
 * When distributing Covered Software, include this CDDL Header Notice in each file and include
 * the License file at legal/CDDLv1.0.txt. If applicable, add the following below the CDDL
 * Header, with the fields enclosed by brackets [] replaced by your own identifying
 * information: "Portions copyright [year] [name of copyright owner]".
 *
 * Copyright 2015 ForgeRock AS.
 */
package org.forgerock.audit.handlers.jdbc.publishers;

import static java.util.concurrent.Executors.newFixedThreadPool;
import static org.forgerock.util.Reject.checkNotNull;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.forgerock.audit.AuditException;
import org.forgerock.audit.handlers.jdbc.JDBCAuditEvent;
import org.forgerock.audit.handlers.jdbc.Parameter;
import org.forgerock.audit.handlers.jdbc.utils.CleanupHelper;
import org.forgerock.util.Reject;
import org.forgerock.util.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Buffers the create events to a {@link JDBCAuditEventExecutor}.
 */
public class BufferedJDBCAuditEventExecutor implements JDBCAuditEventExecutor {
    private static final Logger logger = LoggerFactory.getLogger(BufferedJDBCAuditEventExecutor.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    /** The wrapped {@link JDBCAuditEventExecutor}. */
    private final JDBCAuditEventExecutor delegate;

    /** Queue to store unpublished events. */
    private final LinkedBlockingQueue<JDBCAuditEvent> queue;

    private volatile boolean stopRequested;
    private final ScheduledExecutorService queueWatcher;
    private final ExecutorService workerPool;
    private final boolean autoFlush;
    private final int maxBatchedEvents;
    private final DataSource dataSource;

    /**
     * Created a BufferedJDBCAuditEventExecutor with a given queue capacity, and the {@link JDBCAuditEventExecutor}
     * to use.
     * @param capacity The capacity of the buffered queue.
     * @param autoFlush Whether the queue needs to be auto flushed or not.
     * @param delegate The {@link JDBCAuditEventExecutor} to delegate the operations too.
     * @param writeInterval The interval to trigger write events.
     * @param threads The number of writer threads.
     */
    public BufferedJDBCAuditEventExecutor(int capacity, boolean autoFlush, JDBCAuditEventExecutor delegate,
            Duration writeInterval, int threads, int maxBatchedEvents, final DataSource dataSource) {
        Reject.ifNull(delegate);
        this.autoFlush = autoFlush;
        this.delegate = delegate;

        this.queue = new LinkedBlockingQueue<>(capacity);
        this.stopRequested = false;

        this.dataSource = dataSource;
        this.queueWatcher = Executors.newScheduledThreadPool(1);
        this.workerPool = newFixedThreadPool(threads);
        this.queueWatcher.scheduleAtFixedRate(
                new QueueWatcherThread(workerPool), 0, writeInterval.to(TimeUnit.MILLISECONDS), TimeUnit.MILLISECONDS);
        this.maxBatchedEvents = maxBatchedEvents;
    }

    public void flush() {
        try {
            while (!queue.isEmpty()) {
                Collection<JDBCAuditEvent> events = new ArrayList<>(maxBatchedEvents);
                queue.drainTo(events, maxBatchedEvents);
                try {
                    workerPool.submit(new DatabaseWriterTask(events, dataSource));
                } catch (RejectedExecutionException e) {
                    // unable to submit task put events back in queue.
                    queue.addAll(events);
                }
            }
        } catch (Exception e) {
            logger.error("Unable to create remaining entries in the queue.", e);
        }
    }

    /**
     * Stops the publisher thread and writes the remaining buffered events.
     * {@inheritDoc}
     */
    @Override
    public void close() {
        stopRequested = true;
        if (autoFlush) {
            flush();
        }
        shutdownPool(queueWatcher);
        shutdownPool(workerPool);
        delegate.close();
    }

    @Override
    public void createAuditEvent(JDBCAuditEvent event) throws AuditException {
        while (!stopRequested) {
            // Put request on queue for writer
            if (queue.offer(event)) {
                break;
            }
        }
    }

    @Override
    public List<Map<String, Object>> readAuditEvent(JDBCAuditEvent event) throws AuditException {
        return delegate.readAuditEvent(event);
    }

    @Override
    public List<Map<String, Object>> queryAuditEvent(JDBCAuditEvent event) throws AuditException {
        return delegate.queryAuditEvent(event);
    }

    private class QueueWatcherThread implements Runnable {

        private final ExecutorService workerPool;

        QueueWatcherThread(ExecutorService workerPool) {
            this.workerPool = workerPool;
        }

        @Override
        public void run() {
            while (!stopRequested && !queue.isEmpty()) {
                Collection<JDBCAuditEvent> events = new ArrayList<>(maxBatchedEvents);
                queue.drainTo(events, maxBatchedEvents);

                // Handle the case where the task cannot be submitted.
                try {
                    workerPool.submit(new DatabaseWriterTask(events, dataSource));
                } catch (RejectedExecutionException e) {
                    // unable to submit task put events back in queue.
                    queue.addAll(events);
                }
            }
        }
    }

    private class DatabaseWriterTask implements Runnable {

        final private Collection<JDBCAuditEvent> events;
        final private DataSource dataSource;

        public DatabaseWriterTask(final Collection<JDBCAuditEvent> events, final DataSource dataSource) {
            this.events = checkNotNull(events);
            this.dataSource = dataSource;
        }

        @Override
        public void run() {
            if (events.isEmpty()) {
                return;
            }

            Connection connection = null;
            try {
                connection = dataSource.getConnection();
                connection.setAutoCommit(false);

                // Use a PreparedStatement batch to insert the events into the DB
                try  (final PreparedStatement preparedStatement = connection.prepareStatement(events.iterator().next().getSql())) {
                    for (JDBCAuditEvent event : events) {
                        preparedStatement.clearParameters();
                        try {
                            initializePreparedStatement(preparedStatement, event.getParams());
                            preparedStatement.addBatch();
                        } catch (Exception e) {
                            logger.error("Unable to create event in the queue", e);
                        }
                    }
                    preparedStatement.executeBatch();
                }
                CleanupHelper.commit(connection);
            } catch (SQLException e) {
                logger.error("Unable to create events in the queue.", e);
                CleanupHelper.rollback(connection);
            } finally {
                CleanupHelper.close(connection);
            }
        }

        private void initializePreparedStatement(final PreparedStatement preparedStatement, final List<Parameter> params)
                throws AuditException, SQLException, JsonProcessingException {
            int i = 1;
            for (final Parameter parameter : params) {
                switch (parameter.getParameterType()) {
                    case STRING:
                        preparedStatement.setString(i, (String) parameter.getParameter());
                        break;
                    case NUMBER:
                        preparedStatement.setInt(i, (Integer) parameter.getParameter());
                        break;
                    case BOOLEAN:
                        preparedStatement.setBoolean(i, (Boolean) parameter.getParameter());
                        break;
                    case OBJECT:
                    case ARRAY:
                        preparedStatement.setString(i, mapper.writeValueAsString(parameter.getParameter()));
                        break;
                    default:
                        logger.error("Unknown class type");
                        throw new AuditException("Unknown class type");
                }
                i++;
            }
        }

    }

    private void shutdownPool(final ExecutorService executorService) {
        try {
            executorService.shutdown();
            while (!executorService.awaitTermination(500, TimeUnit.MILLISECONDS)) {
                logger.debug("Waiting to terminate the executor service.");
            }
        } catch (InterruptedException ex) {
            logger.error("Unable to terminate the executor service", ex);
            Thread.currentThread().interrupt();
        }
    }
}
