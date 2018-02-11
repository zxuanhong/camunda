/*
 * Copyright © 2017 camunda services GmbH (info@camunda.com)
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
package io.zeebe.dispatcher;

import static io.zeebe.dispatcher.impl.PositionUtil.*;
import static io.zeebe.dispatcher.impl.log.LogBufferAppender.RESULT_PADDING_AT_END_OF_PARTITION;

import java.util.Arrays;

import io.zeebe.dispatcher.impl.log.*;
import io.zeebe.util.sched.*;
import io.zeebe.util.sched.future.ActorFuture;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.status.AtomicLongPosition;
import org.agrona.concurrent.status.Position;


/**
 * Component for sending and receiving messages between different threads.
 *
 */
public class Dispatcher extends ZbActor implements AutoCloseable
{
    public static final int MODE_PUB_SUB = 1;
    public static final int MODE_PIPELINE = 2;

    protected final LogBuffer logBuffer;
    protected final LogBufferAppender logAppender;

    protected final Position publisherLimit;
    protected final Position publisherPosition;
    protected final String[] defaultSubscriptionNames;
    protected Subscription[] subscriptions;

    protected final int maxFrameLength;
    protected final int partitionSize;
    protected int logWindowLength;
    protected final String name;

    protected final int mode;

    protected volatile boolean isClosed = false;

    private ActorCondition dataConsumed;

    private Runnable backgroundTask = this::runBackgroundTask;

    private final Runnable onClaimComplete = this::signalSubsciptions;

    public Dispatcher(
            LogBuffer logBuffer,
            LogBufferAppender logAppender,
            Position publisherLimit,
            Position publisherPosition,
            int logWindowLength,
            String[] subscriptionNames,
            int mode,
            String name)
    {
        this.logBuffer = logBuffer;
        this.logAppender = logAppender;
        this.publisherLimit = publisherLimit;
        this.publisherPosition = publisherPosition;
        this.logWindowLength = logWindowLength;
        this.mode = mode;
        this.name = name;

        this.partitionSize = logBuffer.getPartitionSize();
        this.maxFrameLength = partitionSize / 16;

        this.subscriptions = new Subscription[0];
        this.defaultSubscriptionNames = subscriptionNames;
    }

    @Override
    public String getName()
    {
        return name;
    }

    private void runBackgroundTask()
    {
        updatePublisherLimit();
        logBuffer.cleanPartitions();
    }

    @Override
    protected void onActorStarted()
    {
        dataConsumed = actor.onCondition("data-consumed", backgroundTask);
        openDefaultSubscriptions();
    }

    @Override
    protected void onActorClosing()
    {
        publisherLimit.close();
        publisherPosition.close();

        final Subscription[] subscriptionsCopy = Arrays.copyOf(subscriptions, subscriptions.length);

        for (Subscription subscription : subscriptionsCopy)
        {
            doCloseSubscription(subscription);
        }

        isClosed = true;
    }

    protected void openDefaultSubscriptions()
    {
        final int subscriptionSize = defaultSubscriptionNames == null ? 0 : defaultSubscriptionNames.length;

        for (int i = 0; i < subscriptionSize; i++)
        {
            doOpenSubscription(defaultSubscriptionNames[i], dataConsumed);
        }
    }

    /**
     * Writes the given message to the buffer. This can fail if the publisher
     * limit or the buffer partition size is reached.
     *
     * @return the new publisher position if the message was written
     *         successfully. Otherwise, the return value is negative.
     */
    public long offer(DirectBuffer msg)
    {
        return offer(msg, 0, msg.capacity(), 0);
    }

    /**
     * Writes the given message to the buffer with the given stream id. This can
     * fail if the publisher limit or the buffer partition size is reached.
     *
     * @return the new publisher position if the message was written
     *         successfully. Otherwise, the return value is negative.
     */
    public long offer(DirectBuffer msg, int streamId)
    {
        return offer(msg, 0, msg.capacity(), streamId);
    }

    /**
     * Writes the given part of the message to the buffer. This can fail if the publisher
     * limit or the buffer partition size is reached.
     *
     * @return the new publisher position if the message was written
     *         successfully. Otherwise, the return value is negative.
     */
    public long offer(DirectBuffer msg, int start, int length)
    {
        return offer(msg, start, length, 0);
    }

