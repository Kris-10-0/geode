/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.geode.internal.tcp;

import static java.lang.Integer.MAX_VALUE;
import static java.util.concurrent.TimeUnit.SECONDS;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.logging.log4j.Logger;

import org.apache.geode.SystemFailure;
import org.apache.geode.alerting.internal.spi.AlertingAction;
import org.apache.geode.annotations.VisibleForTesting;
import org.apache.geode.annotations.internal.MakeNotStatic;
import org.apache.geode.distributed.DistributedMember;
import org.apache.geode.distributed.DistributedSystemDisconnectedException;
import org.apache.geode.distributed.internal.DistributionManager;
import org.apache.geode.distributed.internal.InternalDistributedSystem;
import org.apache.geode.distributed.internal.membership.InternalDistributedMember;
import org.apache.geode.distributed.internal.membership.api.MemberIdentifier;
import org.apache.geode.distributed.internal.membership.api.Membership;
import org.apache.geode.internal.Assert;
import org.apache.geode.internal.SystemTimer;
import org.apache.geode.internal.lang.utils.JavaWorkarounds;
import org.apache.geode.internal.logging.CoreLoggingExecutors;
import org.apache.geode.internal.net.BufferPool;
import org.apache.geode.internal.net.SocketCloser;
import org.apache.geode.logging.internal.executors.LoggingExecutors;
import org.apache.geode.logging.internal.log4j.api.LogService;

/**
 * ConnectionTable holds all of the Connection objects in a conduit. Connections represent a pipe
 * between two endpoints represented by generic DistributedMembers.
 *
 * @since GemFire 2.1
 */
public class ConnectionTable {
  private static final Logger logger = LogService.getLogger();

  /**
   * warning when descriptor limit reached
   */
  @MakeNotStatic
  private static boolean ulimitWarningIssued;

  /**
   * true if the current thread wants non-shared resources
   */
  private static final ThreadLocal<Boolean> threadWantsOwnResources = new ThreadLocal<>();

  /**
   * Used for messages whose order must be preserved Only connections used for sending messages, and
   * receiving acks, will be put in this map.
   * Value may be {@link Connection} or {@link PendingConnection}.
   */
  private final Map<DistributedMember, Object> orderedConnectionMap = new ConcurrentHashMap<>();

  /**
   * ordered connections local to this thread.
   */
  static final ThreadLocal<Map<DistributedMember, Connection>> threadOrderedConnMap =
      new ThreadLocal<>();

  /**
   * Timer to kill idle threads. Guarded by this.
   */
  private SystemTimer idleConnTimer;

  /**
   * Used to find connections owned by threads. The key is the same one used in
   * threadOrderedConnMap. The value is an ArrayList since we can have any number of connections
   * with the same key.
   */
  private final ConcurrentMap<DistributedMember, List<Connection>> threadConnectionMap;

  /**
   * Used for all non-ordered messages. Only connections used for sending messages, and receiving
   * acks, will be put in this map.
   */
  private final Map<DistributedMember, Object> unorderedConnectionMap = new ConcurrentHashMap<>();

  /**
   * Used for all accepted connections. These connections are read only; we never send messages,
   * except for acks; only receive. Consists of a list of Connection.
   */
  private final List<Connection> receivers = new ArrayList<>();

  /**
   * the conduit for this table
   */
  private final TCPConduit owner;

  private final BufferPool bufferPool;

  /**
   * true if this table is no longer in use
   */
  private volatile boolean closed;

  /**
   * Executor used by p2p reader and p2p handshaker threads.
   */
  private final Executor p2pReaderThreadPool;
  /**
   * Number of seconds to wait before timing out an unused p2p reader thread. Default is 120 (2
   * minutes).
   */
  private static final long READER_POOL_KEEP_ALIVE_TIME =
      Long.getLong("p2p.READER_POOL_KEEP_ALIVE_TIME", 120);

  private final SocketCloser socketCloser;

  /**
   * The most recent instance to be created
   *
   * <p>
   * TODO this assumes no more than one instance is created at a time?
   */
  @MakeNotStatic
  private static final AtomicReference<ConnectionTable> lastInstance = new AtomicReference<>();

  /**
   * A set of sockets that are in the process of being connected
   */
  private final Map<Socket, ConnectingSocketInfo> connectingSockets = new HashMap<>();

  /**
   * Cause calling thread to share communication resources with other threads.
   */
  public static void threadWantsSharedResources() {
    threadWantsOwnResources.set(Boolean.FALSE);
  }

  /**
   * Cause calling thread to acquire exclusive access to communication resources. Exclusive access
   * may not be available in which case this call is ignored.
   */
  public static void threadWantsOwnResources() {
    threadWantsOwnResources.set(Boolean.TRUE);
  }

