/**
 * Copyright 2012 Netflix, Inc.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.hystrix;

import com.netflix.hystrix.HystrixCommandMetrics.HealthCounts;
import rx.Subscriber;
import rx.Subscription;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Circuit-breaker logic that is hooked into {@link HystrixCommand} execution and will stop allowing executions if failures have gone past the defined threshold.
 * 断路器，在HystrixCommand执行时会调用断路器逻辑，如果故障超过定义的阈值，断路器熔断开关将会打开，这时将阻止任务执行。
 * <p>
 * The default (and only) implementation  will then allow a single retry after a defined sleepWindow until the execution
 * succeeds at which point it will again close the circuit and allow executions again.
 * <p>
 * 默认（且唯一）实现将允许在定义的sleepWindow之后进行单次重试，直到执行成功，此时它将再次关闭电路并允许再次执行。
 */
public interface HystrixCircuitBreaker {

    /**
     * Every {@link HystrixCommand} requests asks this if it is allowed to proceed or not.  It is idempotent and does
     * not modify any internal state, and takes into account the half-open logic which allows some requests through
     * after the circuit has been opened
     * <p>
     * 每个HystrixCommand请求都会询问是否允许继续（当断路器开关为OPEN和HALF_OPEN都时返回false，当断路器开关是CLOSE时或者到了下一个睡眠窗口时返回true）。
     * 它是幂等的，不会修改任何内部状态，并考虑到半开逻辑，当一个睡眠窗口到来时他会放行一些请求到后续逻辑
     *
     * @return boolean whether a request should be permitted (是否应允许请求)
     */
    boolean allowRequest();

    /**
     * Whether the circuit is currently open (tripped).
     * 熔断开关是否打开（如果是OPEN或HALF_OPEN时都返回true，如果为CLOSE时返回false，无副作用，是幂等方式）。
     *
     * @return boolean state of circuit breaker（返回断路器的状态）
     */
    boolean isOpen();

    /**
     * Invoked on successful executions from {@link HystrixCommand} as part of feedback mechanism when in a half-open state.
     * <p>
     * 断路器在处于半开状态时，作为反馈机制的一部分，从HystrixCommand成功执行时调用。
     */
    void markSuccess();

    /**
     * Invoked on unsuccessful executions from {@link HystrixCommand} as part of feedback mechanism when in a half-open state.
     * 断路器当处于半开状态时，作为反馈机制的一部分，从HystrixCommand执行不成功的调用。
     */
    void markNonSuccess();

    /**
     * Invoked at start of command execution to attempt an execution.  This is non-idempotent - it may modify internal
     * state.
     * <p>
     * 在命令执行开始时调用以尝试执行，主要所用时判断该请求是否可以执行。这是非幂等的 - 它可能会修改内部状态。
     */
    boolean attemptExecution();

    /**
     * @ExcludeFromJavadoc
     * @ThreadSafe
     */
    class Factory {
        // String is HystrixCommandKey.name() (we can't use HystrixCommandKey directly as we can't guarantee it implements hashcode/equals correctly)
        // key是HystrixCommandKey.name()（我们不能直接使用HystrixCommandKey，因为我们无法保证它正确实现hashcode / equals）
        private static ConcurrentHashMap<String, HystrixCircuitBreaker> circuitBreakersByCommand = new ConcurrentHashMap<String, HystrixCircuitBreaker>();

        /**
         * 根据HystrixCommandKey获取HystrixCircuitBreaker
         * Get the {@link HystrixCircuitBreaker} instance for a given {@link HystrixCommandKey}.
         * <p>
         * This is thread-safe and ensures only 1 {@link HystrixCircuitBreaker} per {@link HystrixCommandKey}.
         *
         * @param key        {@link HystrixCommandKey} of {@link HystrixCommand} instance requesting the {@link HystrixCircuitBreaker}
         * @param group      Pass-thru to {@link HystrixCircuitBreaker}
         * @param properties Pass-thru to {@link HystrixCircuitBreaker}
         * @param metrics    Pass-thru to {@link HystrixCircuitBreaker}
         * @return {@link HystrixCircuitBreaker} for {@link HystrixCommandKey}
         */
        public static HystrixCircuitBreaker getInstance(HystrixCommandKey key, HystrixCommandGroupKey group, HystrixCommandProperties properties, HystrixCommandMetrics metrics) {
            // this should find it for all but the first time
            // 根据HystrixCommandKey获取断路器
            HystrixCircuitBreaker previouslyCached = circuitBreakersByCommand.get(key.name());
            if (previouslyCached != null) {
                return previouslyCached;
            }

            // if we get here this is the first time so we need to initialize

            // Create and add to the map ... use putIfAbsent to atomically handle the possible race-condition of
            // 2 threads hitting this point at the same time and let ConcurrentHashMap provide us our thread-safety
            // If 2 threads hit here only one will get added and the other will get a non-null response instead.
            // 第一次没有获取到断路器，那么我们需要取初始化它
            // 这里直接利用ConcurrentHashMap的putIfAbsent方法，它是原子操作，加入有两个线程执行到这里，将会只有一个线程将值放到容器中
            // 让我们省掉了加锁的步骤
            HystrixCircuitBreaker cbForCommand = circuitBreakersByCommand.putIfAbsent(key.name(), new HystrixCircuitBreakerImpl(key, group, properties, metrics));
            if (cbForCommand == null) {
                // this means the putIfAbsent step just created a new one so let's retrieve and return it
                return circuitBreakersByCommand.get(key.name());
            } else {
                // this means a race occurred and while attempting to 'put' another one got there before
                // and we instead retrieved it and will now return it
                return cbForCommand;
            }
        }

