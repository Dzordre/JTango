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
package org.tango.server.admin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.ext.XLogger;
import org.slf4j.ext.XLoggerFactory;
import org.tango.DeviceState;
import org.tango.logging.LoggingManager;
import org.tango.orb.ORBManager;
import org.tango.orb.ServerRequestInterceptor;
import org.tango.server.ExceptionMessages;
import org.tango.server.IPollable;
import org.tango.server.PolledObjectType;
import org.tango.server.ServerManager;
import org.tango.server.annotation.Command;
import org.tango.server.annotation.Device;
import org.tango.server.annotation.DeviceProperty;
import org.tango.server.annotation.Init;
import org.tango.server.annotation.StateMachine;
import org.tango.server.annotation.Status;
import org.tango.server.annotation.TransactionType;
import org.tango.server.attribute.AttributeImpl;
import org.tango.server.build.DeviceClassBuilder;
import org.tango.server.cache.TangoCacheManager;
import org.tango.server.command.CommandImpl;
import org.tango.server.events.EventManager;
import org.tango.server.export.IExporter;
import org.tango.server.properties.ClassPropertyImpl;
import org.tango.server.properties.DevicePropertyImpl;
import org.tango.server.servant.DeviceImpl;
import org.tango.utils.DevFailedUtils;
import org.tango.utils.TangoUtil;

import fr.esrf.Tango.ClntIdent;
import fr.esrf.Tango.DevFailed;
import fr.esrf.Tango.DevVarLongStringArray;

/**
 * The administration device. Will be started automatically for each device
 * server.
 * 
 * @author ABEILLE
 * 
 */
@Device(transactionType = TransactionType.DEVICE)
public final class AdminDevice {

    private static final String DOES_NOT_EXISTS = " does not exists";
    private static final String DOES_NOT_EXIST = " does not exist";
    private static final String DEVICE_NAME = "Device name";
    private static final String INPUT_ERROR = "INPUT_ERROR";
    private final Logger logger = LoggerFactory.getLogger(AdminDevice.class);
    private final XLogger xlogger = XLoggerFactory.getXLogger(AdminDevice.class);

    @DeviceProperty(name = "polling_threads_pool_size", defaultValue = "0")
    private int pollingThreadsPoolSize = 0;

    // @DeviceProperty
    // private int quartzThreadsPoolSize;

    private List<DeviceClassBuilder> classList;

    @Status
    private String status;
    private IExporter tangoExporter;

    /**
     * Init the device
     * 
     * @throws DevFailed
     */
    @Init
    @StateMachine(endState = DeviceState.ON)
    public void init() throws DevFailed {
        xlogger.entry();
        TangoCacheManager.setPollSize(pollingThreadsPoolSize);
        // logger.debug("init admin device with quartzThreadsPoolSize = {}",
        // quartzThreadsPoolSize);
        status = "The device is ON\nThe polling is ON";
        xlogger.exit();
    }

    /**
     * Set the tango exporter
     * 
     * @param tangoExporter
     */
    public void setTangoExporter(final IExporter tangoExporter) {
        this.tangoExporter = tangoExporter;
    }

    /**
     * Set the class list
     * 
     * @param classList
     */
    public void setClassList(final List<DeviceClassBuilder> classList) {
        this.classList = classList;
    }