    /**
     * Writes the given part of the message to the buffer with the given stream
     * id. This can fail if the publisher limit or the buffer partition size is
     * reached.
     *
     * @return the new publisher position if the message was written
     *         successfully. Otherwise, the return value is negative.
     */
    public long offer(DirectBuffer msg, int start, int length, int streamId)
    {
        long newPosition = -1;

        if (!isClosed)
        {
            final long limit = publisherLimit.getVolatile();

            final int activePartitionId = logBuffer.getActivePartitionIdVolatile();
            final LogBufferPartition partition = logBuffer.getPartition(activePartitionId);

            final int partitionOffset = partition.getTailCounterVolatile();
            final long position = position(activePartitionId, partitionOffset);


            if (position < limit)
            {
                final int newOffset;

                if (length < maxFrameLength)
                {
                    newOffset = logAppender.appendFrame(partition,
                                                        activePartitionId,
                                                        msg,
                                                        start,
                                                        length,
                                                        streamId);
                }
                else
                {
                    final String exceptionMessage = String.format("Message length of %s is larger than max frame length of %s",
                                                                  length, maxFrameLength);
                    throw new RuntimeException(exceptionMessage);
                }

                newPosition = updatePublisherPosition(activePartitionId, newOffset);

                publisherPosition.proposeMaxOrdered(newPosition);
                signalSubsciptions();
            }
        }


        return newPosition;
    }

    private void signalSubsciptions()
    {
        final Subscription[] subscriptions = this.subscriptions;
        for (int i = 0; i < subscriptions.length; i++)
        {
            subscriptions[i].getActorConditions().signalConsumers();
        }
    }

    /**
     * Claim a fragment of the buffer with the given length. Use
     * {@link ClaimedFragment#getBuffer()} to write the message and finish the
     * operation using {@link ClaimedFragment#commit()} or
     * {@link ClaimedFragment#abort()}. Note that the claim operation can fail
     * if the publisher limit or the buffer partition size is reached.
     *
     * @return the new publisher position if the fragment was claimed
     *         successfully. Otherwise, the return value is negative.
     */
    public long claim(ClaimedFragment claim, int length)
    {
        return claim(claim, length, 0);
    }

    /**
     * Claim a fragment of the buffer with the given length and stream id. Use
     * {@link ClaimedFragment#getBuffer()} to write the message and finish the
     * operation using {@link ClaimedFragment#commit()} or
     * {@link ClaimedFragment#abort()}. Note that the claim operation can fail
     * if the publisher limit or the buffer partition size is reached.
     *
     * @return the new publisher position if the fragment was claimed
     *         successfully. Otherwise, the return value is negative.
     */
    public long claim(ClaimedFragment claim, int length, int streamId)
    {
        final long limit = publisherLimit.getVolatile();

        final int activePartitionId = logBuffer.getActivePartitionIdVolatile();
        final LogBufferPartition partition = logBuffer.getPartition(activePartitionId);

        final int partitionOffset = partition.getTailCounterVolatile();
        final long position = position(activePartitionId, partitionOffset);

        long newPosition = -1;

        if (position < limit)
        {
            final int newOffset;

            if (length < maxFrameLength)
            {
                newOffset = logAppender.claim(partition,
                        activePartitionId,
                        claim,
                        length,
                        streamId,
                        onClaimComplete);
            }
            else
            {
                throw new RuntimeException("Cannot claim more than " + maxFrameLength + " bytes.");
            }

            newPosition = updatePublisherPosition(activePartitionId, newOffset);
            publisherPosition.proposeMaxOrdered(newPosition);
            signalSubsciptions();
        }
        return newPosition;
    }

    /**
     * Claim a batch of fragments on the buffer with the given length. Use
     * {@link #nextFragment(int, int)} to add a new fragment to the batch. Write the
     * fragment message using {@link #getBuffer()} and {@link #getFragmentOffset()}
     * to get the buffer offset of this fragment. Complete the whole batch operation
     * by calling either {@link #commit()} or {@link #abort()}.
     * Note that the claim operation can fail
     * if the publisher limit or the buffer partition size is reached.
     *
     * @return the new publisher position if the batch was claimed
     *         successfully. Otherwise, the return value is negative.
     */
    public long claim(ClaimedFragmentBatch batch, int fragmentCount, int batchLength)
    {
        long newPosition = -1;

        if (!isClosed)
        {
            final long limit = publisherLimit.getVolatile();

            final int activePartitionId = logBuffer.getActivePartitionIdVolatile();
            final LogBufferPartition partition = logBuffer.getPartition(activePartitionId);

            final int partitionOffset = partition.getTailCounterVolatile();
            final long position = position(activePartitionId, partitionOffset);


            if (position < limit)
            {
                final int newOffset;

                if (batchLength < maxFrameLength)
                {
                    newOffset = logAppender.claim(partition,
                                                  activePartitionId,
                                                  batch,
                                                  fragmentCount,
                                                  batchLength,
                                                  onClaimComplete);
                }
                else
                {
                    throw new RuntimeException("Cannot claim more than " + maxFrameLength + " bytes.");
                }

                newPosition = updatePublisherPosition(activePartitionId, newOffset);

                publisherPosition.proposeMaxOrdered(newPosition);
                signalSubsciptions();
            }
        }

        return newPosition;
    }