  /**
   * Returns true if calling thread owns its own communication resources.
   */
  private boolean threadOwnsResources() {
    DistributionManager d = getDM();
    if (d != null) {
      return d.getSystem().threadOwnsResources() && !AlertingAction.isThreadAlerting();
    }
    return false;
  }

  public static Boolean getThreadOwnsResourcesRegistration() {
    return threadWantsOwnResources.get();
  }

  public TCPConduit getOwner() {
    return owner;
  }

  public static ConnectionTable create(TCPConduit conduit) {
    ConnectionTable ct = new ConnectionTable(conduit);
    lastInstance.set(ct);
    return ct;
  }

  private ConnectionTable(TCPConduit conduit) {
    owner = conduit;
    idleConnTimer = owner.idleConnectionTimeout != 0
        ? new SystemTimer(conduit.getDM().getSystem()) : null;
    threadConnectionMap = new ConcurrentHashMap<>();
    p2pReaderThreadPool = createThreadPoolForIO(conduit.getDM().getSystem().isShareSockets());
    socketCloser = new SocketCloser();
    bufferPool = conduit.getBufferPool();
  }

  private Executor createThreadPoolForIO(boolean conserveSockets) {
    if (conserveSockets) {
      return LoggingExecutors.newThreadOnEachExecute("SharedP2PReader");
    }
    return CoreLoggingExecutors.newThreadPoolWithSynchronousFeed(1, MAX_VALUE,
        READER_POOL_KEEP_ALIVE_TIME, SECONDS, "UnsharedP2PReader");
  }

  /** conduit calls acceptConnection after an accept */
  void acceptConnection(Socket sock, PeerConnectionFactory peerConnectionFactory)
      throws IOException, ConnectionException {
    InetAddress connAddress = sock.getInetAddress();
    boolean finishedConnecting = false;
    Connection connection = null;
    try {
      connection = peerConnectionFactory.createReceiver(this, sock);

      // check for shutdown (so it doesn't get missed in the finally block)
      owner.getCancelCriterion().checkCancelInProgress(null);
      finishedConnecting = true;
    } catch (ConnectionException | IOException ex) {
      // check for shutdown...
      owner.getCancelCriterion().checkCancelInProgress(ex);
      logger.warn("Failed to accept connection from {} because: {}",
          connAddress != null ? connAddress : "unavailable address", ex);
      throw ex;
    } finally {
      // note: no need to call incFailedAccept here because it will be done in our caller.
      // no need to log error here since caller will log warning

      if (connection != null && !finishedConnecting) {
        // we must be throwing from checkCancelInProgress so close the connection
        closeCon("cancel after accept", connection);
        connection = null;
      }
    }

    if (connection != null) {
      synchronized (receivers) {
        owner.getStats().incReceivers();
        if (closed) {
          closeCon("Connection table no longer in use", connection);
          return;
        }
        // If connection.stopped is false, any connection cleanup thread will not yet have acquired
        // the receiver synchronization to remove the receiver. Therefore we can safely add it here.
        if (!(connection.isSocketClosed() || connection.isReceiverStopped())) {
          receivers.add(connection);
        }
      }
      if (logger.isDebugEnabled()) {
        logger.debug("Accepted {} myAddr={} theirAddr={}", connection, getConduit().getMemberId(),
            connection.getRemoteAddress());
      }
    }
  }