    /**
     * get the polling status
     * 
     * @param deviceName
     *            Device name
     * @return Device polling status
     * @throws DevFailed
     */
    @Command(name = "DevPollStatus", inTypeDesc = DEVICE_NAME, outTypeDesc = "Device polling status")
    public String[] getPollStatus(final String deviceName) throws DevFailed {
        xlogger.entry();
        // TODO: manage tango://localhost:12354/1/1/1#dbase=no style device
        // XXX WARN!!! The string table is parsed by jive.Do not change a letter
        // of
        // the result!
        final String fullDeviceName = TangoUtil.getfullNameForDevice(deviceName);
        final List<String> pollStatus = new ArrayList<String>();
        boolean deviceFound = true;
        for (final DeviceClassBuilder deviceClass : classList) {
            int nbDevices = 0;
            for (final DeviceImpl device : deviceClass.getDeviceImplList()) {
                if (fullDeviceName.equalsIgnoreCase(device.getName())) {
                    for (final CommandImpl command : device.getCommandList()) {
                        if (command.isPolled()) {
                            final StringBuilder buf = buildPollingStatus(device, command);
                            pollStatus.add(buf.toString());
                        }
                    }
                    for (final AttributeImpl attribute : device.getAttributeList()) {
                        if (attribute.isPolled()) {
                            final StringBuilder buf = buildPollingStatus(device, attribute);
                            pollStatus.add(buf.toString());
                        }
                    }
                    break;
                }
                nbDevices++;
            }
            if (nbDevices == deviceClass.getDeviceImplList().size()) {
                deviceFound = false;
            } else {
                deviceFound = true;
                break;
            }
        }
        if (!deviceFound) {
            DevFailedUtils.throwDevFailed(ExceptionMessages.DEVICE_NOT_FOUND, deviceName + DOES_NOT_EXIST);
        }
        String[] ret;
        if (pollStatus.isEmpty()) {
            ret = new String[0];
            // ret[0] =
            // "Dear Client,\nI am in a great mood today, I ain't gonna bug.\nYour favourite server :)";
        } else {
            ret = pollStatus.toArray(new String[pollStatus.size()]);
        }
        xlogger.exit();
        return ret;
    }

    private StringBuilder buildPollingStatus(final DeviceImpl device, final IPollable pollable) {
        final StringBuilder buf;
        if (pollable instanceof AttributeImpl) {
            buf = new StringBuilder("Polled attribute name = ");
        } else {
            buf = new StringBuilder("Polled command name = ");
        }

        buf.append(pollable.getName());
        if (pollable.getPollingPeriod() == 0) {
            buf.append("\nPolling externally triggered");
        } else {
            buf.append("\nPolling period (mS) = ");
            buf.append(pollable.getPollingPeriod());
        }
        buf.append("\nPolling ring buffer depth = ");
        buf.append(pollable.getPollRingDepth());
        if (pollable instanceof AttributeImpl && device.getAttributeHistorySize((AttributeImpl) pollable) == 0) {
            buf.append("\nNo data recorded yet");
        }
        if (pollable instanceof CommandImpl && device.getCommandHistorySize((CommandImpl) pollable) == 0) {
            buf.append("\nNo data recorded yet");
        }

        if (!pollable.getLastDevFailed().isEmpty()) {
            buf.append("\nLast attribute read FAILED :\n").append(pollable.getLastDevFailed());
        } else {
            buf.append("\nTime needed for the last attribute reading (mS) = ");
            buf.append(pollable.getExecutionDuration());
            buf.append("\nData not updated since ");
            buf.append(System.currentTimeMillis() - (long) pollable.getLastUpdateTime());
            buf.append(" mS\nDelta between last records (in mS) = ");
            buf.append(pollable.getDeltaTime());
        }
        return buf;
    }

    /**
     * 
     * @return Device class list
     * @throws DevFailed
     */
    @Command(name = "QueryClass", outTypeDesc = "Device server class(es) list")
    public String[] queryClass() throws DevFailed {
        xlogger.entry();
        final String[] ret = new String[classList.size() - 1];
        int i = 0;
        for (final DeviceClassBuilder clazz : classList) {
            if (!clazz.getDeviceClass().equals(AdminDevice.class)) {
                ret[i++] = clazz.getClassName();
            }
        }
        xlogger.exit(Arrays.toString(ret));
        return ret;
    }

