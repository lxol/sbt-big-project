package org.ensime.indexer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.File;

import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchEvent.Kind;
import java.nio.file.PathMatcher;

import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;


public class FileMonitor {

    private final Logger logger = LoggerFactory.getLogger(FileMonitor.class);

    private final Map<WatchKey, Path> directories = new HashMap<>();
    private final CopyOnWriteArrayList<FileChangeListener> listeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArraySet<String> selectors = new CopyOnWriteArraySet<>();
    private final CopyOnWriteArrayList<File> watchedDirs = new CopyOnWriteArrayList<>();
    private PathMatcher pathMatcher;
    private WatchService watchService;

    /**
     * Construct a new watcher with no listeners
     */
    public FileMonitor() {
    }

    /**
     * Construct a new watcher with the provided listeners and selectors
     * and directories to watch
     *
     * @param listeners
     *        The listeners
     * @param selectors
     *        The selectors
     * @param watchedDirs
     *        The watchedDirs
     * @param
     */
    public FileMonitor(List<FileChangeListener> listeners,
                                Set<String> selectors,
                                List<File> watchedDirs) {
        for (final FileChangeListener listener : listeners) {
            addListener(listener);
        }
        for (final String selector : selectors) {
            addSelector(selector);
        }
        for (final File watchedDir : watchedDirs) {
            addWatchedDir(watchedDir);
        }
    }


    /**
     * Add a {@link FileChangteListener} to be notified of changes.
     *
     * @param listener The listener to notify.
     */
    public void addListener(final FileChangeListener listener) {
        logger.debug("listener added");
        listeners.add(listener);
    }

    /**
     * Remove a {@link FileChangeListener} from notifications.
     *
     * @param listener The listener to stop notifying.
     */
    public void removeListener(final FileChangeListener listener) {
        listeners.remove(listener);
    }

    /**
     * Add a {@link FileChangteListener} to be notified of changes.
     *
     * @param selector The selector to watch.
     */
    public void addSelector(final String selector) {
        selectors.add(selector);
        String pattern = "glob:**.{";
        String delim = "";
        for (String s : selectors) {
            pattern += delim + s;
            delim = ",";
        }
        pattern += "}";
        pathMatcher = FileSystems.getDefault().getPathMatcher(pattern);
    }

    /**
     * Add a {@link FileChangeListener} to be notified of changes.
     *
     * @param watchedDir The watchedDir to watch.
     */
    public void addWatchedDir(final File watchedDir) {
        watchedDirs.add(watchedDir);
    }

    private void registerPath(Path path) throws IOException {
        WatchKey key = path.register(watchService, StandardWatchEventKinds.ENTRY_CREATE,
                                     StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE);
        directories.put(key, path);
    }

    private void registerTree(Path start) throws IOException {
        Files.walkFileTree(start, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                    throws IOException {
                    registerPath(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
    }

    public void register() {
        try {
            watchService = FileSystems.getDefault().newWatchService();
            for (File dir : watchedDirs) {
                registerTree(dir.toPath());
            }
        } catch (final Exception e) {
            throw new RuntimeException("Register error: ", e);
        }
    }

    public void watch() {
        watch(-1);
    }

    public void watch(int count) {
        try {
            _watch(count);
        } catch (final Exception e) {
            throw new RuntimeException("FileMonitor error ", e);
        }
    }

    private void _watch(int count) throws IOException, InterruptedException {
        while (true) {
            if (count == 0) {return;}
            if (count > 0) {count -= 1;}
            final WatchKey key = watchService.take();
            for (WatchEvent<?> watchEvent : key.pollEvents()) {
                final Kind<?> kind = watchEvent.kind();
                final WatchEvent<Path> watchEventPath = (WatchEvent<Path>) watchEvent;
                final Path filename = watchEventPath.context();
                if (kind == StandardWatchEventKinds.OVERFLOW) {
                    // todo: report/warn on the overflow condition
                    continue;
                }

                if (kind == StandardWatchEventKinds.ENTRY_CREATE) {
                    final Path directory_path = directories.get(key);
                    final Path child = directory_path.resolve(filename);

                    if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                        registerTree(child);
                    }
                }
                if (isWatched(filename)) {
                    notify(filename, kind);
                }
            }

            boolean valid = key.reset();
            if (!valid) {
                directories.remove(key);
                if (directories.isEmpty()) {
                    //todo: what to do if there is nothing to watch?
                    break;
                }
            }
        }
        watchService.close();
    }

    private Boolean isWatched(Path path) {
        return !Files.isDirectory(path) && pathMatcher.matches(path);
    }

    public void shutdown() {
        if (watchService != null) {
            try {
                watchService.close();
            } catch(IOException e) {
                logger.error("unable to close watchService {}", e);
            }
        }
    }
    /**
     * Notify listeners of the event.
     *
     * @param path  The path the event occurred on.
     * @param event The event.
     */
    private void notify(final Path path, final Kind<?> event) {
        logger.debug("{} event received for {}", event, path);
        if (StandardWatchEventKinds.ENTRY_CREATE.equals(event)) {
            for (FileChangeListener listener : listeners) {
                listener.fileAdded(path.toFile());
            }
        } else if (StandardWatchEventKinds.ENTRY_MODIFY.equals(event)) {
            for (FileChangeListener listener : listeners) {
                listener.fileChanged(path.toFile());
            }
        } else if (StandardWatchEventKinds.ENTRY_DELETE.equals(event)) {
            for (FileChangeListener listener : listeners) {
                listener.fileRemoved(path.toFile());
            }
        } else {
            logger.warn("Unhandled event: {}", event);
        }
    }
}
