/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package ch.epfl.tkvs.transactionmanager.algorithms;

import ch.epfl.tkvs.exceptions.TransactionNotLiveException;
import ch.epfl.tkvs.transactionmanager.Transaction;
import ch.epfl.tkvs.transactionmanager.TransactionManager;
import ch.epfl.tkvs.transactionmanager.communication.requests.AbortRequest;
import ch.epfl.tkvs.transactionmanager.communication.requests.BeginRequest;
import ch.epfl.tkvs.transactionmanager.communication.requests.CommitRequest;
import ch.epfl.tkvs.transactionmanager.communication.requests.PrepareRequest;
import ch.epfl.tkvs.transactionmanager.communication.requests.ReadRequest;
import ch.epfl.tkvs.transactionmanager.communication.requests.TryCommitRequest;
import ch.epfl.tkvs.transactionmanager.communication.requests.WriteRequest;
import ch.epfl.tkvs.transactionmanager.communication.responses.GenericSuccessResponse;
import ch.epfl.tkvs.transactionmanager.communication.responses.ReadResponse;
import ch.epfl.tkvs.yarn.HDFSLogger;


/**
 * This abstract class represents an concurrency control algorithm. One must come up with actual implementation of such
 * a class and inject it in the transaction manager in order to get a custom concurrency algorithm.
 */
public abstract class CCAlgorithm {

    protected RemoteHandler remote;
    public static HDFSLogger log;

    /**
     * Called whenever the {@link TransactionManager} receives a read request
     * @param request the incoming read request
     * @return the response to be sent to the sender
     */
    public abstract ReadResponse read(ReadRequest request);

    /**
     * Called whenever the {@link TransactionManager} receives a write request.
     * @param request the incoming write request
     * @return the response to be sent to the sender
     */
    public abstract GenericSuccessResponse write(WriteRequest request);

    /**
     * Called whenever the {@link TransactionManager} receives a request to begin a transaction
     * @param request the incoming begin request
     * @return the response to be sent to the sender
     */
    public abstract GenericSuccessResponse begin(BeginRequest request);

    /**
     * Called whenever the {@link TransactionManager} receives a commit request.
     * @param request the incoming commit request
     * @return the response to be sent to the sender
     */
    public abstract GenericSuccessResponse commit(CommitRequest request);

    /**
     * Called whenever the {@link TransactionManager} receives an abort request.
     * @param request the incoming abort request
     * @return the response to be sent to the sender
     */
    public abstract GenericSuccessResponse abort(AbortRequest request);

    /**
     * Called whenever the {@link TransactionManager} receives a prepare request (first phase of 2PC).
     * @param request the incoming prepare request
     * @return the response to be sent to the sender
     */
    public abstract GenericSuccessResponse prepare(PrepareRequest request);

    /**
     * Returns a {@link Transaction} object given its id.
     * @param xid the id of the transaction
     * @return the corresponding transaction
     */
    public abstract Transaction getTransaction(int xid);

    /**
     * Called by user client to prepare and commit the transaction, using 2-Phase Commit protocol in case of distributed
     * transaction
     * @param request incoming TryCommitRequests
     * @return the response to be sent to the sender
     */
    public GenericSuccessResponse tryCommit(TryCommitRequest request) {
        int xid = request.getTransactionId();

        Transaction transaction = getTransaction(xid);

        // Transaction not begun or already terminated
        if (transaction == null) {
            return new GenericSuccessResponse(new TransactionNotLiveException());
        }

        if (isLocalTransaction(transaction)) {
            prepare(new PrepareRequest(xid));
            return commit(new CommitRequest(xid));
        } else
            return remote.tryCommit(transaction);
    }

    /**
     * This method is called periodically by the {@link TransactionManager}. It can be used for a lot of purpose
     * including: <ul> <li>Fault tolerance</li> <li>Sending report to some node</li> <li>Internal state audit</li> </ul>
     * This is up to the actual implementation of the concurrency control algorithm to decide. If such a function is not
     * needed, please leave it empty.
     */
    abstract public void checkpoint();

    public CCAlgorithm(RemoteHandler remote, HDFSLogger log) {
        this.remote = remote;
        CCAlgorithm.log = log;
    }

    /**
     * Checks if the key is managed locally or by another {@link TransactionManager}
     * @param localityHash hash of the key
     * @return true if either remote handling is disabled or if the locality hash of key matches that handled by local
     * Transaction Manager
     */
    protected boolean isLocalKey(int localityHash) {
        return (remote == null) || TransactionManager.isLocalLocalityHash(localityHash);
    }

    /**
     * Checks if a given {@link Transaction} is either a secondary transaction or a primary transaction with no
     * secondary transctions.
     * @param t The transaction object
     * @return false if this transaction object represents a primary transaction which has at least one secondary
     * transaction running on a different {@link TransactionManager}, true otherwise
     */
    protected boolean isLocalTransaction(Transaction t) {
        return (remote == null) || (t.remoteIsPrepared.isEmpty());
    }
}
