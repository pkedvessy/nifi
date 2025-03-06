/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.nifi.minifi.nar;

import static java.util.Objects.requireNonNull;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.concurrent.TimeUnit;
import org.apache.nifi.nar.ExtensionManager;
import org.apache.nifi.nar.NarLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NarAutoUnLoaderTask implements Runnable {

    private static final Logger LOGGER = LoggerFactory.getLogger(NarAutoUnLoaderTask.class);
    private static final long POLL_INTERVAL_MS = 5000;
    private static final String UNPACKED_POSTFIX = "-unpacked";

    private final Path autoLoadPath;
    private final File extensionWorkDirectory;
    private final WatchService watchService;
    private final ExtensionManager extensionManager;
    private final NarLoader narLoader;

    private volatile boolean stopped = false;

    public NarAutoUnLoaderTask(
            Path autoLoadPath,
            File extensionWorkDirectory,
            WatchService watchService,
            ExtensionManager extensionManager,
            NarLoader narLoader) {
        this.autoLoadPath = requireNonNull(autoLoadPath);
        this.extensionWorkDirectory = requireNonNull(extensionWorkDirectory);
        this.watchService = requireNonNull(watchService);
        this.extensionManager = requireNonNull(extensionManager);
        this.narLoader = requireNonNull(narLoader);
    }

    @Override
    public void run() {
        while (!stopped) {
            try {
                WatchKey key;
                try {
                    LOGGER.debug("Polling for removed NARs at {}", autoLoadPath);
                    key = watchService.poll(POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
                } catch (InterruptedException x) {
                    LOGGER.info("WatchService interrupted, returning...");
                    return;
                }
                if (key != null) {
                    for (WatchEvent<?> event : key.pollEvents()) {
                        if (event.kind() != StandardWatchEventKinds.OVERFLOW) {
                            unLoadNarFile(getFileName(event));
                        }
                    }
                    if (!key.reset()) {
                        LOGGER.error("NAR auto-load directory is no longer valid");
                        stop();
                    }
                }
            } catch (final Throwable t) {
                LOGGER.error("Error un-loading NARs", t);
            }
        }
    }

    public void stop() {
        LOGGER.info("Stopping NAR Auto Un-Loader");
        stopped = true;
    }

    private String getFileName(WatchEvent<?> event) {
        WatchEvent<Path> ev = (WatchEvent<Path>) event;
        Path filename = ev.context();
        Path autoUnLoadFile = autoLoadPath.resolve(filename);
        return autoUnLoadFile.toFile().getName().toLowerCase();
    }

    private boolean isSupported(String fileName) {
        if (!fileName.endsWith(".nar")) {
            LOGGER.info("Skipping non-nar file {}", fileName);
            return false;
        } else if (fileName.startsWith(".")) {
            LOGGER.debug("Skipping partially written file {}", fileName);
            return false;
        }
        return true;
    }

    private void unLoadNarFile(String fileName) {
        if (isSupported(fileName)) {
            File narWorkingDirectory = new File(extensionWorkDirectory, fileName + UNPACKED_POSTFIX);
            extensionManager.getAllBundles().stream()
                    .filter(nb -> nb.getBundleDetails().getWorkingDirectory().getPath().equals(narWorkingDirectory.getPath()))
                    .findFirst()
                    .ifPresentOrElse(narLoader::unload, () -> LOGGER.warn("NAR bundle not found for " + fileName));
        }
    }
}