  /**
   * Process a newly created PendingConnection
   *
   * @param id DistributedMember on which the connection is created
   * @param sharedResource whether the connection is used by multiple threads
   * @param preserveOrder whether to preserve order
   * @param m map to add the connection to
   * @param pc the PendingConnection to process
   * @param startTime the ms clock start time for the operation
   * @param ackThreshold the ms ack-wait-threshold, or zero
   * @param ackSAThreshold the ms ack-severe_alert-threshold, or zero
   * @param doNotRetry whether we should perform reattempt to create connection
   * @return the Connection, or null if someone else already created or closed it
   * @throws IOException if unable to connect
   */
  private Connection handleNewPendingConnection(InternalDistributedMember id,
      boolean sharedResource,
      boolean preserveOrder, Map<DistributedMember, Object> m, PendingConnection pc, long startTime,
      long ackThreshold,
      long ackSAThreshold, boolean doNotRetry)
      throws IOException, DistributedSystemDisconnectedException {
    // handle new pending connection
    Connection con = null;
    try {
      long senderCreateStartTime = owner.getStats().startSenderCreate();
      con = Connection.createSender(owner.getMembership(), this, preserveOrder, id,
          sharedResource, startTime, ackThreshold, ackSAThreshold, doNotRetry);
      owner.getStats().incSenders(sharedResource, preserveOrder, senderCreateStartTime);
    } finally {
      // our connection failed to notify anyone waiting for our pending con
      if (con == null) {
        owner.getStats().incFailedConnect();
        synchronized (m) {
          Object rmObj = m.remove(id);
          if (rmObj != pc && rmObj != null) {
            // put it back since it was not our pc
            m.put(id, rmObj);
          }
        }
        pc.notifyWaiters(null);
        // we must be throwing an exception
      }
    }

    // Update our list of connections -- either the orderedConnectionMap or unorderedConnectionMap
    //
    // Note that we added the entry _before_ we attempted the connect,
    // so it's possible something else got through in the mean time...
    synchronized (m) {
      Object e = m.get(id);
      if (e == pc) {
        m.put(id, con);
      } else if (e == null) {
        // someone closed our pending connection so cleanup the connection we created
        con.requestClose("pending connection cancelled");
        con = null;
      } else {
        if (e instanceof Connection) {
          Connection newCon = (Connection) e;
          if (!newCon.connected) {
            // someone closed our pending connect so cleanup the connection we created
            if (con != null) {
              con.requestClose("pending connection closed");
              con = null;
            }
          } else {
            // This should not happen. It means that someone else created the connection which
            // should only happen if our Connection was rejected.
            if (con != null) {
              con.requestClose("someone else created the connection");
            }
            con = newCon;
          }
        }
      }
    }
    pc.notifyWaiters(con);
    if (con != null && logger.isDebugEnabled()) {
      logger.debug("handleNewPendingConnection {} myAddr={} theirAddr={}", con,
          getConduit().getMemberId(), con.getRemoteAddress());
    }

    return con;
  }

  /**
   * unordered or conserve-sockets=true note that unordered connections are currently always shared
   *
   * @param id the DistributedMember on which we are creating a connection
   * @param scheduleTimeout whether unordered connection should time out
   * @param preserveOrder whether to preserve order
   * @param startTime the ms clock start time for the operation
   * @param ackTimeout the ms ack-wait-threshold, or zero
   * @param ackSATimeout the ms ack-severe-alert-threshold, or zero
   * @param doNotRetryWaitForConnection whether we should perform reattempt (or wait) to create
   *        connection
   * @return the new Connection, or null if an error
   * @throws IOException if unable to create the connection
   */
  private Connection getSharedConnection(InternalDistributedMember id, boolean scheduleTimeout,
      boolean preserveOrder, long startTime, long ackTimeout, long ackSATimeout,
      boolean doNotRetryWaitForConnection)
      throws IOException, DistributedSystemDisconnectedException {

    final Map<DistributedMember, Object> m =
        preserveOrder ? orderedConnectionMap : unorderedConnectionMap;

    // new connection, if needed
    PendingConnection pc = null;

    // existing connection (if we don't create a new one)
    Object mEntry;

    // Look for pending connection
    synchronized (m) {
      mEntry = m.get(id);
      if (mEntry instanceof Connection) {
        Connection existingCon = (Connection) mEntry;
        if (!existingCon.connected) {
          mEntry = null;
        }
      }
      if (mEntry == null) {
        pc = new PendingConnection(preserveOrder, id);
        m.put(id, pc);
      }
    }

    Connection result;
    if (pc != null) {
      if (logger.isDebugEnabled()) {
        logger.debug("created PendingConnection {}", pc);
      }
      result = handleNewPendingConnection(id, true, preserveOrder, m, pc,
          startTime, ackTimeout, ackSATimeout, doNotRetryWaitForConnection);
      if (!preserveOrder && scheduleTimeout) {
        scheduleIdleTimeout(result);
      }

    } else { // we have existing connection
      if (mEntry instanceof PendingConnection) {

        if (AlertingAction.isThreadAlerting()) {
          // do not change the text of this exception - it is looked for in exception handlers
          throw new IOException("Cannot form connection to alert listener " + id);
        }

        if (doNotRetryWaitForConnection) {
          return null;
        }

        result = ((PendingConnection) mEntry).waitForConnect(owner.getMembership(),
            startTime, ackTimeout, ackSATimeout);
        if (logger.isDebugEnabled()) {
          if (result != null) {
            logger.debug("getSharedConnection {} myAddr={} theirAddr={}", result,
                getConduit().getMemberId(), result.getRemoteAddress());
          } else {
            logger.debug("getSharedConnection: Connect failed");
          }
        }
      } else {
        result = (Connection) mEntry;
      }
    } // we have existing connection

    return result;
  }

