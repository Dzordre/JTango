/**
 * Copyright (C) :     2012
 *
 * 	Synchrotron Soleil
 * 	L'Orme des merisiers
 * 	Saint Aubin
 * 	BP48
 * 	91192 GIF-SUR-YVETTE CEDEX
 *
 * This file is part of Tango.
 *
 * Tango is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Tango is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Tango.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.tango.server.device;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.lang.builder.ToStringBuilder;
import org.apache.commons.lang.builder.ToStringStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tango.DeviceState;
import org.tango.server.DeviceBehaviorObject;
import org.tango.server.annotation.Init;
import org.tango.utils.DevFailedUtils;

import fr.esrf.Tango.DevFailed;

/**
 * Managed the initialization of a device.
 * 
 * @see Init
 * @author ABEILLE
 * 
 */
public final class InitImpl extends DeviceBehaviorObject {

    private static final String INIT_FAILED = "Init failed";
    private final Logger logger = LoggerFactory.getLogger(InitImpl.class);

    private final Method initMethod;
    private final boolean isLazy;
    private final Object businessObject;

    private static class ThreadFact implements ThreadFactory {
        private final String name;

        ThreadFact(final String name) {
            this.name = name;
        }

        @Override
        public Thread newThread(final Runnable r) {
            return new Thread(r, name + " Init");
        }
    }

    private final ExecutorService executor;
    private Future<Void> future;
    private final AtomicBoolean isInitDoneCorrectly = new AtomicBoolean(false);

    /**
     * Ctr
     * 
     * @param initMethod
     * @param isLazy
     * @param businessObject
     */
    public InitImpl(final String deviceName, final Method initMethod, final boolean isLazy, final Object businessObject) {
        super();
        this.initMethod = initMethod;
        this.isLazy = isLazy;
        this.businessObject = businessObject;
        executor = Executors.newSingleThreadExecutor(new ThreadFact(deviceName));
    }

    /**
     * Execute the init
     * 
     * @param stateImpl
     * @param statusImpl
     * @throws DevFailed
     */
    public synchronized void execute(final StateImpl stateImpl, final StatusImpl statusImpl) {
        if (isLazy && !isInitInProgress()) {
            final Callable<Void> initRunnable = new Callable<Void>() {
                @Override
                public Void call() throws DevFailed {
                    logger.debug("Lazy init in");
                    doInit(stateImpl, statusImpl);
                    logger.debug("Lazy init out");
                    return null;
                }
            };

            future = executor.submit(initRunnable);
        } else {
            doInit(stateImpl, statusImpl);
        }
    }

    /**
     * Get the init progress
     * 
     * @return true if init is in progress
     */
    public synchronized boolean isInitInProgress() {
        boolean isInitInProgress = false;
        if (future != null) {
            isInitInProgress = !future.isDone();
        }
        return isInitInProgress;
    }

    /**
     * Get the init result
     * 
     * @return true if init was done without errors
     */
    public boolean isInitDoneCorrectly() {
        return isInitDoneCorrectly.get();
    }

    /**
     * 
     * @param stateImpl
     * @param statusImpl
     * @param finishedHandler
     */
    private void doInit(final StateImpl stateImpl, final StatusImpl statusImpl) {
        isInitDoneCorrectly.set(false);
        try {
            stateImpl.stateMachine(DeviceState.INIT);
            statusImpl.statusMachine("Init in progress", DeviceState.INIT);
            initMethod.invoke(businessObject);
            if (getEndState() != null) {
                // state changed by @StateMachine
                stateImpl.stateMachine(getEndState());
            } else if (stateImpl.isDefaultState()) {
                stateImpl.stateMachine(DeviceState.UNKNOWN);
            }
            isInitDoneCorrectly.set(true);
        } catch (final IllegalArgumentException e) {
            manageError(stateImpl, statusImpl, e);
        } catch (final IllegalAccessException e) {
            manageError(stateImpl, statusImpl, e);
        } catch (final InvocationTargetException e) {
            manageInitError(stateImpl, statusImpl, e);
        } catch (final DevFailed e) {
            logger.error(INIT_FAILED, e);
            try {
                stateImpl.stateMachine(DeviceState.FAULT);
                statusImpl.statusMachine(DevFailedUtils.toString(e), DeviceState.FAULT);
            } catch (final DevFailed e1) {
                logger.debug(DevFailedUtils.toString(e1));
            }
        }
    }

    private void manageInitError(final StateImpl stateImpl, final StatusImpl statusImpl,
            final InvocationTargetException e) {
        try {
            logger.error(INIT_FAILED, e.getCause());
            stateImpl.stateMachine(DeviceState.FAULT);
            if (e.getCause() instanceof DevFailed) {
                logger.error("Tango error at Init: {}", DevFailedUtils.toString((DevFailed) e.getCause()));
                statusImpl.statusMachine("Init failed: " + DevFailedUtils.toString((DevFailed) e.getCause()),
                        DeviceState.FAULT);
            } else {
                final StringWriter sw = new StringWriter();
                e.getCause().printStackTrace(new PrintWriter(sw));
                final String stacktrace = sw.toString();
                statusImpl.statusMachine("Init failed: " + stacktrace, DeviceState.FAULT);
            }
        } catch (final DevFailed e1) {
            logger.error(DevFailedUtils.toString(e1));
        }
    }

    private void manageError(final StateImpl stateImpl, final StatusImpl statusImpl, final Exception e) {
        try {
            stateImpl.stateMachine(DeviceState.FAULT);
            final StringWriter sw = new StringWriter();
            if (e.getCause() != null) {
                e.getCause().printStackTrace(new PrintWriter(sw));
            } else {
                e.printStackTrace(new PrintWriter(sw));
            }
            final String stacktrace = sw.toString();
            statusImpl.statusMachine(stacktrace, DeviceState.FAULT);
        } catch (final DevFailed e1) {
            logger.debug(DevFailedUtils.toString(e1));
        }
    }

    @Override
    public String toString() {
        final ToStringBuilder builder = new ToStringBuilder(this, ToStringStyle.SHORT_PREFIX_STYLE);
        builder.append("name", initMethod);
        builder.append("device class", businessObject.getClass());
        return builder.toString();
    }
}
