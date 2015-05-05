package ch.epfl.tkvs.transactionmanager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.hadoop.net.NetUtils;
import org.apache.log4j.Logger;
import org.apache.log4j.net.SocketServer;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import ch.epfl.tkvs.transactionmanager.algorithms.CCAlgorithm;
import ch.epfl.tkvs.transactionmanager.algorithms.MVTO;
import ch.epfl.tkvs.transactionmanager.algorithms.RemoteHandler;
import ch.epfl.tkvs.transactionmanager.algorithms.Simple2PL;
import ch.epfl.tkvs.transactionmanager.communication.Message;
import ch.epfl.tkvs.transactionmanager.communication.utils.Base64Utils;
import ch.epfl.tkvs.transactionmanager.communication.utils.Message2JSONConverter;
import ch.epfl.tkvs.yarn.RoutingTable;
import ch.epfl.tkvs.yarn.Utils;
import ch.epfl.tkvs.yarn.appmaster.AppMaster;


/**
 * The TransactionManager is the deamon started by the {@link AppMaster} on many nodes of the cluster.
 * 
 * It is mainly a server which answers the client requests.
 *
 */
public class TransactionManager {

    private static final int THREAD_POOL_SIZE = 15;
    private static final int CHECKPOINT_PERIOD_MS = 15000;
    private static String tmHost;
    private static int tmPort;
    private static RoutingTable routing;
    private static boolean isAMReady = false;

    private final static Logger log = Logger.getLogger(TransactionManager.class.getName());

    public static void main(String[] args) throws Exception {

        log.info("Initializing...");
        try {
            String amIp = System.getenv("AM_IP");
            int amPort = Integer.parseInt(System.getenv("AM_PORT"));
            tmPort = NetUtils.getFreeSocketPort();
            tmHost = Utils.extractIP(NetUtils.getHostname());

            log.info("Sending Host:Port to AppMaster");
            Socket sock = new Socket(amIp, amPort);
            PrintWriter out = new PrintWriter(sock.getOutputStream(), true);
            out.println(tmHost + ":" + tmPort);
            out.close();
            sock.close();

            new TransactionManager().run();
        } catch (Exception ex) {
            log.fatal("Could not run transaction manager", ex);
        }
        log.info("Finished");
        System.exit(0);
    }

    public void run() throws Exception {
        log.info("Starting server at " + tmHost + ":" + tmPort);
        ServerSocket server = new ServerSocket(tmPort);

        Socket sock = server.accept();
        BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream()));
        String input = in.readLine();
        routing = (RoutingTable) Base64Utils.convertFromBase64(input);

        RemoteHandler remoteHandler = new RemoteHandler();

        // Select which concurrency algorithm to use
        // concurrencyController = new Simple2PL(remoteHandler);
        CCAlgorithm concurrencyController = new Simple2PL(null);

        remoteHandler.setAlgo(concurrencyController);

        // Start the thread that will call checkpoint on the concurrency controller
        startCheckpointThread(server, concurrencyController);

        ExecutorService threadPool = Executors.newFixedThreadPool(THREAD_POOL_SIZE);

        while (!server.isClosed()) {
            try {
                log.info("Waiting for message...");
                sock = server.accept();
                log.info("Processing message...");

                in = new BufferedReader(new InputStreamReader(sock.getInputStream()));
                input = in.readLine();

                switch (input) {
                case ":exit":
                    log.info("Stopping Server");
                    sock.close();
                    server.close();
                    threadPool.shutdown();
                    break;
                default:
                    threadPool.execute(new TMWorker(new JSONObject(input), sock, concurrencyController));
                }
            } catch (IOException e) {
                log.error("sock.accept ", e);
            }
        }
        log.info("Finalizing");
        server.close();
    }

    /**
     * Helper method to send a message to the app master. Might be blocking if the app master is not ready on start up.
     * @param message the message to send
     * @throws IOException in case of network error or invalid message format
     */
    public static void sendToAppMaster(Message message) throws IOException {

        Socket sock = new Socket(routing.getAMIp(), routing.getAMPort());
        BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream()));
        PrintWriter out = new PrintWriter(sock.getOutputStream());

        while (!isAMReady) {
            out.println(":ping");
            out.flush();

            String response = in.readLine();
            if (response != null && response.equals("ok")) {
                isAMReady = true;
            } else {
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    throw new IOException(e);
                }
            }
        }

        JSONObject json = null;
        try {
            json = Message2JSONConverter.toJSON(message);
        } catch (JSONException e) {
            log.error(e);
            throw new IOException("An exception occurred while converting the message to json: " + e);
        }

        out.println(json.toString());
        out.flush();

        in.close();
        out.close();
        sock.close();
    }

    // Start the thread responsible for calling the checkpoint methods of the concurrency control algorithms
    private void startCheckpointThread(final ServerSocket mainServer, final CCAlgorithm ccAlg) {
        new Thread(new Runnable() {

            @Override
            public void run() {
                while (!mainServer.isClosed()) {

                    try {
                        Thread.sleep(CHECKPOINT_PERIOD_MS);
                    } catch (InterruptedException e) {
                        // TODO Handle this error
                        e.printStackTrace();
                    }

                    ccAlg.checkpoint();
                }
            }
        }).start();
    }
}
