package org.ensime.indexer;

import java.io.File;
/**
 * Interface for clients that wish to be notified of changes by a {@RecursiveFileWatcher}.
 */
public interface FileChangeListener {
    void fileAdded(File f);
    void fileRemoved(File f);
    void fileChanged(File f);
}
