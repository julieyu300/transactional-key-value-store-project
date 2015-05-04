package ch.epfl.tkvs.transactionmanager.algorithms;

import static ch.epfl.tkvs.transactionmanager.lockingunit.LockCompatibilityTable.newCompatibilityList;

import java.io.Serializable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import ch.epfl.tkvs.transactionmanager.AbortException;
import ch.epfl.tkvs.transactionmanager.Transaction;
import ch.epfl.tkvs.transactionmanager.communication.requests.AbortRequest;
import ch.epfl.tkvs.transactionmanager.communication.requests.BeginRequest;
import ch.epfl.tkvs.transactionmanager.communication.requests.CommitRequest;
import ch.epfl.tkvs.transactionmanager.communication.requests.PrepareRequest;
import ch.epfl.tkvs.transactionmanager.communication.requests.ReadRequest;
import ch.epfl.tkvs.transactionmanager.communication.requests.WriteRequest;
import ch.epfl.tkvs.transactionmanager.communication.responses.GenericSuccessResponse;
import ch.epfl.tkvs.transactionmanager.communication.responses.ReadResponse;
import ch.epfl.tkvs.transactionmanager.lockingunit.LockType;
import ch.epfl.tkvs.transactionmanager.lockingunit.LockingUnit;
import ch.epfl.tkvs.transactionmanager.versioningunit.VersioningUnitMVCC2PL;


public class MVCC2PL implements Algorithm {

    private LockingUnit lockingUnit;
    private VersioningUnitMVCC2PL versioningUnit;

    public MVCC2PL() {
        lockingUnit = LockingUnit.instance;

        HashMap<LockType, List<LockType>> lockCompatibility = new HashMap<>();
        lockCompatibility.put(Lock.READ_LOCK, newCompatibilityList(Lock.READ_LOCK, Lock.WRITE_LOCK));
        lockCompatibility.put(Lock.WRITE_LOCK, newCompatibilityList(Lock.READ_LOCK));
        lockCompatibility.put(Lock.COMMIT_LOCK, newCompatibilityList());
        lockingUnit.initWithLockCompatibilityTable(lockCompatibility);

        versioningUnit = VersioningUnitMVCC2PL.getInstance();
        versioningUnit.init();

        transactions = new ConcurrentHashMap<>();
    }

    @Override
    public ReadResponse read(ReadRequest request) {
        int xid = request.getTransactionId();
        Serializable key = request.getEncodedKey();

        MVCC2PL_Transaction transaction = transactions.get(xid);

        // Transaction not begun or already terminated
        if (transaction == null) {
            return new ReadResponse(false, null);
        }

        Lock lock = Lock.READ_LOCK;
        try {
            if (!transaction.checkLock(key, lock)) {
                lockingUnit.lock(xid, key, lock);
                transaction.addLock(key, lock);
            }
            Serializable value = versioningUnit.get(xid, key);
            return new ReadResponse(true, (String) value);
        } catch (AbortException e) {
            terminate(transaction, false);
            return new ReadResponse(false, null);
        }

    }

    @Override
    public GenericSuccessResponse write(WriteRequest request) {
        int xid = request.getTransactionId();
        Serializable key = request.getEncodedKey();
        Serializable value = request.getEncodedValue();

        MVCC2PL_Transaction transaction = transactions.get(xid);

        // Transaction not begun or already terminated
        if (transaction == null) {
            return new GenericSuccessResponse(false);
        }

        Lock lock = Lock.WRITE_LOCK;
        try {
            if (!transaction.checkLock(key, lock)) {
                lockingUnit.lock(xid, key, lock);
                transaction.addLock(key, lock);
            }
            versioningUnit.put(xid, key, value);
            return new GenericSuccessResponse(true);
        } catch (AbortException e) {
            terminate(transaction, false);
            return new GenericSuccessResponse(false);
        }
    }