  /**
   * Must be looking for an ordered connection that this thread owns
   *
   * @param id stub on which to create the connection
   * @param startTime the ms clock start time for the operation
   * @param ackTimeout the ms ack-wait-threshold, or zero
   * @param ackSATimeout the ms ack-severe-alert-threshold, or zero
   * @param doNotRetry whether we should perform reattempt to create connection
   * @return the connection, or null if an error
   * @throws IOException if the connection could not be created
   */
  Connection getThreadOwnedConnection(InternalDistributedMember id, long startTime, long ackTimeout,
      long ackSATimeout, boolean doNotRetry)
      throws IOException, DistributedSystemDisconnectedException {
    Connection result;

    // Look for result in the thread local
    Map<DistributedMember, Connection> m = threadOrderedConnMap.get();
    if (m == null) {
      // First time for this thread. Create thread local
      m = new HashMap<>();
      threadOrderedConnMap.set(m);
    } else {
      // No need to sync map since it is only referenced by ThreadLocal.
      result = m.get(id);
      if (result != null && !result.timedOut) {
        return result;
      }
    }

    // OK, we have to create a new connection.
    long senderCreateStartTime = owner.getStats().startSenderCreate();
    result = Connection.createSender(owner.getMembership(), this, true, id, false, startTime,
        ackTimeout, ackSATimeout, doNotRetry);
    owner.getStats().incSenders(false, true, senderCreateStartTime);
    if (logger.isDebugEnabled()) {
      logger.debug("ConnectionTable: created an ordered connection: {}", result);
    }

    // Update the list of connections owned by this thread....

    final List<Connection> al =
        JavaWorkarounds.computeIfAbsent(threadConnectionMap, id, k -> new ArrayList<>());

    // Add our Connection to the list
    synchronized (al) {
      al.add(result);
    }

    // Finally, add the connection to our thread local map.
    // No need to sync map since it is only referenced by ThreadLocal.
    m.put(id, result);

    scheduleIdleTimeout(result);
    return result;
  }

  /** schedule an idle-connection timeout task */
  private void scheduleIdleTimeout(Connection conn) {
    if (conn == null) {
      return;
    }
    // Set the idle timeout
    if (owner.idleConnectionTimeout != 0) {
      try {
        synchronized (this) {
          if (!closed) {
            IdleConnTT task = new IdleConnTT(conn);
            conn.setIdleTimeoutTask(task);
            synchronized (task) {
              if (!task.isCancelled()) {
                getIdleConnTimer().scheduleAtFixedRate(task, owner.idleConnectionTimeout,
                    owner.idleConnectionTimeout);
              }
            }
          }
        }
      } catch (IllegalStateException e) {
        if (conn.isClosing()) {
          // connection is closed before we schedule the timeout task,
          // causing the task to be canceled
          return;
        }
        logger.debug("Got an illegal state exception: {}", e.getMessage(), e);
        // Unfortunately, cancelInProgress() is not set until *after* the shutdown message has been
        // sent, so we need to check the "closeInProgress" bit instead.
        owner.getCancelCriterion().checkCancelInProgress(null);
        Throwable cause = owner.getShutdownCause();
        if (cause == null) {
          cause = e;
        }
        throw new DistributedSystemDisconnectedException("The distributed system is shutting down",
            cause);
      }
    }
  }

  /**
   * Get a new connection
   *
   * @param id the DistributedMember on which to create the connection
   * @param preserveOrder whether order should be preserved
   * @param startTime the ms clock start time
   * @param ackTimeout the ms ack-wait-threshold, or zero
   * @param ackSATimeout the ms ack-severe-alert-threshold, or zero
   * @param doNotRetry whether we should perform reattempt to create connection
   * @return the new Connection, or null if a problem
   * @throws IOException if the connection could not be created
   */
  protected Connection get(InternalDistributedMember id, boolean preserveOrder, long startTime,
      long ackTimeout, long ackSATimeout, boolean doNotRetry)
      throws IOException, DistributedSystemDisconnectedException {
    if (closed) {
      owner.getCancelCriterion().checkCancelInProgress(null);
      throw new DistributedSystemDisconnectedException("Connection table is closed");
    }
    Connection result;
    boolean threadOwnsResources = threadOwnsResources();
    if (!preserveOrder || !threadOwnsResources) {
      result = getSharedConnection(id, threadOwnsResources, preserveOrder, startTime, ackTimeout,
          ackSATimeout, doNotRetry);
    } else {
      result = getThreadOwnedConnection(id, startTime, ackTimeout, ackSATimeout, doNotRetry);
    }
    if (result != null) {
      Assert.assertTrue(result.getPreserveOrder() == preserveOrder);
    }
    return result;
  }

