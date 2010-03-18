/* This file is part of VoltDB.
 * Copyright (C) 2008-2010 VoltDB L.L.C.
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
 * OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */

package org.voltdb.benchmark;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import org.voltdb.VoltTable;
import org.voltdb.VoltType;
import org.voltdb.benchmark.Verification.Expression;
import org.voltdb.client.Client;
import org.voltdb.client.ClientFactory;
import org.voltdb.client.ClientResponse;
import org.voltdb.client.NoConnectionsException;
import org.voltdb.client.ProcCallException;
import org.voltdb.processtools.SSHTools;
import org.voltdb.sysprocs.saverestore.TableSaveFile;
import org.voltdb.utils.Pair;
import org.voltdb.utils.VoltSampler;
import org.voltdb.utils.DBBPool.BBContainer;

/**
 * Base class for clients that will work with the multi-host multi-process
 * benchmark framework that is driven from stdin
 */
public abstract class ClientMain {

    /**
     * Client initialized here and made available for use in derived classes
     */
    protected final Client m_voltClient;

    /**
     * Manage input and output to the framework
     */
    private final ControlPipe m_controlPipe = new ControlPipe();

    /**
     * State of this client
     */
    private volatile ControlState m_controlState = ControlState.PREPARING;

    /**
     * A host, can be any one. This is only used by data verification
     * at the end of run.
     */
    private String m_host;

    /**
     * Username supplied to the Volt client
     */
    private final String m_username;

    /**
     * Password supplied to the Volt client
     */
    private final String m_password;

    /**
     * Rate at which transactions should be generated. If set to -1 the rate
     * will be controlled by the derived class. Rate is in transactions per
     * second
     */
    private final int m_txnRate;

    /**
     * Number of transactions to generate for every millisecond of time that
     * passes
     */
    private final double m_txnsPerMillisecond;

    /**
     * Storage for error descriptions
     */
    private String m_reason = "";

    /**
     * Count of transactions invoked by this client. This is updated by derived
     * classes directly
     */
    protected final AtomicLong m_counts[];

    /**
     * Display names for each transaction.
     */
    protected final String m_countDisplayNames[];

    protected final int m_id;

    private final boolean m_exitOnCompletion;

    /**
     * Data verification.
     */
    private final float m_checkTransaction;
    private final boolean m_checkTables;
    private final Random m_checkGenerator = new Random();
    private final LinkedHashMap<Pair<String, Integer>, Expression> m_constraints;
    private final List<String> m_tableCheckOrder = new LinkedList<String>();
    protected VoltSampler m_sampler = null;

    /** The states important to the remote controller */
    public static enum ControlState {
        PREPARING("PREPARING"), READY("READY"), RUNNING("RUNNING"), ERROR(
            "ERROR");

        ControlState(final String displayname) {
            display = displayname;
        }

        public final String display;
    };

    /**
     * Implements the simple state machine for the remote controller protocol.
     * Hypothetically, you can extend this and override the answerPoll() and
     * answerStart() methods for other clients.
     */
    class ControlPipe implements Runnable {

        public void run() {
            String command = "";
            final InputStreamReader reader = new InputStreamReader(System.in);
            final BufferedReader in = new BufferedReader(reader);

            // transition to ready and send ready message
            if (m_controlState == ControlState.PREPARING) {
                System.out.printf("%d,%s\n", System.currentTimeMillis(),
                                  ControlState.READY.display);
                m_controlState = ControlState.READY;
            }
            else {
                System.err.println("Error - not starting prepared!");
                System.err.println(m_controlState.display + " " + m_reason);
            }

            while (true) {
                try {
                    command = in.readLine();
                }
                catch (final IOException e) {
                    // Hm. quit?
                    System.err.println("Error on standard input: "
                        + e.getMessage());
                    System.exit(-1);
                }

                if (command.equalsIgnoreCase("START")) {
                    if (m_controlState != ControlState.READY) {
                        setState(ControlState.ERROR, "START when not READY.");
                        answerWithError();
                        continue;
                    }
                    answerStart();
                    m_controlState = ControlState.RUNNING;
                }
                else if (command.equalsIgnoreCase("POLL")) {
                    if (m_controlState != ControlState.RUNNING) {
                        setState(ControlState.ERROR, "POLL when not RUNNING.");
                        answerWithError();
                        continue;
                    }
                    answerPoll();
                }
                else if (command.equalsIgnoreCase("STOP")) {
                    if (m_controlState == ControlState.RUNNING) {
                        try {
                            if (m_sampler != null) {
                                m_sampler.setShouldStop();
                                m_sampler.join();
                            }
                            m_voltClient.shutdown();
                            if (m_checkTables) {
                                checkTables();
                            }
                        } catch (InterruptedException e) {
                            System.exit(0);
                        }
                        System.exit(0);
                    }
                    System.err.println("Error: STOP when not RUNNING");
                    System.exit(-1);
                }
                else {
                    System.err
                        .println("Error on standard input: unknown command "
                            + command);
                    System.exit(-1);
                }
            }
        }