    /**
     * 
     * @return Device list
     * @throws DevFailed
     */
    @Command(name = "QueryDevice", outTypeDesc = "Device server device(s) list")
    public String[] queryDevice() throws DevFailed {
        xlogger.entry();
        final List<String> deviceNames = new ArrayList<String>();
        for (final DeviceClassBuilder clazz : classList) {
            if (!clazz.getDeviceClass().equals(AdminDevice.class)) {
                for (final DeviceImpl dev : clazz.getDeviceImplList()) {
                    deviceNames.add(clazz.getClassName() + "::" + dev.getName());
                }
            }
        }
        xlogger.exit();
        return deviceNames.toArray(new String[deviceNames.size()]);
    }

    /**
     * Command QuerySubDevice
     * 
     * @return Device server sub device(s) list
     * @throws DevFailed
     */
    @Command(name = "QuerySubDevice", outTypeDesc = "Device server sub device(s) list")
    public String[] querySubDevice() throws DevFailed {
        // TODO QuerySubDevice
        // final Set<String> names = DeviceProxyFactory.getProxies().keySet();
        // return names.toArray(new String[names.size()]);
        return new String[0];
    }

    /**
     * 
     * @param deviceName
     *            Device name
     * @throws DevFailed
     */
    @Command(name = "DevRestart", inTypeDesc = DEVICE_NAME)
    public void restart(final String deviceName) throws DevFailed {
        xlogger.entry();
        int nbClasses = 0;
        final ClntIdent id = tangoExporter.getDevice("DServer", ServerManager.getInstance().getAdminDeviceName())
                .getClientIdentity();
        for (final DeviceClassBuilder deviceClass : classList) {
            if (deviceClass.containsDevice(deviceName)) {
                // a device cannot be restarted if locked by another client
                deviceClass.getDeviceImpl(deviceName).checkLocking(id);
                tangoExporter.buildDevice(deviceName, deviceClass);
                xlogger.exit();
                break;
            }
            nbClasses++;
        }
        if (nbClasses == classList.size()) {
            DevFailedUtils.throwDevFailed(ExceptionMessages.DEVICE_NOT_FOUND, deviceName + DOES_NOT_EXISTS);
        }
    }

    @Command(name = "EventSubscriptionChange", inTypeDesc = "Event consumer wants to subscribe to", outTypeDesc = "Tango lib release")
    public int eventSubscriptionChange(final String[] argin) {
        return 0;
    }

    /**
     * Restart the whole server and its devices.
     * 
     * @throws DevFailed
     */
    @Command(name = "RestartServer")
    public void restartServer() throws DevFailed {
        xlogger.entry();
        tangoExporter.unexportAll();
        tangoExporter.exportAll();
        xlogger.exit();
    }

    /**
     * Unexport everything and kill it self
     * 
     * @throws DevFailed
     */
    @Command(name = "Kill")
    public void kill() throws DevFailed {
        xlogger.entry();
        // do it in a thread to avoid throwing error to client

        new Thread() {
            @Override
            public void run() {
                logger.error("kill server");
                try {
                    tangoExporter.unexportAll();
                } catch (final DevFailed e) {
                } finally {
                    ORBManager.shutdown();
                    System.exit(-1);
                }

                logger.error("everything has been shutdown normally");
            }
        }.start();
        xlogger.exit();
    }

    /**
     * Start logging
     */
    @Command(name = "StartLogging")
    public void startLogging() {
        LoggingManager.getInstance().startAll();
    }

    /**
     * Stop logging
     */
    @Command(name = "StopLogging")
    public void stopLogging() {
        LoggingManager.getInstance().stopAll();
    }

