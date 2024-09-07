/*
 * Copyright 2016-present Open Networking Foundation
 * Copyright © 2020 camunda services GmbH (info@camunda.com)
 *
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
package io.atomix.raft.partition.impl;

import static org.slf4j.LoggerFactory.getLogger;

import io.atomix.cluster.ClusterMembershipService;
import io.atomix.cluster.MemberId;
import io.atomix.cluster.messaging.ClusterCommunicationService;
import io.atomix.primitive.PrimitiveTypeRegistry;
import io.atomix.primitive.partition.Partition;
import io.atomix.raft.RaftCommitListener;
import io.atomix.raft.RaftRoleChangeListener;
import io.atomix.raft.RaftServer;
import io.atomix.raft.RaftServer.Role;
import io.atomix.raft.partition.RaftCompactionConfig;
import io.atomix.raft.partition.RaftPartition;
import io.atomix.raft.partition.RaftPartitionGroupConfig;
import io.atomix.raft.partition.RaftStorageConfig;
import io.atomix.raft.roles.RaftRole;
import io.atomix.raft.storage.RaftStorage;
import io.atomix.raft.storage.log.RaftLogReader;
import io.atomix.raft.storage.snapshot.SnapshotStore;
import io.atomix.raft.zeebe.ZeebeLogAppender;
import io.atomix.storage.StorageException;
import io.atomix.storage.journal.JournalReader.Mode;
import io.atomix.storage.journal.index.JournalIndex;
import io.atomix.utils.Managed;
import io.atomix.utils.concurrent.Futures;
import io.atomix.utils.concurrent.ThreadContextFactory;
import io.atomix.utils.serializer.Serializer;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Supplier;
import org.slf4j.Logger;

/** {@link Partition} server. */
public class RaftPartitionServer implements Managed<RaftPartitionServer> {

  private final Logger log = getLogger(getClass());

  private final MemberId localMemberId;
  private final RaftPartition partition;
  private final RaftPartitionGroupConfig config;
  private final ClusterMembershipService membershipService;
  private final ClusterCommunicationService clusterCommunicator;
  private final PrimitiveTypeRegistry primitiveTypes;
  private final ThreadContextFactory threadContextFactory;
  private final Set<RaftRoleChangeListener> deferredRoleChangeListeners =
      new CopyOnWriteArraySet<>();
  private final Set<Runnable> deferredFailureListeners = new CopyOnWriteArraySet<>();

  private RaftServer server;
  private SnapshotStore snapshotStore;
  private final Supplier<JournalIndex> journalIndexFactory;

  public RaftPartitionServer(
      final RaftPartition partition,
      final RaftPartitionGroupConfig config,
      final MemberId localMemberId,
      final ClusterMembershipService membershipService,
      final ClusterCommunicationService clusterCommunicator,
      final PrimitiveTypeRegistry primitiveTypes,
      final ThreadContextFactory threadContextFactory,
      final Supplier<JournalIndex> journalIndexFactory) {
    this.partition = partition;
    this.config = config;
    this.localMemberId = localMemberId;
    this.membershipService = membershipService;
    this.clusterCommunicator = clusterCommunicator;
    this.primitiveTypes = primitiveTypes;
    this.threadContextFactory = threadContextFactory;
    this.journalIndexFactory = journalIndexFactory;
  }

  @Override
  public CompletableFuture<RaftPartitionServer> start() {
    log.info("Starting server for partition {}", partition.id());
    final CompletableFuture<RaftServer> serverOpenFuture;
    if (partition.members().contains(localMemberId)) {
      if (server != null && server.isRunning()) {
        return CompletableFuture.completedFuture(null);
      }
      synchronized (this) {
        try {
          initServer();
        } catch (final StorageException e) {
          return Futures.exceptionalFuture(e);
        }
      }
      serverOpenFuture = server.bootstrap(partition.members());
    } else {
      serverOpenFuture = CompletableFuture.completedFuture(null);
    }
    return serverOpenFuture
        .whenComplete(
            (r, e) -> {
              if (e == null) {
                log.debug("Successfully started server for partition {}", partition.id());
              } else {
                log.warn("Failed to start server for partition {}", partition.id(), e);
              }
            })
        .thenApply(v -> this);
  }

  @Override
  public boolean isRunning() {
    return server.isRunning();
  }

  @Override
  public CompletableFuture<Void> stop() {
    return server.shutdown();
  }

  private void initServer() {
    server = buildServer();

    if (!deferredRoleChangeListeners.isEmpty()) {
      deferredRoleChangeListeners.forEach(server::addRoleChangeListener);
      deferredRoleChangeListeners.clear();
    }
    if (!deferredFailureListeners.isEmpty()) {
      deferredFailureListeners.forEach(server::addFailureListener);
      deferredFailureListeners.clear();
    }
  }