        public void answerWithError() {
            System.out.printf("%d,%s,%s\n", System.currentTimeMillis(),
                              m_controlState.display, m_reason);
        }

        public void answerPoll() {
            final StringBuilder txncounts = new StringBuilder();
            synchronized (m_counts) {
                for (int i = 0; i < m_counts.length; ++i) {
                    txncounts.append(",");
                    txncounts.append(m_countDisplayNames[i]);
                    txncounts.append(",");
                    txncounts.append(m_counts[i].get());
                }
            }
            System.out.printf("%d,%s%s\n", System.currentTimeMillis(),
                              m_controlState.display, txncounts.toString());
        }

        public void answerStart() {
            final ControlWorker worker = new ControlWorker();
            new Thread(worker).start();
        }
    }

    /**
     * Thread that executes the derives classes run loop which invokes stored
     * procedures indefinitely
     */
    private class ControlWorker extends Thread {
        @Override
        public void run() {
            if (m_txnRate == -1) {
                if (m_sampler != null) {
                    m_sampler.start();
                }
                try {
                    runLoop();
                }
                catch (final NoConnectionsException e) {

                }
            }
            else {
                System.err.println("Running rate controlled m_txnRate == "
                    + m_txnRate + " m_txnsPerMillisecond == "
                    + m_txnsPerMillisecond);
                System.err.flush();
                rateControlledRunLoop();
            }

            if (m_exitOnCompletion) {
                System.exit(0);
            }
        }

        /*
         * Time in milliseconds since requests were last sent.
         */
        private long m_lastRequestTime;

        private void rateControlledRunLoop() {
            m_lastRequestTime = System.currentTimeMillis();
            while (true) {
                boolean bp = false;
                /*
                 * If there is back pressure don't send any requests. Update the
                 * last request time so that a large number of requests won't
                 * queue up to be sent when there is no longer any back
                 * pressure.
                 */
                try {
                    m_voltClient.backpressureBarrier();
                } catch (InterruptedException e1) {
                    throw new RuntimeException();
                }

                final long now = System.currentTimeMillis();

                /*
                 * Generate the correct number of transactions based on how much
                 * time has passed since the last time transactions were sent.
                 */
                final long delta = now - m_lastRequestTime;
                if (delta > 0) {
                    final int transactionsToCreate =
                        (int) (delta * m_txnsPerMillisecond);
                    if (transactionsToCreate < 1) {
                        Thread.yield();
                        continue;
                    }

                    for (int ii = 0; ii < transactionsToCreate; ii++) {
                        try {
                            bp = !runOnce();
                            if (bp) {
                                m_lastRequestTime = now;
                                break;
                            }
                        }
                        catch (final NoConnectionsException e) {
                            return;
                        }
                    }
                }
                else {
                    Thread.yield();
                }

                m_lastRequestTime = now;
            }
        }
    }

    /**
     * Implemented by derived classes. Loops indefinitely invoking stored
     * procedures. Method never returns and never receives any updates.
     */
    abstract protected void runLoop() throws NoConnectionsException;

    protected boolean useHeavyweightClient() {
        return false;
    }

    /**
     * Implemented by derived classes. Invoke a single procedure without running
     * the network. This allows ClientMain to control the rate at which
     * transactions are generated.
     *
     * @return True if an invocation was queued and false otherwise
     */
    protected boolean runOnce() throws NoConnectionsException {
        throw new UnsupportedOperationException();
    }

    /**
     * Hint used when constructing the Client to control the size of buffers
     * allocated for message serialization
     *
     * @return
     */
    protected int getExpectedOutgoingMessageSize() {
        return 128;
    }