    /**
     * Send logs to a device
     * 
     * @param argin
     * @throws DevFailed
     */
    @Command(name = "AddLoggingTarget", inTypeDesc = "Str[i]=Device-name. Str[i+1]=Target-type::Target-name")
    public void addLoggingTarget(final String[] argin) throws DevFailed {
        if (argin.length % 2 != 0) {
            DevFailedUtils.throwDevFailed(INPUT_ERROR, "argin must be of even size");
        }
        for (int i = 0; i < argin.length - 1; i = i + 2) {
            final String deviceName = argin[i];
            final String[] config = argin[i + 1].split(LoggingManager.LOGGING_TARGET_SEPARATOR);
            if (config.length != 2) {
                DevFailedUtils.throwDevFailed(INPUT_ERROR, "config must be of size 2: targetType::targetName");
            }
            if (config[0].equalsIgnoreCase(LoggingManager.LOGGING_TARGET_DEVICE)) {
                LoggingManager.getInstance().addDeviceAppender(config[1], deviceName);
            } else {
                LoggingManager.getInstance().addFileAppender(config[1], deviceName);
            }
        }
    }

    /**
     * remove logging to a device
     * 
     * @param argin
     * @throws DevFailed
     */
    @Command(name = "RemoveLoggingTarget", inTypeDesc = "Str[i]=Device-name. Str[i+1]=Target-type::Target-name")
    public void removeLoggingTarget(final String[] argin) throws DevFailed {
        if (argin.length % 2 != 0) {
            DevFailedUtils.throwDevFailed(INPUT_ERROR, "argin must be of even size");
        }
        for (int i = 0; i < argin.length - 1; i = i + 2) {
            final String deviceName = argin[i];
            final String[] config = argin[i + 1].split(LoggingManager.LOGGING_TARGET_SEPARATOR);
            if (config.length != 2) {
                DevFailedUtils.throwDevFailed(INPUT_ERROR, "config must be of size 2: targetType::targetName");
            }
            LoggingManager.getInstance().removeAppender(deviceName, config[0]);
        }
    }

    /**
     * Get the logging level
     * 
     * @param deviceNames
     * @return the current logging levels
     */
    @Command(name = "GetLoggingLevel", inTypeDesc = "Device list", outTypeDesc = "Lg[i]=Logging Level. Str[i]=Device name.")
    public DevVarLongStringArray getLoggingLevel(final String[] deviceNames) {
        final int[] levels = new int[deviceNames.length];
        for (int i = 0; i < levels.length; i++) {
            levels[i] = LoggingManager.getInstance().getLoggingLevel(deviceNames[i]);
        }
        return new DevVarLongStringArray(levels, deviceNames);
    }

    /**
     * Get logging target
     * 
     * @param deviceName
     * @return Logging target list
     * @throws DevFailed
     */

    @Command(name = "GetLoggingTarget", inTypeDesc = DEVICE_NAME, outTypeDesc = "Logging target list")
    public String[] getLoggingTarget(final String deviceName) throws DevFailed {
        return LoggingManager.getInstance().getLoggingTarget(deviceName);
    }

    /**
     * Set logging level
     * 
     * @param dvlsa
     *            Lg[i]=Logging Level. Str[i]=Device name.
     * @throws DevFailed
     */
    @Command(name = "SetLoggingLevel", inTypeDesc = "Lg[i]=Logging Level. Str[i]=Device name.")
    public void setLoggingLevel(final DevVarLongStringArray dvlsa) throws DevFailed {
        final int[] levels = dvlsa.lvalue;
        final String[] deviceNames = dvlsa.svalue;
        if (deviceNames.length != levels.length) {
            DevFailedUtils.throwDevFailed(INPUT_ERROR, "argin must be of same size for string and long ");
        }
        for (int i = 0; i < levels.length; i++) {
            LoggingManager.getInstance().setLoggingLevel(deviceNames[i], levels[i]);
        }
    }

    /**
     * Get status
     * 
     * @return the status
     */
    public String getStatus() {
        return status;
    }

    /**
     * 
     * @return Polled device name list
     */
    @Command(name = "PolledDevice", outTypeDesc = "Polled device name list")
    public String[] getPolledDevice() {
        xlogger.entry();

        final Set<String> pollDevices = new LinkedHashSet<String>();
        for (final DeviceClassBuilder deviceClass : classList) {
            for (final DeviceImpl device : deviceClass.getDeviceImplList()) {
                for (final CommandImpl command : device.getCommandList()) {
                    if (command.isPolled()) {
                        pollDevices.add(device.getName());
                    }
                }
                for (final AttributeImpl attribute : device.getAttributeList()) {
                    if (attribute.isPolled()) {
                        pollDevices.add(device.getName());
                    }
                }
            }
        }

        xlogger.exit();
        return pollDevices.toArray(new String[pollDevices.size()]);
    }