        /**
         * 根据HystrixCommandKey获取HystrixCircuitBreaker，如果没有返回NULL
         * Get the {@link HystrixCircuitBreaker} instance for a given {@link HystrixCommandKey} or null if none exists.
         *
         * @param key {@link HystrixCommandKey} of {@link HystrixCommand} instance requesting the {@link HystrixCircuitBreaker}
         * @return {@link HystrixCircuitBreaker} for {@link HystrixCommandKey}
         */
        public static HystrixCircuitBreaker getInstance(HystrixCommandKey key) {
            return circuitBreakersByCommand.get(key.name());
        }

        /**
         * Clears all circuit breakers. If new requests come in instances will be recreated.
         * 清除所有断路器。如果有新的请求将会重新创建断路器放到容器。
         */
        /* package */
        static void reset() {
            circuitBreakersByCommand.clear();
        }
    }


    /**
     * 默认的断路器实现
     * The default production implementation of {@link HystrixCircuitBreaker}.
     *
     * @ExcludeFromJavadoc
     * @ThreadSafe
     */
    /* package */class HystrixCircuitBreakerImpl implements HystrixCircuitBreaker {
        private final HystrixCommandProperties properties;
        private final HystrixCommandMetrics metrics;

        enum Status {
            // 断路器状态，关闭，打开，半开
            CLOSED, OPEN, HALF_OPEN;
        }

        // 赋值操作不是线程安全的。若想不用锁来实现，可以用AtomicReference<V>这个类，实现对象引用的原子更新。
        // AtomicReference 原子引用，保证Status原子性修改
        private final AtomicReference<Status> status = new AtomicReference<Status>(Status.CLOSED);
        // 记录断路器打开的时间点（时间戳）,如果这个时间大于0表示断路器处于打开状态或半开状态
        private final AtomicLong circuitOpened = new AtomicLong(-1);
        private final AtomicReference<Subscription> activeSubscription = new AtomicReference<Subscription>(null);

        protected HystrixCircuitBreakerImpl(HystrixCommandKey key, HystrixCommandGroupKey commandGroup, final HystrixCommandProperties properties, HystrixCommandMetrics metrics) {
            this.properties = properties;
            this.metrics = metrics;

            //On a timer, this will set the circuit between OPEN/CLOSED as command executions occur
            // 在定时器上，当命令执行发生时，这将在OPEN / CLOSED之间设置电路
            Subscription s = subscribeToStream();
            activeSubscription.set(s);
        }

        private Subscription subscribeToStream() {
            /*
             * This stream will recalculate the OPEN/CLOSED status on every onNext from the health stream
             * 此流将重新计算运行状况流中每个onNext上的OPEN / CLOSED状态
             */
            return metrics.getHealthCountsStream()
                    .observe()
                    .subscribe(new Subscriber<HealthCounts>() {
                        @Override
                        public void onCompleted() {

                        }

                        @Override
                        public void onError(Throwable e) {

                        }

                        @Override
                        public void onNext(HealthCounts hc) {
                            // check if we are past the statisticalWindowVolumeThreshold
                            // 检查一个时间窗口内的最小请求数
                            if (hc.getTotalRequests() < properties.circuitBreakerRequestVolumeThreshold().get()) {
                                // we are not past the minimum volume threshold for the stat window,
                                // so no change to circuit status.
                                // if it was CLOSED, it stays CLOSED
                                // IF IT WAS HALF-OPEN, WE NEED TO WAIT FOR A SUCCESSFUL COMMAND EXECUTION
                                // if it was open, we need to wait for sleep window to elapse
                                // 我们没有超过统计窗口的最小音量阈值，所以我们不会去改变断路器状态，如果是closed状态，他将保持这个状态
                                // 如果是半开状态，那么她需要等到一个成功的 Command执行
                                // 如果是打开状态，那么它需要等到这个时间窗口过去
                            } else {
                                // 检查错误比例阀值
                                if (hc.getErrorPercentage() < properties.circuitBreakerErrorThresholdPercentage().get()) {
                                    //we are not past the minimum error threshold for the stat window,
                                    // so no change to circuit status.
                                    // if it was CLOSED, it stays CLOSED
                                    // if it was half-open, we need to wait for a successful command execution
                                    // if it was open, we need to wait for sleep window to elapse
                                } else {
                                    // our failure rate is too high, we need to set the state to OPEN
                                    // 我们的失败率太高，我们需要将状态设置为OPEN
                                    if (status.compareAndSet(Status.CLOSED, Status.OPEN)) {
                                        circuitOpened.set(System.currentTimeMillis());
                                    }
                                }
                            }
                        }
                    });
        }

        @Override
        public void markSuccess() {
            // 断路器是处理半开并且HystrixCommand执行成功，将状态设置成关闭
            if (status.compareAndSet(Status.HALF_OPEN, Status.CLOSED)) {
                //This thread wins the race to close the circuit - it resets the stream to start it over from 0
                //该线程赢得了关闭电路的竞争 - 它重置流以从0开始
                metrics.resetStream();
                Subscription previousSubscription = activeSubscription.get();
                if (previousSubscription != null) {
                    previousSubscription.unsubscribe();
                }
                Subscription newSubscription = subscribeToStream();
                activeSubscription.set(newSubscription);
                circuitOpened.set(-1L);
            }
        }

        @Override
        public void markNonSuccess() {
            // 断路器是处理半开并且HystrixCommand执行成功，将状态设置成打开
            if (status.compareAndSet(Status.HALF_OPEN, Status.OPEN)) {
                //This thread wins the race to re-open the circuit - it resets the start time for the sleep window
                // 该线程赢得了重新打开电路的竞争 - 它重置了睡眠窗口的开始时间
                circuitOpened.set(System.currentTimeMillis());
            }
        }

        @Override
        public boolean isOpen() {
            // 获取配置判断断路器是否强制打开
            if (properties.circuitBreakerForceOpen().get()) {
                return true;
            }
            // 获取配置判断断路器是否强制关闭
            if (properties.circuitBreakerForceClosed().get()) {
                return false;
            }
            return circuitOpened.get() >= 0;
        }

        @Override
        public boolean allowRequest() {
            // 获取配置判断断路器是否强制打开
            if (properties.circuitBreakerForceOpen().get()) {
                return false;
            }
            // 获取配置判断断路器是否强制关闭
            if (properties.circuitBreakerForceClosed().get()) {
                return true;
            }
            if (circuitOpened.get() == -1) {
                return true;
            } else {
                // 如果是半开状态则返回不允许Command执行
                if (status.get().equals(Status.HALF_OPEN)) {
                    return false;
                } else {
                    // 检查睡眠窗口是否过了
                    return isAfterSleepWindow();
                }
            }
        }

        private boolean isAfterSleepWindow() {
            final long circuitOpenTime = circuitOpened.get();
            final long currentTime = System.currentTimeMillis();
            // 获取配置的一个睡眠的时间窗口
            final long sleepWindowTime = properties.circuitBreakerSleepWindowInMilliseconds().get();
            return currentTime > circuitOpenTime + sleepWindowTime;
        }

        @Override
        public boolean attemptExecution() {
            // 获取配置判断断路器是否强制打开
            if (properties.circuitBreakerForceOpen().get()) {
                return false;
            }
            // 获取配置判断断路器是否强制关闭
            if (properties.circuitBreakerForceClosed().get()) {
                return true;
            }
            if (circuitOpened.get() == -1) {
                return true;
            } else {
                if (isAfterSleepWindow()) {
                    //only the first request after sleep window should execute
                    //if the executing command succeeds, the status will transition to CLOSED
                    //if the executing command fails, the status will transition to OPEN
                    //if the executing command gets unsubscribed, the status will transition to OPEN
                    // 只有一个睡眠窗口后的第一个请求会被执行
                    // 如果执行命令成功，状态将转换为CLOSED
                    // 如果执行命令失败，状态将转换为OPEN
                    // 如果执行命令取消订阅，状态将过渡到OPEN
                    if (status.compareAndSet(Status.OPEN, Status.HALF_OPEN)) {
                        return true;
                    } else {
                        return false;
                    }
                } else {
                    return false;
                }
            }
        }
    }

    /**
     * An implementation of the circuit breaker that does nothing.
     *
     * @ExcludeFromJavadoc
     */
    /* package */static class NoOpCircuitBreaker implements HystrixCircuitBreaker {

        @Override
        public boolean allowRequest() {
            return true;
        }

        @Override
        public boolean isOpen() {
            return false;
        }

        @Override
        public void markSuccess() {

        }

        @Override
        public void markNonSuccess() {

        }

        @Override
        public boolean attemptExecution() {
            return true;
        }
    }

}