  synchronized void fileDescriptorsExhausted() {
    if (!ulimitWarningIssued) {
      ulimitWarningIssued = true;
      logger.fatal(
          "This process is out of file descriptors.This will hamper communications and slow down the system.Any conserve-sockets setting is now being ignored.Please consider raising the descriptor limit.This alert is only issued once per process.");
      InternalDistributedSystem.getAnyInstance().setShareSockets(true);
    }
  }

  TCPConduit getConduit() {
    return owner;
  }

  BufferPool getBufferPool() {
    return bufferPool;
  }

  public boolean isClosed() {
    return closed;
  }

  private static void closeCon(String reason, Object c) {
    closeCon(reason, c, false);
  }

  private static void closeCon(String reason, Object c, boolean beingSick) {
    if (c == null) {
      return;
    }
    if (c instanceof Connection) {
      ((Connection) c).closePartialConnect(reason, beingSick);
    } else {
      ((PendingConnection) c).notifyWaiters(null);
    }
  }

  /**
   * returns the idle connection timer, or null if the connection table is closed. guarded by a sync
   * on the connection table
   */
  synchronized SystemTimer getIdleConnTimer() {
    if (closed) {
      return null;
    }
    if (idleConnTimer == null) {
      idleConnTimer = new SystemTimer(getDM().getSystem());
    }
    return idleConnTimer;
  }

  protected void close() {
    if (closed) {
      return;
    }
    closed = true;
    synchronized (this) {
      if (idleConnTimer != null) {
        idleConnTimer.cancel();
      }
    }
    synchronized (orderedConnectionMap) {
      for (Object o : orderedConnectionMap.values()) {
        closeCon("Connection table being destroyed", o);
      }
      orderedConnectionMap.clear();
    }
    synchronized (unorderedConnectionMap) {
      for (Object o : unorderedConnectionMap.values()) {
        closeCon("Connection table being destroyed", o);
      }
      unorderedConnectionMap.clear();
    }
    if (threadConnectionMap != null) {
      synchronized (threadConnectionMap) {
        for (final List<Connection> connections : threadConnectionMap.values()) {
          synchronized (connections) {
            for (Object o : connections) {
              closeCon("Connection table being destroyed", o);
            }
          }
        }
        threadConnectionMap.clear();
      }
    }
    Executor localExec = p2pReaderThreadPool;
    if (localExec != null) {
      if (localExec instanceof ExecutorService) {
        ((ExecutorService) localExec).shutdown();
      }
    }
    closeReceivers(false);

    Map<DistributedMember, Connection> map = threadOrderedConnMap.get();
    if (map != null) {
      // No need to synchronize map since it is only referenced by ThreadLocal.
      map.clear();
    }
    socketCloser.close();
  }

  public void executeCommand(Runnable runnable) {
    Executor local = p2pReaderThreadPool;
    if (local != null) {
      local.execute(runnable);
    }
  }

  /**
   * Close all receiving threads. This is used during shutdown and is also used by a test hook that
   * makes us deaf to incoming messages.
   *
   * @param beingSick a test hook to simulate a sick process
   */
  void closeReceivers(boolean beingSick) {
    synchronized (receivers) {
      for (Iterator<Connection> it = receivers.iterator(); it.hasNext();) {
        Connection con = it.next();
        if (!beingSick || con.getPreserveOrder()) {
          closeCon("Connection table being destroyed", con, beingSick);
          it.remove();
        }
      }
      // now close any sockets being formed
      synchronized (connectingSockets) {
        for (final Iterator<Map.Entry<Socket, ConnectingSocketInfo>> it =
            connectingSockets.entrySet().iterator(); it.hasNext();) {
          final Map.Entry<Socket, ?> entry = it.next();
          try {
            entry.getKey().close();
          } catch (IOException e) {
            // ignored - we're shutting down
          }
          it.remove();
        }
      }
    }
  }

  void removeReceiver(Connection con) {
    synchronized (receivers) {
      receivers.remove(con);
    }
  }


  /**
   * remove an endpoint and notify the membership manager of the departure
   */
  protected void removeEndpoint(DistributedMember stub, String reason) {
    removeEndpoint(stub, reason, true);
  }