    /**
     * 
     * @param dvlsa
     *            Lg[0]=Upd period. Str[0]=Device name. Str[1]=Object
     *            type(COMMAND or ATTRIBUTE). Str[2]=Object name
     * @throws DevFailed
     */
    @Command(name = "AddObjPolling", inTypeDesc = "Lg[0]=Upd period. Str[0]=Device name. Str[1]=Object type. Str[2]=Object name")
    public void addPolling(final DevVarLongStringArray dvlsa) throws DevFailed {
        xlogger.entry();
        // Check that parameters number is correct
        if (dvlsa.svalue.length != 3 || dvlsa.lvalue.length != 1) {
            DevFailedUtils.throwDevFailed(ExceptionMessages.WRONG_NR_ARGS, "Incorrect number of inout arguments");
        }
        final String deviceName = dvlsa.svalue[0];
        final String type = dvlsa.svalue[1];
        final String polledObjectName = dvlsa.svalue[2];
        final int pollPeriod = dvlsa.lvalue[0];
        logger.info("add polling for {}/{} {} with period {}", new Object[] { deviceName, polledObjectName, type,
                pollPeriod });
        for (final DeviceClassBuilder deviceClass : classList) {
            if (deviceClass.containsDevice(deviceName)) {
                final DeviceImpl dev = deviceClass.getDeviceImpl(deviceName);
                if (type.equalsIgnoreCase(PolledObjectType.ATTRIBUTE.toString())) {
                    dev.addAttributePolling(polledObjectName, pollPeriod);
                } else {
                    dev.addCommandPolling(polledObjectName, pollPeriod);
                }

                break;
            }
        }

        xlogger.exit();
    }

    /**
     * Command UpdObjPollingPeriod
     * 
     * @param dvlsa
     * @throws DevFailed
     */
    @Command(name = "UpdObjPollingPeriod", inTypeDesc = "Lg[0]=Upd period. Str[0]=Device name. Str[1]=Object type. Str[2]=Object name")
    public void updatePollingPeriod(final DevVarLongStringArray dvlsa) throws DevFailed {
        addPolling(dvlsa);
    }

    /**
     * Command RemObjPolling
     * 
     * @param devices
     *            deviceName,type= {attribute or command},name1, namei
     * @throws DevFailed
     */
    @Command(name = "RemObjPolling", inTypeDesc = "Str[0]=Device name. Str[1]=Object type. Str[2]=Object name")
    public void removePolling(final String[] devices) throws DevFailed {
        xlogger.entry();
        if (devices.length < 3) {
            DevFailedUtils.throwDevFailed(ExceptionMessages.WRONG_NR_ARGS, "Incorrect number of inout arguments");
        }
        final String deviceName = devices[0];
        final String type = devices[1];
        final String[] attributes = Arrays.copyOfRange(devices, 2, devices.length);
        for (final String attribute : attributes) {
            for (final DeviceClassBuilder deviceClass : classList) {
                if (deviceClass.containsDevice(deviceName)) {
                    final DeviceImpl dev = deviceClass.getDeviceImpl(deviceName);
                    if (type.equalsIgnoreCase(PolledObjectType.ATTRIBUTE.toString())) {
                        logger.debug("remove polling of attribute {} on device {}", attribute, deviceName);
                        dev.removeAttributePolling(attribute);
                    } else {
                        logger.debug("remove polling of command {} on device {}", attribute, deviceName);
                        dev.removeCommandPolling(attribute);
                    }
                }
            }
        }
        xlogger.exit();
    }