    /**
     * Get the display names of the transactions that will be invoked by the
     * dervied class. As a side effect this also retrieves the number of
     * transactions that can be invoked.
     *
     * @return
     */
    abstract protected String[] getTransactionDisplayNames();

    public ClientMain(final Client client) {
        m_voltClient = client;
        m_exitOnCompletion = false;
        m_host = "localhost";
        m_password = "";
        m_username = "";
        m_txnRate = -1;
        m_txnsPerMillisecond = 0;
        m_id = 0;
        m_counts = null;
        m_countDisplayNames = null;
        m_checkTransaction = 0;
        m_checkTables = false;
        m_constraints = new LinkedHashMap<Pair<String, Integer>, Expression>();
    }

    abstract protected String getApplicationName();
    abstract protected String getSubApplicationName();

    /**
     * Constructor that initializes the framework portions of the client.
     * Creates a Volt client and connects it to all the hosts provided on the
     * command line with the specified username and password
     *
     * @param args
     */
    public ClientMain(String args[]) {
        /*
         * Input parameters: HOST=host:port (may occur multiple times)
         * USER=username PASSWORD=password
         */

        // default values
        String username = "";
        String password = "";
        ControlState state = ControlState.PREPARING; // starting state
        String reason = ""; // and error string
        int transactionRate = -1;
        int id = 0;
        boolean exitOnCompletion = true;
        float checkTransaction = 0;
        boolean checkTables = false;
        String statsDatabaseURL = null;
        int statsPollInterval = 10000;

        // scan the inputs once to read everything but host names
        for (final String arg : args) {
            final String[] parts = arg.split("=", 2);
            if (parts.length == 1) {
                state = ControlState.ERROR;
                reason = "Invalid parameter: " + arg;
                break;
            }
            else if (parts[1].startsWith("${")) {
                continue;
            }
            else if (parts[0].equals("USER")) {
                username = parts[1];
            }
            else if (parts[0].equals("PASSWORD")) {
                password = parts[1];
            }
            else if (parts[0].equals("EXITONCOMPLETION")) {
                exitOnCompletion = Boolean.parseBoolean(parts[1]);
            }
            else if (parts[0].equals("TXNRATE")) {
                transactionRate = Integer.parseInt(parts[1]);
            }
            else if (parts[0].equals("ID")) {
                id = Integer.parseInt(parts[1]);
            }
            else if (parts[0].equals("CHECKTRANSACTION")) {
                checkTransaction = Float.parseFloat(parts[1]);
            }
            else if (parts[0].equals("CHECKTABLES")) {
                checkTables = Boolean.parseBoolean(parts[1]);
            } else if (parts[0].equals("STATSDATABASEURL")) {
                statsDatabaseURL = parts[1];
            } else if (parts[0].equals("STATSPOLLINTERVAL")) {
                statsPollInterval = Integer.parseInt(parts[1]);
            }
        }
        ClientFactory.StatsUploaderSettings statsSettings = null;
        if (statsDatabaseURL != null) {
            try {
                statsSettings =
                    new
                        ClientFactory.StatsUploaderSettings(
                            statsDatabaseURL,
                            getApplicationName(),
                            getSubApplicationName(),
                            statsPollInterval);
            } catch (SQLException e) {
                e.printStackTrace();
                statsSettings = null;
            }
        }
        m_voltClient =
            ClientFactory.createClient(
                getExpectedOutgoingMessageSize(),
                null,
                useHeavyweightClient(),
                statsSettings);

        m_id = id;
        m_exitOnCompletion = exitOnCompletion;
        m_username = username;
        m_password = password;
        m_txnRate = transactionRate;
        m_txnsPerMillisecond = transactionRate / 1000.0;

        // report any errors that occurred before the client was instantiated
        if (state != ControlState.PREPARING)
            setState(state, reason);

        // scan the inputs again looking for host connections
        boolean atLeastOneConnection = false;
        for (final String arg : args) {
            final String[] parts = arg.split("=", 2);
            if (parts.length == 1) {
                continue;
            }
            else if (parts[0].equals("HOST")) {
                final String hostnport[] = parts[1].split("\\:", 2);
                m_host = hostnport[0];
                try {
                    System.err.println("Creating connection to  "
                        + hostnport[0]);
                    createConnection(hostnport[0]);
                    System.err.println("Created connection.");
                    atLeastOneConnection = true;
                }
                catch (final Exception ex) {
                    setState(ControlState.ERROR, "createConnection to " + arg
                        + " failed: " + ex.getMessage());
                }
            }
        }
        if (!atLeastOneConnection)
            setState(ControlState.ERROR, "No HOSTS specified on command line.");
        m_checkTransaction = checkTransaction;
        m_checkTables = checkTables;
        m_constraints = new LinkedHashMap<Pair<String, Integer>, Expression>();

        m_countDisplayNames = getTransactionDisplayNames();
        m_counts = new AtomicLong[m_countDisplayNames.length];
        for (int ii = 0; ii < m_counts.length; ii++) {
            m_counts[ii] = new AtomicLong(0);
        }
    }