  void removeEndpoint(DistributedMember memberID, String reason, boolean notifyDisconnect) {
    if (closed) {
      return;
    }
    boolean needsRemoval = false;
    synchronized (orderedConnectionMap) {
      if (orderedConnectionMap.get(memberID) != null) {
        needsRemoval = true;
      }
    }
    if (!needsRemoval) {
      synchronized (unorderedConnectionMap) {
        if (unorderedConnectionMap.get(memberID) != null) {
          needsRemoval = true;
        }
      }
    }
    if (!needsRemoval) {
      final ConcurrentMap<DistributedMember, List<Connection>> cm = threadConnectionMap;
      if (cm != null) {
        final List<Connection> al = cm.get(memberID);
        needsRemoval = al != null && !al.isEmpty();
      }
    }

    if (needsRemoval) {
      InternalDistributedMember remoteAddress = null;
      synchronized (orderedConnectionMap) {
        Object c = orderedConnectionMap.remove(memberID);
        if (c instanceof Connection) {
          remoteAddress = ((Connection) c).getRemoteAddress();
        }
        closeCon(reason, c);
      }
      synchronized (unorderedConnectionMap) {
        Object c = unorderedConnectionMap.remove(memberID);
        if (remoteAddress == null && c instanceof Connection) {
          remoteAddress = ((Connection) c).getRemoteAddress();
        }
        closeCon(reason, c);
      }

      final ConcurrentMap<DistributedMember, List<Connection>> cm = threadConnectionMap;
      if (cm != null) {
        final List<Connection> al = cm.remove(memberID);
        if (al != null) {
          synchronized (al) {
            for (Connection c : al) {
              if (remoteAddress == null && c != null) {
                remoteAddress = c.getRemoteAddress();
              }
              closeCon(reason, c);
            }
            al.clear();
          }
        }
      }

      // close any sockets that are in the process of being connected
      final Set<Socket> toRemove = new HashSet<>();
      synchronized (connectingSockets) {
        for (final Iterator<Map.Entry<Socket, ConnectingSocketInfo>> it =
            connectingSockets.entrySet().iterator(); it.hasNext();) {
          final Map.Entry<Socket, ConnectingSocketInfo> entry = it.next();
          final ConnectingSocketInfo info = entry.getValue();
          if (info.peerAddress.equals(((MemberIdentifier) memberID).getInetAddress())) {
            toRemove.add(entry.getKey());
            it.remove();
          }
        }
      }

      for (final Socket sock : toRemove) {
        try {
          sock.close();
        } catch (IOException e) {
          if (logger.isDebugEnabled()) {
            logger.debug("caught exception while trying to close connecting socket for {}",
                memberID, e);
          }
        }
      }

      // close any receivers
      // avoid deadlock when a NIC has failed by closing connections outside of the receivers sync
      final Set<Connection> connectionsToClose = new HashSet<>();
      synchronized (receivers) {
        for (final Iterator<Connection> it = receivers.iterator(); it.hasNext();) {
          final Connection con = it.next();
          if (memberID.equals(con.getRemoteAddress())) {
            it.remove();
            connectionsToClose.add(con);
          }
        }
      }
      for (final Connection con : connectionsToClose) {
        closeCon(reason, con);
      }
      if (notifyDisconnect) {
        if (owner.getDM().shutdownInProgress()) {
          throw new DistributedSystemDisconnectedException("Shutdown in progress",
              owner.getDM().getDistribution().getShutdownCause());
        }
      }

      if (remoteAddress != null) {
        socketCloser.releaseResourcesForAddress(remoteAddress.toString());
      }
    }
  }

  SocketCloser getSocketCloser() {
    return socketCloser;
  }

  /** check to see if there are still any receiver threads for the given end-point */
  boolean hasReceiversFor(DistributedMember endPoint) {
    synchronized (receivers) {
      for (Connection receiver : receivers) {
        if (endPoint.equals(receiver.getRemoteAddress())) {
          return true;
        }
      }
    }
    return false;
  }

  private static void removeFromThreadConMap(
      final ConcurrentMap<DistributedMember, List<Connection>> cm, final DistributedMember stub,
      final Connection c) {
    if (cm != null) {
      List<Connection> al = cm.get(stub);
      if (al != null) {
        synchronized (al) {
          al.remove(c);
        }
      }
    }
  }

  void removeThreadConnection(final DistributedMember stub, final Connection c) {
    removeFromThreadConMap(threadConnectionMap, stub, c);
    final Map<DistributedMember, Connection> m = threadOrderedConnMap.get();
    if (m != null) {
      // No need to synchronize map since it is only referenced by ThreadLocal.
      if (m.get(stub) == c) {
        m.remove(stub);
      }
    }
  }

  void removeSharedConnection(String reason, DistributedMember stub, boolean ordered,
      Connection c) {
    if (closed) {
      return;
    }
    if (ordered) {
      synchronized (orderedConnectionMap) {
        if (orderedConnectionMap.get(stub) == c) {
          closeCon(reason, orderedConnectionMap.remove(stub));
        }
      }
    } else {
      synchronized (unorderedConnectionMap) {
        if (unorderedConnectionMap.get(stub) == c) {
          closeCon(reason, unorderedConnectionMap.remove(stub));
        }
      }
    }
  }