    /**
     * Stop polling
     */
    @Command(name = "StopPolling")
    public void stopPolling() {
        xlogger.entry();
        for (final DeviceClassBuilder deviceClass : classList) {
            for (final DeviceImpl dev : deviceClass.getDeviceImplList()) {
                dev.stopPolling();
            }
        }
        status = "The device is ON\nThe polling is OFF";
        xlogger.exit();
    }

    /**
     * Start polling
     */
    @Command(name = "StartPolling")
    public void startPolling() {
        xlogger.entry();
        for (final DeviceClassBuilder deviceClass : classList) {
            for (final DeviceImpl dev : deviceClass.getDeviceImplList()) {
                dev.startPolling();
            }
        }
        status = "The device is ON\nThe polling is ON";
        xlogger.exit();
    }

    /**
     * Get class properties
     * 
     * @param className
     * @return class properties
     * @throws DevFailed
     */
    @Command(name = "QueryWizardClassProperty", inTypeDesc = "Class name", outTypeDesc = "Class property list (name - description and default value)")
    public String[] queryClassProp(final String className) throws DevFailed {
        xlogger.entry();
        final List<String> names = new ArrayList<String>();
        for (final DeviceClassBuilder deviceClass : classList) {
            if (deviceClass.getClassName().equalsIgnoreCase(className)) {
                final List<DeviceImpl> devices = deviceClass.getDeviceImplList();
                if (devices.size() > 0) {
                    final DeviceImpl dev = devices.get(0);
                    final List<ClassPropertyImpl> props = dev.getClassPropertyList();
                    for (final ClassPropertyImpl prop : props) {
                        names.add(prop.getName());
                        names.add(prop.getDescription());
                        names.add("");// default value
                    }
                }
                break;
            }
        }
        xlogger.exit();
        return names.toArray(new String[names.size()]);
    }

    /**
     * Get device properties
     * 
     * @param className
     * @return device properties and descriptions
     */
    @Command(name = "QueryWizardDevProperty", inTypeDesc = "Class name", outTypeDesc = "Device property list (name - description and default value)")
    public String[] queryDevProp(final String className) {
        xlogger.entry();
        final List<String> names = new ArrayList<String>();
        for (final DeviceClassBuilder deviceClass : classList) {
            if (deviceClass.getClassName().equalsIgnoreCase(className)) {
                final List<DeviceImpl> devices = deviceClass.getDeviceImplList();
                if (devices.size() > 0) {
                    final DeviceImpl dev = devices.get(0);
                    final List<DevicePropertyImpl> props = dev.getDevicePropertyList();
                    for (final DevicePropertyImpl prop : props) {
                        names.add(prop.getName());
                        names.add(prop.getDescription());
                        names.add(prop.getDefaultValue()[0]);// default value
                    }
                }
                break;
            }
        }
        xlogger.exit(names);
        return names.toArray(new String[names.size()]);
    }