  private RaftServer buildServer() {
    snapshotStore =
        config
            .getStorageConfig()
            .getSnapshotStoreFactory()
            .createSnapshotStore(partition.dataDirectory().toPath(), partition.name());

    return RaftServer.builder(localMemberId)
        .withName(partition.name())
        .withMembershipService(membershipService)
        .withProtocol(createServerProtocol())
        .withPrimitiveTypes(primitiveTypes)
        .withHeartbeatInterval(config.getHeartbeatInterval())
        .withElectionTimeout(config.getElectionTimeout())
        .withSessionTimeout(config.getDefaultSessionTimeout())
        .withStorage(createRaftStorage())
        .withThreadContextFactory(threadContextFactory)
        .withStateMachineFactory(config.getStateMachineFactory())
        .withJournalIndexFactory(journalIndexFactory)
        .build();
  }

  /**
   * Closes the server and exits the partition.
   *
   * @return future that is completed when the operation is complete
   */
  public CompletableFuture<Void> leave() {
    return server.leave();
  }

  /**
   * Takes a snapshot of the partition server.
   *
   * @return a future to be completed once the snapshot has been taken
   */
  public CompletableFuture<Void> snapshot() {
    return server.compact();
  }

  public void setCompactableIndex(final long index) {
    server.getContext().getServiceManager().setCompactableIndex(index);
  }

  public RaftLogReader openReader(final long index, final Mode mode) {
    return server.getContext().getLog().openReader(index, mode);
  }

  public void addRoleChangeListener(final RaftRoleChangeListener listener) {
    if (server == null) {
      deferredRoleChangeListeners.add(listener);
    } else {
      server.addRoleChangeListener(listener);
    }
  }

  public void addFailureListener(final Runnable failureListener) {
    if (server == null) {
      deferredFailureListeners.add(failureListener);
    } else {
      server.addFailureListener(failureListener);
    }
  }

  public void removeFailureListener(final Runnable failureListener) {
    server.removeFailureListener(failureListener);
  }

  public void removeRoleChangeListener(final RaftRoleChangeListener listener) {
    deferredRoleChangeListeners.remove(listener);
    server.removeRoleChangeListener(listener);
  }

  /** @see io.atomix.raft.impl.RaftContext#addCommitListener(RaftCommitListener) */
  public void addCommitListener(final RaftCommitListener commitListener) {
    server.getContext().addCommitListener(commitListener);
  }

  /** @see io.atomix.raft.impl.RaftContext#removeCommitListener(RaftCommitListener) */
  public void removeCommitListener(final RaftCommitListener commitListener) {
    server.getContext().removeCommitListener(commitListener);
  }

  public SnapshotStore getSnapshotStore() {
    return snapshotStore;
  }

  /** Deletes the server. */
  public void delete() {
    try {
      Files.walkFileTree(
          partition.dataDirectory().toPath(),
          new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs)
                throws IOException {
              Files.delete(file);
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(final Path dir, final IOException exc)
                throws IOException {
              Files.delete(dir);
              return FileVisitResult.CONTINUE;
            }
          });
    } catch (final IOException e) {
      log.error("Failed to delete partition: {}", partition, e);
    }
  }

  public CompletableFuture<Void> join(final Collection<MemberId> otherMembers) {
    log.info("Joining partition {} ({})", partition.id(), partition.name());
    initServer();
    return server
        .join(otherMembers)
        .whenComplete(
            (r, e) -> {
              if (e == null) {
                log.debug(
                    "Successfully joined partition {} ({})", partition.id(), partition.name());
              } else {
                log.warn("Failed to join partition {} ({})", partition.id(), partition.name(), e);
              }
            })
        .thenApply(v -> null);
  }

  public Optional<ZeebeLogAppender> getAppender() {
    final RaftRole role = server.getContext().getRaftRole();
    if (role instanceof ZeebeLogAppender) {
      return Optional.of((ZeebeLogAppender) role);
    }

    return Optional.empty();
  }

  public Role getRole() {
    return server.getRole();
  }

  public long getTerm() {
    return server.getTerm();
  }

  private RaftStorage createRaftStorage() {
    final RaftStorageConfig storageConfig = config.getStorageConfig();
    final RaftCompactionConfig compactionConfig = config.getCompactionConfig();
    return RaftStorage.builder()
        .withPrefix(partition.name())
        .withDirectory(partition.dataDirectory())
        .withStorageLevel(storageConfig.getLevel())
        .withMaxSegmentSize((int) storageConfig.getSegmentSize().bytes())
        .withMaxEntrySize((int) storageConfig.getMaxEntrySize().bytes())
        .withFlushOnCommit(storageConfig.isFlushOnCommit())
        .withDynamicCompaction(compactionConfig.isDynamic())
        .withFreeDiskBuffer(compactionConfig.getFreeDiskBuffer())
        .withFreeMemoryBuffer(compactionConfig.getFreeMemoryBuffer())
        .withNamespace(RaftNamespaces.RAFT_STORAGE)
        .withSnapshotStore(snapshotStore)
        .withJournalIndexFactory(journalIndexFactory)
        .build();
  }

  private RaftServerCommunicator createServerProtocol() {
    return new RaftServerCommunicator(
        partition.name(), Serializer.using(RaftNamespaces.RAFT_PROTOCOL), clusterCommunicator);
  }

  public CompletableFuture<Void> stepDown() {
    return server.stepDown();
  }
}