    /**
     * Derived classes implementing a main that will be invoked at the start of
     * the app should call this main to instantiate themselves
     *
     * @param clientClass
     *            Derived class to instantiate
     * @param args
     * @param startImmediately
     *            Whether to start the client thread immediately or not.
     */
    public static void main(final Class<? extends ClientMain> clientClass,
        final String args[], final boolean startImmediately) {
        try {
            final Constructor<? extends ClientMain> constructor =
                clientClass.getConstructor(new Class<?>[] { new String[0]
                    .getClass() });
            final ClientMain clientMain =
                constructor.newInstance(new Object[] { args });
            if (startImmediately) {
                final ControlWorker worker = clientMain.new ControlWorker();
                worker.start();
                // Wait for the worker to finish
                worker.join();
            }
            else {
                clientMain.start();
            }
        }
        catch (final Exception e) {
            e.printStackTrace();
            System.exit(-1);
        }
    }

    // update the client state and start waiting for a message.
    private void start() {
        m_controlPipe.run();
    }

    public void setState(final ControlState state, final String reason) {
        m_controlState = state;
        if (m_reason.equals("") == false)
            m_reason += (" " + reason);
        else
            m_reason = reason;
    }

    private void createConnection(final String hostname)
        throws UnknownHostException, IOException {
        m_voltClient.createConnection(hostname, m_username, m_password);
    }

    private boolean checkConstraints(String procName, ClientResponse response) {
        boolean isSatisfied = true;
        int orig_position = -1;

        // Check if all the tables in the result set satisfy the constraints.
        for (int i = 0; isSatisfied && i < response.getResults().length; i++) {
            Pair<String, Integer> key = Pair.of(procName, i);
            if (!m_constraints.containsKey(key))
                continue;

            VoltTable table = response.getResults()[i];
            orig_position = table.getActiveRowIndex();
            table.resetRowPosition();

            // Iterate through all rows and check if they satisfy the
            // constraints.
            while (isSatisfied && table.advanceRow()) {
                isSatisfied = Verification.checkRow(m_constraints.get(key), table);
            }

            // Have to reset the position to its original position.
            if (orig_position < 0)
                table.resetRowPosition();
            else
                table.advanceToRow(orig_position);
        }

        if (!isSatisfied)
            System.err.println("Transaction " + procName + " failed check");

        return isSatisfied;
    }

    /**
     * Performs constraint checking on the result set in clientResponse. It does
     * simple sanity checks like if the response code is SUCCESS. If the check
     * transaction flag is set to true by calling setCheckTransaction(), then it
     * will check the result set against constraints.
     *
     * @param procName
     *            The name of the procedure
     * @param clientResponse
     *            The client response
     * @param errorExpected
     *            true if the response is expected to be an error.
     * @return true if it passes all tests, false otherwise
     */
    protected boolean checkTransaction(String procName,
                                       ClientResponse clientResponse,
                                       boolean abortExpected,
                                       boolean errorExpected) {
        final byte status = clientResponse.getStatus();
        if (status != ClientResponse.SUCCESS) {
            if (errorExpected)
                return true;

            if (abortExpected && status == ClientResponse.USER_ABORT)
                return true;

            if (status == ClientResponse.CONNECTION_LOST) {
                return false;
            }

            if (clientResponse.getException() != null) {
                clientResponse.getException().printStackTrace();
            }
            if (clientResponse.getExtra() != null) {
                System.err.println(clientResponse.getExtra());
            }

            System.exit(-1);
        }

        if (m_checkGenerator.nextFloat() >= m_checkTransaction)
            return true;

        return checkConstraints(procName, clientResponse);
    }