    /**
     * 
     * @param argin
     * @throws DevFailed
     */
    @Command(name = "ZmqEventSubscriptionChange", inTypeDesc = "Events consumer wants to subscribe to", outTypeDesc = "Str[0] = Heartbeat pub endpoint - Str[1] = Event pub endpoint - Lg[0] = Tango lib release - Lg[1] = Device IDL release")
    public DevVarLongStringArray zmqEventSubscriptionChange(final String[] argin) throws DevFailed {

        xlogger.entry();
        if (argin.length != 4) {
            DevFailedUtils.throwDevFailed(ExceptionMessages.WRONG_NR_ARGS,
                    "Command ZmqEventSubscriptionChange expect 4 input arguments");
        }
        final String deviceName = argin[0].toLowerCase(Locale.ENGLISH);
        final String attributeName = argin[1].toLowerCase(Locale.ENGLISH);
        // argin[2] - "subscribe" not used.
        final String eventType = argin[3].toLowerCase(Locale.ENGLISH);

        // Search the specified device and attribute objects
        DeviceImpl device = null;
        AttributeImpl attribute = null;
        for (final DeviceClassBuilder deviceClass : classList) {
            for (final DeviceImpl deviceImpl : deviceClass.getDeviceImplList()) {
                if (deviceImpl.getName().toLowerCase(Locale.ENGLISH).equals(deviceName)) {
                    for (final AttributeImpl attributeImpl : deviceImpl.getAttributeList()) {

                        if (attributeImpl.getName().toLowerCase(Locale.ENGLISH).equals(attributeName)) {

                             if (attributeImpl.isPolled()) {
                                // Check if event criteria are set. Otherwise a DevFailed is thrown
                                 EventManager.checkEventCriteria(attributeImpl, eventType);
                                // Found. Store objects
                                device = deviceImpl;
                                attribute = attributeImpl;

                                // ToDo could not start user event if not polled?
                                // XXX No. It must be authorized by code with attribute methods
                                //	set_data_ready_event(), set_change_event() or set_archive_event()

                             } else
                                DevFailedUtils.throwDevFailed(ExceptionMessages.ATTR_NOT_POLLED,
                                    "The polling (necessary to send events) for the attribute " +
                                            attributeName + " is not started");
                        }
                    }

                    if (attribute == null) { // Not found
                        DevFailedUtils.throwDevFailed(ExceptionMessages.ATTR_NOT_FOUND,
                                "Attribute " + attributeName + " not found");
                    }
                }
            }
        }

        logger.debug("Subscribe event for {}/{} with type {}", new Object[] { deviceName, attributeName, eventType });

        if (device == null) { // Not found
            DevFailedUtils.throwDevFailed(ExceptionMessages.DEVICE_NOT_FOUND, "Device " + deviceName + " not found");
        }
        xlogger.exit();

        // Subscribe and returns connection parameters for client
        // Str[0] = Heartbeat pub endpoint -
        // Str[1] = Event pub endpoint
        // - Lg[0] = Tango lib release
        // - Lg[1] = Device IDL release
        // - Lg[2] = High Water Mark buffer size
        // - Lg[3] = Multicast info
        // - Lg[4] = Multicast info
        // - Lg[5] = ZMQ release
        return EventManager.getInstance().getConnectionParameters(deviceName, attribute, eventType);

    }

    /**
     * 
     * @param argin
     * @throws DevFailed
     */
    @Command(name = "LockDevice", inTypeDesc = "Str[0] = Device name. Lg[0] = Lock validity")
    public void lockDevice(final DevVarLongStringArray argin) throws DevFailed {
        if (argin.svalue.length != 1 && argin.lvalue.length != 1) {
            DevFailedUtils.throwDevFailed(ExceptionMessages.WRONG_NR_ARGS, "Incorrect number of inout arguments");
        }
        final String deviceName = argin.svalue[0];
        final int validity = argin.lvalue[0];
        logger.debug("locking {} with {}", deviceName, validity);
        if (deviceName.equalsIgnoreCase(ServerManager.getInstance().getAdminDeviceName())) {
            DevFailedUtils.throwDevFailed(ExceptionMessages.DEVICE_UNLOCKABLE, deviceName + " not lockable");
        }

        // identify the client who's asking to lock
        ClntIdent id = null;
        for (final DeviceClassBuilder deviceClass : classList) {
            if (deviceClass.containsDevice(ServerManager.getInstance().getAdminDeviceName())) {
                id = deviceClass.getDeviceImpl(ServerManager.getInstance().getAdminDeviceName()).getClientIdentity();
                break;
            }
        }
        int nbClasses = 0;
        for (final DeviceClassBuilder deviceClass : classList) {
            if (deviceClass.containsDevice(deviceName)) {
                deviceClass.getDeviceImpl(deviceName).lock(validity, id,
                        ServerRequestInterceptor.getInstance().getGiopHostAddress());
                xlogger.exit();
                break;
            }
            nbClasses++;
        }
        if (nbClasses == classList.size()) {
            DevFailedUtils.throwDevFailed(ExceptionMessages.DEVICE_NOT_FOUND, deviceName + DOES_NOT_EXISTS);
        }
    }