    protected long updatePublisherPosition(final int activePartitionId, int newOffset)
    {
        long newPosition = -1;

        if (newOffset > 0)
        {
            newPosition = position(activePartitionId, newOffset);
        }
        else if (newOffset == RESULT_PADDING_AT_END_OF_PARTITION)
        {
            logBuffer.onActiveParitionFilled(activePartitionId);
            newPosition = -2;
        }

        return newPosition;
    }

    public int updatePublisherLimit()
    {
        int isUpdated = 0;

        if (!isClosed)
        {
            long lastSubscriberPosition = -1;

            if (subscriptions.length > 0)
            {
                lastSubscriberPosition = subscriptions[subscriptions.length - 1].getPosition();

                if (MODE_PUB_SUB == mode && subscriptions.length > 1)
                {
                    for (int i = 0; i < subscriptions.length - 1; i++)
                    {
                        lastSubscriberPosition = Math.min(lastSubscriberPosition, subscriptions[i].getPosition());
                    }
                }
            }
            else
            {
                lastSubscriberPosition = Math.max(0, publisherLimit.get() - logWindowLength);
            }

            int partitionId = partitionId(lastSubscriberPosition);
            int partitionOffset = partitionOffset(lastSubscriberPosition) + logWindowLength;
            if (partitionOffset >= logBuffer.getPartitionSize())
            {
                ++partitionId;
                partitionOffset = logWindowLength;

            }
            final long proposedPublisherLimit = position(partitionId, partitionOffset);

            if (publisherLimit.proposeMaxOrdered(proposedPublisherLimit))
            {
                isUpdated = 1;
            }
        }

        return isUpdated;
    }

    /**
     * Creates a new subscription with the given name.
     *
     * @throws IllegalStateException
     *             <li>if the dispatcher runs in pipeline-mode,
     *             <li>if a subscription with this name already exists
     */
    public Subscription openSubscription(String subscriptionName)
    {
        return FutureUtil.join(openSubscriptionAsync(subscriptionName));
    }

    /**
     * Creates a new subscription with the given name asynchronously. The
     * operation fails if the dispatcher runs in pipeline-mode or a subscription
     * with this name already exists.
     */
    public ActorFuture<Subscription> openSubscriptionAsync(String subscriptionName)
    {
        return actor.call(() ->
        {
            if (mode == MODE_PIPELINE)
            {
                throw new IllegalStateException("Cannot open subscriptions in pipelining mode");
            }

            return doOpenSubscription(subscriptionName, dataConsumed);
        });
    }

    public ActorFuture<Subscription> getSubscriptionAsync(String subscriptionName)
    {
        return actor.call(() -> getSubscriptionByName(subscriptionName));
    }

    public Subscription getSubscription(String subscriptionName)
    {
        return FutureUtil.join(getSubscriptionAsync(subscriptionName));
    }

    protected Subscription doOpenSubscription(String subscriptionName, ActorCondition onConsumption)
    {
        ensureUniqueSubscriptionName(subscriptionName);

        final Subscription[] newSubscriptions = new Subscription[subscriptions.length + 1];
        System.arraycopy(subscriptions, 0, newSubscriptions, 0, subscriptions.length);

        final int subscriberId = newSubscriptions.length - 1;

        final Subscription subscription = newSubscription(subscriberId, subscriptionName, onConsumption);

        newSubscriptions[subscriberId] = subscription;

        this.subscriptions = newSubscriptions;

        onConsumption.signal();

        return subscription;
    }

    protected void ensureUniqueSubscriptionName(String subscriptionName)
    {
        if (findSubscriptionByName(subscriptionName) != null)
        {
            throw new IllegalStateException("subscription with name '" + subscriptionName + "' already exists");
        }
    }