  @VisibleForTesting
  public static long getNumSenderSharedConnections() {
    ConnectionTable ct = lastInstance.get();
    if (ct == null) {
      return 0;
    }
    return (ct.getConduit().getStats().getSendersSU());
  }



  /**
   * Clears lastInstance. Does not yet close underlying sockets, but probably not strictly
   * necessary.
   *
   * @see SystemFailure#emergencyClose()
   */
  public static void emergencyClose() {
    ConnectionTable ct = lastInstance.get();
    if (ct == null) {
      return;
    }
    lastInstance.set(null);
  }

  void removeAndCloseThreadOwnedSockets() {
    Map<DistributedMember, Connection> m = threadOrderedConnMap.get();
    if (m != null) {
      // No need to synchronize map since it is only referenced by ThreadLocal.
      for (final Iterator<Map.Entry<DistributedMember, Connection>> it =
          m.entrySet().iterator(); it.hasNext();) {
        final Map.Entry<DistributedMember, Connection> me = it.next();
        DistributedMember stub = me.getKey();
        Connection c = me.getValue();
        removeFromThreadConMap(threadConnectionMap, stub, c);
        it.remove();
        closeCon("thread finalization", c);
      }
    }
  }

  public static void releaseThreadsSockets() {
    ConnectionTable ct = lastInstance.get();
    if (ct == null) {
      return;
    }
    ct.removeAndCloseThreadOwnedSockets();
  }

  /**
   * records the current outgoing message count on all thread-owned ordered connections. This does
   * not synchronize or stop new connections from being formed or new messages from being sent
   *
   * @since GemFire 5.1
   */
  void getThreadOwnedOrderedConnectionState(final DistributedMember member,
      final Map<Long, Long> result) {
    if (threadConnectionMap != null) {
      List<Connection> al = threadConnectionMap.get(member);
      if (al != null) {
        synchronized (al) {
          al = new ArrayList<>(al);
        }

        for (final Connection connection : al) {
          if (!connection.isSharedResource() && connection.getOriginatedHere()
              && connection.getPreserveOrder()) {
            result.put(connection.getUniqueId(), connection.getMessagesSent());
          }
        }
      }
    }
  }

  /**
   * wait for the given incoming connections to receive at least the associated number of messages
   */
  void waitForThreadOwnedOrderedConnectionState(final DistributedMember member,
      final Map<Long, Long> connectionStates)
      throws InterruptedException {
    if (Thread.interrupted()) {
      // wisest to do this before the synchronize below
      throw new InterruptedException();
    }
    List<Connection> r;
    synchronized (receivers) {
      r = new ArrayList<>(receivers);
    }
    for (Connection con : r) {
      if (!con.stopped && !con.isClosing() && !con.getOriginatedHere() && con.getPreserveOrder()
          && member.equals(con.getRemoteAddress())) {
        final Long state = connectionStates.remove(con.getUniqueId());
        if (state != null) {
          long count = state;
          while (!con.stopped && !con.isClosing() && con.getMessagesReceived() < count) {
            if (logger.isDebugEnabled()) {
              logger.debug("Waiting for connection {}/{} currently={} need={}",
                  con.getRemoteAddress(), con.getUniqueId(), con.getMessagesReceived(), count);
            }
            Thread.sleep(100);
          }
        }
      }
    }
    if (!connectionStates.isEmpty()) {
      if (logger.isDebugEnabled()) {
        StringBuilder sb = new StringBuilder(1000);
        sb.append("These connections from ");
        sb.append(member);
        sb.append("could not be located during waitForThreadOwnedOrderedConnectionState: ");
        for (Iterator<Map.Entry<Long, Long>> it = connectionStates.entrySet().iterator(); it
            .hasNext();) {
          Map.Entry<Long, Long> entry = it.next();
          sb.append(entry.getKey()).append('(').append(entry.getValue()).append(')');
          if (it.hasNext()) {
            sb.append(',');
          }
        }
        logger.debug(sb);
      }
    }
  }

  protected DistributionManager getDM() {
    return owner.getDM();
  }

  /** keep track of a socket that is trying to connect() for shutdown purposes */
  void addConnectingSocket(Socket socket, InetAddress addr) {
    synchronized (connectingSockets) {
      connectingSockets.put(socket, new ConnectingSocketInfo(addr));
    }
  }

  /** remove a socket from the tracked set. It should be connected at this point */
  void removeConnectingSocket(Socket socket) {
    synchronized (connectingSockets) {
      connectingSockets.remove(socket);
    }
  }

  int getNumberOfReceivers() {
    return receivers.size();
  }

  private class PendingConnection {

    /**
     * true if this connection is still pending
     */
    private boolean pending = true;

    /**
     * the connection we are waiting on
     */
    private Connection conn;