    /**
     * Command UnLockDevice
     * 
     * @param argin
     * @return Device global lock counter
     * @throws DevFailed
     */
    @Command(name = "UnLockDevice", inTypeDesc = "Str[x] = Device name(s). Lg[0] = Force flag", outTypeDesc = "Device global lock counter")
    public int unlockDevice(final DevVarLongStringArray argin) throws DevFailed {
        final String[] deviceNames = argin.svalue;
        boolean isForced = false;
        if (argin.lvalue[0] == 1) {
            isForced = true;
        }
        logger.debug("unlocking {} - force = {}", Arrays.toString(deviceNames), isForced);
        for (final String deviceName : deviceNames) {
            int nbClasses = 0;
            for (final DeviceClassBuilder deviceClass : classList) {
                if (deviceClass.containsDevice(deviceName)) {
                    deviceClass.getDeviceImpl(deviceName).unLock(isForced);
                    xlogger.exit();
                    break;
                }
                nbClasses++;
            }
            if (nbClasses == classList.size()) {
                DevFailedUtils.throwDevFailed(ExceptionMessages.DEVICE_NOT_FOUND, deviceName + DOES_NOT_EXISTS);
            }
        }
        return 0;
    }

    /**
     * 
     * @param deviceNames
     * @throws DevFailed
     */
    @Command(name = "ReLockDevices", inTypeDesc = "Device(s) name")
    public void relockDevice(final String[] deviceNames) throws DevFailed {
        for (final String deviceName : deviceNames) {
            logger.debug("re locking {} ", deviceName);
            if (deviceName.equalsIgnoreCase(ServerManager.getInstance().getAdminDeviceName())) {
                DevFailedUtils.throwDevFailed(ExceptionMessages.DEVICE_UNLOCKABLE, deviceName + " not lockable");
            }
            for (final DeviceClassBuilder deviceClass : classList) {
                if (deviceClass.containsDevice(deviceName)) {
                    deviceClass.getDeviceImpl(deviceName).relock();
                    xlogger.exit();
                }
            }
        }
    }

    /**
     * Command DevLockStatus
     * 
     * @param deviceName
     *            device name
     * @return lock status
     * @throws DevFailed
     */
    @Command(name = "DevLockStatus", inTypeDesc = DEVICE_NAME, outTypeDesc = "Device locking status")
    public DevVarLongStringArray devLockStatus(final String deviceName) throws DevFailed {
        DevVarLongStringArray result = null;
        int nbClasses = 0;
        final String fullDeviceName = TangoUtil.getfullNameForDevice(deviceName);
        for (final DeviceClassBuilder deviceClass : classList) {
            if (deviceClass.containsDevice(fullDeviceName)) {
                result = deviceClass.getDeviceImpl(fullDeviceName).getLockStatus();
                xlogger.exit();
                break;
            }
            nbClasses++;
        }
        if (nbClasses == classList.size()) {
            DevFailedUtils.throwDevFailed(ExceptionMessages.DEVICE_NOT_FOUND, deviceName + DOES_NOT_EXISTS);
        }
        logger.debug("DevLockStatus {} {}", Arrays.toString(result.lvalue), Arrays.toString(result.svalue));
        return result;
    }

    /**
     * set status
     * 
     * @param status
     */
    public void setStatus(final String status) {
        this.status = status;
    }

    public void setPollingThreadsPoolSize(final int pollingThreadsPoolSize) {
        this.pollingThreadsPoolSize = pollingThreadsPoolSize;
    }

    // public void setQuartzThreadsPoolSize(final int quartzThreadsPoolSize) {
    // this.quartzThreadsPoolSize = quartzThreadsPoolSize;
    // DeviceScheduler.setThreadPoolSize(quartzThreadsPoolSize);
    // }
}