    /**
     * Sets the given constraint for the table identified by the tableId of
     * procedure 'name'. If there is already a constraint assigned to the table,
     * it is updated to the new one.
     *
     * @param name
     *            The name of the constraint. For transaction check, this should
     *            usually be the procedure name.
     * @param tableId
     *            The index of the table in the result set.
     * @param constraint
     *            The constraint to use.
     */
    protected void addConstraint(String name,
                                 int tableId,
                                 Expression constraint) {
        m_constraints.put(Pair.of(name, tableId), constraint);
    }

    protected void addTableConstraint(String name,
                                      Expression constraint) {
        addConstraint(name, 0, constraint);
        m_tableCheckOrder.add(name);
    }

    /**
     * Removes the constraint on the table identified by tableId of procedure
     * 'name'. Nothing happens if there is no constraint assigned to this table.
     *
     * @param name
     *            The name of the constraint.
     * @param tableId
     *            The index of the table in the result set.
     */
    protected void removeConstraint(String name, int tableId) {
        m_constraints.remove(Pair.of(name, tableId));
    }

    /**
     * Takes a snapshot of all the tables in the database now and check all the
     * rows in each table to see if they satisfy the constraints. The
     * constraints should be added with the table name and table id 0.
     *
     * Since the snapshot files reside on the servers, we have to copy them over
     * to the client in order to check. This might be an overkill, but the
     * alternative is to ask the user to write stored procedure for each table
     * and execute them on all nodes. That's not significantly better, either.
     *
     * This function blocks. Should only be run at the end.
     *
     * @return true if all tables passed the test, false otherwise.
     */
    protected boolean checkTables() {
        String dir = "/tmp";
        String nonce = "data_verification";
        Client client = ClientFactory.createClient(getExpectedOutgoingMessageSize(), null,
                                                   false, null);
        // Host ID to IP mappings
        LinkedHashMap<Integer, String> hostMappings = new LinkedHashMap<Integer, String>();
        /*
         *  The key is the table name. the first one in the pair is the hostname,
         *  the second one is file name
         */
        LinkedHashMap<String, Pair<String, String>> snapshotMappings =
            new LinkedHashMap<String, Pair<String, String>>();
        boolean isSatisfied = true;

        // Load the native library for loading table from snapshot file
        org.voltdb.EELibraryLoader.loadExecutionEngineLibrary(true);

        try {
            boolean keepTrying = true;
            VoltTable[] response = null;

            client.createConnection(m_host, m_username, m_password);
            // Only initiate the snapshot if it's the first client
            while (m_id == 0) {
                // Take a snapshot of the database. This call is blocking.
                response = client.callProcedure("@SnapshotSave", dir, nonce, 1);
                if (response.length != 1 || !response[0].advanceRow()
                    || !response[0].getString("RESULT").equals("SUCCESS")) {
                    if (keepTrying
                        && response[0].getString("ERR_MSG").contains("ALREADY EXISTS")) {
                        client.callProcedure("@SnapshotDelete",
                                             new String[] { dir },
                                             new String[] { nonce });
                        keepTrying = false;
                        continue;
                    }

                    System.err.println("Failed to take snapshot");
                    return false;
                }

                break;
            }

            // Clients other than the one that initiated the snapshot
            // have to check if the snapshot has completed
            if (m_id > 0) {
                int maxTry = 10;

                while (maxTry-- > 0) {
                    boolean found = false;
                    response = client.callProcedure("@SnapshotStatus");
                    if (response.length != 2) {
                        System.err.println("Failed to get snapshot status");
                        return false;
                    }
                    while (response[0].advanceRow()) {
                        if (response[0].getString("NONCE").equals(nonce)) {
                            found = true;
                            break;
                        }
                    }

                    if (found) {
                        // This probably means the snapshot is done
                        if (response[0].getLong("END_TIME") > 0)
                            break;
                    }

                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        return false;
                    }
                }
            }

            // Get host ID to hostname mappings
            response = client.callProcedure("@SystemInformation");
            if (response.length != 1) {
                System.err.println("Failed to get host ID to IP address mapping");
                return false;
            }
            while (response[0].advanceRow()) {
                if (!response[0].getString("key").equals("hostname"))
                    continue;
                hostMappings.put((Integer) response[0].get("node_id", VoltType.INTEGER),
                                 response[0].getString("value"));
            }

            // Do a scan to get all the file names and table names
            response = client.callProcedure("@SnapshotScan", dir);
            if (response.length != 3) {
                System.err.println("Failed to get snapshot filenames");
                return false;
            }

            // Only copy the snapshot files we just created
            while (response[0].advanceRow()) {
                if (!response[0].getString("NONCE").equals(nonce))
                    continue;

                String[] tables = response[0].getString("TABLES_REQUIRED").split(",");
                for (String t : tables)
                    snapshotMappings.put(t, null);
                break;
            }

            while (response[2].advanceRow()) {
                int id = Integer.parseInt(response[2].getString("HOST_ID"));
                String tableName = response[2].getString("TABLE");

                if (!snapshotMappings.containsKey(tableName) || !hostMappings.containsKey(id))
                    continue;

                snapshotMappings.put(tableName, Pair.of(hostMappings.get(id),
                                                        response[2].getString("NAME")));
            }
        } catch (NoConnectionsException e) {
            e.printStackTrace();
            return false;
        } catch (ProcCallException e) {
            e.printStackTrace();
            return false;
        } catch (UnknownHostException e) {
            e.printStackTrace();
            return false;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }

        // Iterate through all the tables
        for (String tableName : m_tableCheckOrder) {
            Pair<String, String> value = snapshotMappings.get(tableName);
            if (value == null)
                continue;

            String hostName = value.getFirst();
            File file = new File(dir, value.getSecond());
            FileInputStream inputStream = null;
            TableSaveFile saveFile = null;
            long rowCount = 0;

            Pair<String, Integer> key = Pair.of(tableName, 0);
            if (!m_constraints.containsKey(key) || hostName == null)
                continue;

            System.err.println("Checking table " + tableName);

            // Copy the file over
            String localhostName = null;
            try {
                localhostName = InetAddress.getLocalHost().getHostName();
            } catch (UnknownHostException e1) {
                localhostName = "localhost";
            }
            if (!hostName.equals("localhost") && !hostName.equals(localhostName)) {
                if (!SSHTools.copyFromRemote(file, m_username, hostName, file.getPath())) {
                    System.err.println("Failed to copy the snapshot file " + file.getPath()
                                       + " from host "
                                       + hostName);
                    return false;
                }
            }

            if (!file.exists()) {
                System.err.println("Snapshot file " + file.getPath()
                                   + " cannot be copied from "
                                   + hostName
                                   + " to localhost");
                return false;
            }

            try {
                try {
                    inputStream = new FileInputStream(file);
                    saveFile = new TableSaveFile(inputStream.getChannel(), 3, null);

                    // Get chunks from table
                    while (isSatisfied && saveFile.hasMoreChunks()) {
                        BBContainer chunk = saveFile.getNextChunk();
                        VoltTable table = null;

                        // This probably should not happen
                        if (chunk == null)
                            continue;

                        table = new VoltTable(chunk.b, true);
                        // Now, check each row
                        while (isSatisfied && table.advanceRow()) {
                            isSatisfied = Verification.checkRow(m_constraints.get(key),
                                                                table);
                            rowCount++;
                        }
                        // Release the memory of the chunk we just examined, be good
                        chunk.discard();
                    }
                } finally {
                    if (saveFile != null)
                        saveFile.close();
                    if (inputStream != null)
                        inputStream.close();
                    if (!hostName.equals("localhost") && !hostName.equals(localhostName)
                        && !file.delete())
                        System.err.println("Failed to delete snapshot file " + file.getPath());
                }
            } catch (FileNotFoundException e) {
                e.printStackTrace();
                return false;
            } catch (IOException e) {
                e.printStackTrace();
                return false;
            }

            if (isSatisfied) {
                System.err.println("Table " + tableName
                                   + " with "
                                   + rowCount
                                   + " rows passed check");
            } else {
                System.err.println("Table " + tableName + " failed check");
                break;
            }
        }

        // Clean up the snapshot we made
        try {
            if (m_id == 0) {
                client.callProcedure("@SnapshotDelete",
                                     new String[] { dir },
                                     new String[] { nonce });
            }
        } catch (NoConnectionsException e) {
            e.printStackTrace();
        } catch (ProcCallException e) {
            e.printStackTrace();
        }

        System.err.println("Table checking finished "
                           + (isSatisfied ? "successfully" : "with failures"));

        return isSatisfied;
    }
}
