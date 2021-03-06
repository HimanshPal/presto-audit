/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package jp.co.yahoo.presto.audit;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.cache.RemovalListener;
import io.airlift.log.Logger;

import java.io.FileWriter;
import java.io.IOException;
import java.util.AbstractMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

public class AuditLogFileWriter
        implements Runnable
{
    private static final int QUEUE_CAPACITY = 10000;
    private static final int FILE_TIMEOUT_SEC = 3;

    private static final Logger log = Logger.get(AuditLogFileWriter.class);
    private static AuditLogFileWriter singleton;
    private final Thread t;

    private volatile boolean isTerminate = false;
    private final BlockingQueue<Map.Entry<String, String>> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    LoadingCache<String, FileWriter> fileWriters;

    private AuditLogFileWriter(WriterFactory writerFactory)
    {
        t = new Thread(this, "AuditLogWriter Thread");

        // Close file handler when cache timeout
        RemovalListener<String, FileWriter> removalListener = removal -> {
            FileWriter h = removal.getValue();
            try {
                log.info("Close FileWriter: " + removal.getKey());
                h.close();
            }
            catch (Exception e) {
                log.error("Failed to close file: " + removal.getKey());
            }
        };

        // Open file handler when cache is needed
        fileWriters = CacheBuilder.newBuilder()
                .expireAfterWrite(FILE_TIMEOUT_SEC, TimeUnit.SECONDS)
                .removalListener(removalListener)
                .build(new CacheLoader<String, FileWriter>()
                        {
                            public FileWriter load(String filename) throws IOException
                            {
                                try {
                                    log.info("Open new FileWriter: " + filename);
                                    FileWriter fileWriter = writerFactory.getFileWriter(filename);
                                    return fileWriter;
                                }
                                catch (Exception e) {
                                    log.error("Failed to open file: " + e.getMessage());
                                    throw e;
                                }
                            }
                        });
    }

    /**
     * Return the singleton instance for this class
     *
     * @return singleton instance
     */
    public static synchronized AuditLogFileWriter getInstance()
    {
        if (singleton == null) {
            singleton = new AuditLogFileWriter(new WriterFactory());
            singleton.start();
        }
        return singleton;
    }

    /**
     * Start the thread for file writing
     */
    public void start()
    {
        isTerminate = false;
        t.start();
    }

    /**
     * Terminate the thread for file writing
     */
    public void stop()
    {
        isTerminate = true;
    }

    /**
     * Write data to a particular file indicated by path
     */
    public void write(String path, String data)
    {
        try {
            queue.add(new AbstractMap.SimpleEntry<>(path, data));
        }
        catch (IllegalStateException e) {
            log.error("Error adding error log to queue. Queue full while capacity is " + QUEUE_CAPACITY + ". Error: " + e.getMessage());
        }
        catch (Exception e) {
            log.error("Unknown error adding error log to queue. ErrorMessage: " + e.getMessage());
        }
    }

    @Override
    public void run()
    {
        while (!isTerminate) {
            try {
                // + 1 second before cleanUP to ensure files are marked timeout
                Map.Entry<String, String> record = queue.poll(FILE_TIMEOUT_SEC + 1, TimeUnit.SECONDS);
                if (record == null) {
                    // Timeout from poll() -> release file handlers
                    fileWriters.cleanUp();
                }
                else {
                    // New record for writing
                    FileWriter fileWriter = fileWriters.get(record.getKey());
                    fileWriter.write(record.getValue());
                    fileWriter.write(System.lineSeparator());
                }
            }
            catch (Exception e) {
                log.error("Error writing event log to file in run(). ErrorMessage: " + e.getMessage());
            }
        }
    }

    static class WriterFactory
    {
        public FileWriter getFileWriter(String filename) throws IOException
        {
            return new FileWriter(filename, true);
        }
    }
}