    protected Subscription newSubscription(final int subscriptionId, final String subscriptionName, ActorCondition onConsumption)
    {
        final AtomicLongPosition position = new AtomicLongPosition();
        position.setOrdered(position(logBuffer.getActivePartitionIdVolatile(), 0));
        final Position limit = determineLimit(subscriptionId);

        return new Subscription(position, limit, subscriptionId, subscriptionName, onConsumption, logBuffer);
    }

    protected Position determineLimit(int subscriptionId)
    {
        if (mode == MODE_PUB_SUB)
        {
            return publisherPosition;
        }
        else
        {
            if (subscriptionId == 0)
            {
                return publisherPosition;
            }
            else
            {
                // in pipelining mode, a subscriber's limit is the position of the
                // previous subscriber
                return subscriptions[subscriptionId - 1].position;
            }
        }
    }

    /**
     * Close the given subscription.
     *
     * @throws IllegalStateException
     *             if the dispatcher runs in pipeline-mode.
     */
    public void closeSubscription(Subscription subscriptionToClose)
    {
        FutureUtil.join(closeSubscriptionAsync(subscriptionToClose));
    }

    /**
     * Close the given subscription asynchronously. The operation fails if the
     * dispatcher runs in pipeline-mode.
     */
    public ActorFuture<Void> closeSubscriptionAsync(Subscription subscriptionToClose)
    {
        return actor.call(() ->
        {
            if (mode == MODE_PIPELINE)
            {
                throw new IllegalStateException("Cannot close subscriptions in pipelining mode");
            }

            doCloseSubscription(subscriptionToClose);
        });
    }

    protected void doCloseSubscription(Subscription subscriptionToClose)
    {
        if (isClosed)
        {
            return; // don't need to adjust the subscriptions when closed
        }

        // close subscription
        subscriptionToClose.isClosed = true;
        subscriptionToClose.position.close();

        // remove from list
        final int len = subscriptions.length;
        int index = 0;

        for (int i = 0; i < len; i++)
        {
            if (subscriptionToClose == subscriptions[i])
            {
                index = i;
                break;
            }
        }

        Subscription[] newSubscriptions = null;

        final int numMoved = len - index - 1;

        if (numMoved == 0)
        {
            newSubscriptions = Arrays.copyOf(subscriptions, len - 1);
        }
        else
        {
            newSubscriptions = new Subscription[len - 1];
            System.arraycopy(subscriptions, 0, newSubscriptions, 0, index);
            System.arraycopy(subscriptions, index + 1, newSubscriptions, index, numMoved);
        }

        this.subscriptions = newSubscriptions;
    }

    /**
     * Returns the subscription with the given name.
     *
     * @return the subscription
     * @throws exception if no such subscription is opened
     */
    private Subscription getSubscriptionByName(String subscriptionName)
    {
        final Subscription subscription = findSubscriptionByName(subscriptionName);

        if (subscription == null)
        {
            throw new RuntimeException("Subscription with name " + subscriptionName + " not registered");
        }
        else
        {
            return subscription;
        }
    }

    protected Subscription findSubscriptionByName(String subscriptionName)
    {
        Subscription subscription = null;

        if (!isClosed)
        {
            for (int i = 0; i < subscriptions.length; i++)
            {
                if (subscriptions[i].getName().equals(subscriptionName))
                {
                    subscription = subscriptions[i];
                    break;
                }
            }
        }

        return subscription;
    }

    public boolean isClosed()
    {
        return isClosed;
    }

    @Override
    public void close()
    {
        FutureUtil.join(closeAsync());
    }

    public ActorFuture<Void> closeAsync()
    {
        return actor.close();
    }

    public LogBuffer getLogBuffer()
    {
        return logBuffer;
    }

    public int getMaxFrameLength()
    {
        return maxFrameLength;
    }

    public long getPublisherPosition()
    {
        if (isClosed)
        {
            return -1L;
        }
        else
        {
            return publisherPosition.get();
        }
    }

    public long getPublisherLimit()
    {
        if (isClosed)
        {
            return -1L;
        }
        else
        {
            return publisherLimit.get();
        }
    }

    public int getSubscriberCount()
    {
        return subscriptions.length;
    }

    @Override
    public String toString()
    {
        return "Dispatcher [" + name + "]";
    }
}
