package ch.epfl.tkvs.transactionmanager.versioningunit;

import java.io.Serializable;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;


public class VersioningUnitMVCC2PL {

    /** Unique instance of the VersioningUnitMVTO class */
    private static VersioningUnitMVCC2PL instance = null;

    private final static int PRIMARY_CACHE = 0;

    private Map<Integer, Cache> caches;
    private Cache primary;
    private Deque<Cache> tmpPrimary;
    private BackgroundCommitThread backgroundCommitThread = null;
    private Object guard = new Object();

    /**
     * Private constructor of the Singleton
     */
    private VersioningUnitMVCC2PL() {
        // Exists only to defeat instantiation
    }

    /**
     * Double-checked locking method to return the unique object
     * 
     * @return singleton VersioningUnit
     */
    public static VersioningUnitMVCC2PL getInstance() {
        if (instance == null) {
            synchronized (VersioningUnitMVCC2PL.class) {
                if (instance == null) {
                    instance = new VersioningUnitMVCC2PL();
                }
            }
        }
        return instance;
    }

    /**
     * MUST be called before first use. This initializes the module.
     */
    public void init() {
        stopBackgroundCommitThreadIfAlive();

        caches = new ConcurrentHashMap<Integer, Cache>();
        primary = new Cache(PRIMARY_CACHE);
        tmpPrimary = new ConcurrentLinkedDeque<Cache>();

        backgroundCommitThread = new BackgroundCommitThread();
        backgroundCommitThread.start();
    }

    /**
     * Read the latest committed version of a key unless the current transaction has written it. In the latter case, it
     * returns the latest written version
     * 
     * @param xid the current transaction doing the read
     * @param key the key to read
     * @return the value associated with the key
     */
    public Serializable get(int xid, Serializable key) {
        Cache xactCache = caches.get(xid);

        if (xactCache != null) {
            Serializable value = xactCache.get(key);
            if (value != null) {
                return value;
            }
        }

        for (Cache c : tmpPrimary) {
            Serializable value = c.get(key);
            if (value != null) {
                return value;
            }
        }

        return primary.get(key);
    }

    /**
     * Write a new version for a given key
     * 
     * @param xid the current transaction doing the write
     * @param key the key to be written
     * @param value the value for the new version
     */
    public void put(int xid, Serializable key, Serializable value) {
        Cache xactCache = caches.get(xid);

        if (xactCache == null) {
            xactCache = new Cache(xid);

            caches.put(xid, xactCache);
        }

        xactCache.put(key, value);

    }

    /**
     * Commit the changes done by a transaction, it cannot fail The transaction SHOULD NOT do any other requests to the
     * VersioningUnit
     * 
     * @param xid the current transaction that wants to commit
     */
    public void commit(final int xid) {
        if (caches.get(xid) != null) {
            tmpPrimary.addFirst((caches.get(xid)));
            synchronized (guard) {
                guard.notifyAll();
            }
        }
    }

    /**
     * Abort the current transaction The transaction SHOULD NOT do any other requests
     * 
     * @param xid the transaction to be aborted
     */
    public void abort(int xid) {
        caches.remove(xid);
    }

    /**
     * Stop gracefully the VersioningUnit Must be call if one wants to restart the VersioningUnit No need to call it if
     * the application is exiting
     */
    public void stopNow() {
        stopBackgroundCommitThreadIfAlive();
    }

    private class BackgroundCommitThread extends Thread {

        private volatile boolean shouldRun = true;

        @Override
        public void run() {
            while (shouldRun) {

                while (tmpPrimary.isEmpty()) {
                    synchronized (guard) {

                        try {
                            guard.wait();
                        } catch (InterruptedException e) {
                            // TODO Take care of the exception
                            e.printStackTrace();
                        }
                    }
                    if (!shouldRun) {
                        return;
                    }
                }

                Cache cacheToCommit = tmpPrimary.getLast();

                for (Serializable key : cacheToCommit.getWrittenKeys()) {
                    primary.put(key, cacheToCommit.get(key));

                }

                tmpPrimary.removeLast();
                caches.remove(cacheToCommit.getXid());
            }
        }

        public void stopNow() {
            shouldRun = false;
            // In case the background thread is still waiting
            synchronized (guard) {
                guard.notifyAll();
            }
        }
    }

    private void stopBackgroundCommitThreadIfAlive() {
        if (backgroundCommitThread != null && backgroundCommitThread.isAlive()) {
            backgroundCommitThread.stopNow();
            try {
                backgroundCommitThread.join();
            } catch (InterruptedException e) {
                // TODO: think about it
                e.printStackTrace();
            }
        }
    }

}