    @Override
    public GenericSuccessResponse begin(BeginRequest request) {
        int xid = request.getTransactionId();

        // Transaction with duplicate id
        if (transactions.containsKey(xid)) {
            return new GenericSuccessResponse(false);
        }
        transactions.put(xid, new MVCC2PL_Transaction(xid));

        return new GenericSuccessResponse(true);
    }

    @Override
    public GenericSuccessResponse commit(CommitRequest request) {
        int xid = request.getTransactionId();

        MVCC2PL_Transaction transaction = transactions.get(xid);

        // Transaction not begun or already terminated or not prepared
        if (transaction == null || !transaction.isPrepared) {
            return new GenericSuccessResponse(false);
        }
        terminate(transaction, true);
        return new GenericSuccessResponse(true);

    }

    private ConcurrentHashMap<Integer, MVCC2PL_Transaction> transactions;

    @Override
    public GenericSuccessResponse abort(AbortRequest request) {
        int xid = request.getTransactionId();

        MVCC2PL_Transaction transaction = transactions.get(xid);

        // Transaction not begun or already terminated
        if (transaction == null) {
            return new GenericSuccessResponse(false);
        }
        terminate(transaction, false);
        return new GenericSuccessResponse(true);
    }

    @Override
    public GenericSuccessResponse prepare(PrepareRequest request) {
        int xid = request.getTransactionId();

        MVCC2PL_Transaction transaction = transactions.get(xid);

        // Transaction not begun or already terminated
        if (transaction == null) {
            return new GenericSuccessResponse(false);
        }
        try {
            for (Serializable key : transaction.getLockedKeys()) {

                // promote each write lock to commit lock
                if (transaction.getLocksForKey(key).contains(Lock.WRITE_LOCK)) {
                    lockingUnit.promote(xid, key, transaction.getLocksForKey(key), Lock.COMMIT_LOCK);
                    // remove old locks
                    transaction.setLock(key, Arrays.asList((LockType) Lock.COMMIT_LOCK));
                }
            }
            transaction.isPrepared = true;
            return new GenericSuccessResponse(true);

        } catch (AbortException e) {
            terminate(transaction, false);
            return new GenericSuccessResponse(false);
        }
    }

    @Override
    public Transaction getTransaction(int id) {
        return transactions.get(id);
    }

    @Override
    public Set<Integer> getAllIds() {
        return transactions.keySet();
    }

    private static enum Lock implements LockType {

        READ_LOCK, WRITE_LOCK, COMMIT_LOCK
    }

    // Does cleaning up after end of transaction
    private void terminate(MVCC2PL_Transaction transaction, boolean success) {
        if (success) {
            versioningUnit.commit(transaction.transactionId);
        } else {
            versioningUnit.abort(transaction.transactionId);
        }
        lockingUnit.releaseAll(transaction.transactionId, transaction.currentLocks);
        transactions.remove(transaction.transactionId);
    }

    public static class MVCC2PL_Transaction extends Transaction {

        private HashMap<Serializable, List<LockType>> currentLocks;

        public MVCC2PL_Transaction(int transactionId) {
            super(transactionId);

            currentLocks = new HashMap<>();
        }

        /**
         * Replaces old locks for a key with new locks
         *
         * @param key
         * @param newLocks
         */
        public void setLock(Serializable key, List<LockType> newLocks) {
            currentLocks.put(key, newLocks);
        }

        /**
         * Adds lock of type lockType for a key
         *
         * @param key
         * @param lockType
         */
        public void addLock(Serializable key, Lock lockType) {
            if (currentLocks.containsKey(key)) {
                currentLocks.get(key).add(lockType);
            } else {
                currentLocks.put(key, new LinkedList<LockType>(Arrays.asList(lockType)));
            }
        }

        public boolean checkLock(Serializable key, Lock lockType) {
            return currentLocks.get(key) != null && currentLocks.get(key).contains(lockType);
        }

        public Set<Serializable> getLockedKeys() {
            return currentLocks.keySet();
        }

        public List<LockType> getLocksForKey(Serializable key) {
            return currentLocks.get(key);
        }
    }
}