    /**
     * whether the connection preserves message ordering
     */
    private final boolean preserveOrder;

    /**
     * the stub we are connecting to
     */
    private final DistributedMember id;

    private final Thread connectingThread;

    private PendingConnection(boolean preserveOrder, DistributedMember id) {
      this.preserveOrder = preserveOrder;
      this.id = id;
      connectingThread = Thread.currentThread();
    }

    /**
     * Synchronously set the connection and notify waiters that we are ready.
     *
     * @param c the new connection
     */
    private synchronized void notifyWaiters(Connection c) {
      if (!pending) {
        return;
      }

      conn = c;
      pending = false;
      if (logger.isDebugEnabled()) {
        logger.debug("Notifying waiters that pending {} connection to {} is ready; {}",
            preserveOrder ? "ordered" : "unordered", id, this);
      }
      notifyAll();
    }

    /**
     * Wait for a connection
     *
     * @param mgr the membership manager that can instigate suspect processing if necessary
     * @param startTime the ms clock start time for the operation
     * @param ackTimeout the ms ack-wait-threshold, or zero
     * @param ackSATimeout the ms ack-severe-alert-threshold, or zero
     * @return the new connection
     */
    private synchronized Connection waitForConnect(Membership<InternalDistributedMember> mgr,
        long startTime, long ackTimeout,
        long ackSATimeout) {
      if (connectingThread == Thread.currentThread()) {
        throw new ReenteredConnectException("This thread is already trying to connect");
      }

      final Map<DistributedMember, Object> m =
          preserveOrder ? orderedConnectionMap : unorderedConnectionMap;

      DistributedMember targetMember = null;
      if (ackSATimeout > 0) {
        targetMember = id;
      }

      boolean suspected = false;
      boolean severeAlertIssued = false;
      for (int attempt = 0;;) {
        if (!pending) {
          break;
        }
        getConduit().getCancelCriterion().checkCancelInProgress(null);

        // wait a little bit...
        boolean interrupted = Thread.interrupted();
        try {
          wait(100);
        } catch (InterruptedException e) {
          interrupted = true;
          getConduit().getCancelCriterion().checkCancelInProgress(e);
        } finally {
          if (interrupted) {
            Thread.currentThread().interrupt();
          }
        }

        if (!pending) {
          break;
        }

        // Still pending...
        long now = System.currentTimeMillis();
        if (!severeAlertIssued && ackSATimeout > 0 && startTime + ackTimeout < now) {
          if (startTime + ackTimeout + ackSATimeout < now) {
            if (targetMember != null) {
              logger.fatal("Unable to form a TCP/IP connection to {} in over {} seconds",
                  targetMember, (ackSATimeout + ackTimeout) / 1000);
            }
            severeAlertIssued = true;
          } else if (!suspected) {
            logger.warn("Unable to form a TCP/IP connection to {} in over {} seconds",
                id, ackTimeout / 1000);
            mgr.suspectMember((InternalDistributedMember) targetMember,
                "Unable to form a TCP/IP connection in a reasonable amount of time");
            suspected = true;
          }
        }

        Object e = m.get(id);
        if (e == this) {
          attempt += 1;
          if (logger.isDebugEnabled() && attempt % 20 == 1) {
            logger.debug("Waiting for pending connection to complete: {} connection to {}; {}",
                preserveOrder ? "ordered" : "unordered", id, this);
          }
          continue;
        }

        // Odd state change. Process and exit.
        if (logger.isDebugEnabled()) {
          logger.debug("Pending connection changed to {} unexpectedly", e);
        }

        if (e == null) {
          // We were removed
          notifyWaiters(null);
          break;
        }
        if (e instanceof Connection) {
          notifyWaiters((Connection) e);
          break;
        }
        // defer to the new instance
        return ((PendingConnection) e).waitForConnect(mgr, startTime, ackTimeout, ackSATimeout);

      }
      return conn;
    }

    public String toString() {
      return super.toString() + " created by " + connectingThread.getName();
    }
  }

  private static class IdleConnTT extends SystemTimer.SystemTimerTask {

    private Connection connection;

    private IdleConnTT(Connection c) {
      connection = c;
    }

    @Override
    public boolean cancel() {
      Connection con = connection;
      if (con != null) {
        con.cleanUpOnIdleTaskCancel();
      }
      connection = null;
      return super.cancel();
    }

    @Override
    public void run2() {
      Connection con = connection;
      if (con != null) {
        if (con.checkForIdleTimeout()) {
          cancel();
        }
      }
    }
  }

  private static class ConnectingSocketInfo {

    private final InetAddress peerAddress;

    private ConnectingSocketInfo(InetAddress addr) {
      peerAddress = addr;
    }
  }
}
