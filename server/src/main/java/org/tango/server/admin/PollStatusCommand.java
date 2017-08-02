package org.tango.server.admin;

import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.collect.Collections2;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import fr.esrf.Tango.DevFailed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tango.server.ExceptionMessages;
import org.tango.server.IPollable;
import org.tango.server.attribute.AttributeImpl;
import org.tango.server.build.DeviceClassBuilder;
import org.tango.server.command.CommandImpl;
import org.tango.server.servant.DeviceImpl;
import org.tango.utils.DevFailedUtils;
import org.tango.utils.TangoUtil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * @author Igor Khokhriakov <igor.khokhriakov@hzg.de>
 * @since 8/2/17
 */
public class PollStatusCommand implements Callable<String[]> {
    private final Logger logger = LoggerFactory.getLogger(PollStatusCommand.class);

    private final String deviceName;
    private final List<DeviceClassBuilder> classList;

    PollStatusCommand(String deviceName, List<DeviceClassBuilder> classList) {
        this.deviceName = deviceName;
        this.classList = classList;
    }

    @Override
    public String[] call() throws DevFailed {
        final List<String> result = new ArrayList<String>();

        final DeviceImpl device = tryFindDeviceByName(deviceName);

        addPolledCommands(result, device);

        addPolledAttributes(result, device);

        return result.toArray(new String[result.size()]);
    }

    private void addPolledAttributes(List<String> pollStatus, final DeviceImpl device) {
        Collection<AttributeImpl> polledAttributes = Collections2.filter(device.getAttributeList(), new Predicate<AttributeImpl>() {
            @Override
            public boolean apply(AttributeImpl attribute) {
                return attribute.isPolled();
            }
        });

        pollStatus.addAll(Collections2.transform(polledAttributes, new Function<AttributeImpl, String>() {
            @Override
            public String apply(AttributeImpl attribute) {
                return buildPollingStatus(device, attribute).toString();
            }
        }));
    }

    private void addPolledCommands(List<String> pollStatus, final DeviceImpl device) {
        Collection<CommandImpl> polledCommands = Collections2.filter(device.getCommandList(), new Predicate<CommandImpl>() {
            @Override
            public boolean apply(CommandImpl command) {
                return command.isPolled();
            }
        });
        pollStatus.addAll(
                Collections2.transform(polledCommands, new Function<CommandImpl, String>() {
                    @Override
                    public String apply(CommandImpl command) {
                        return buildPollingStatus(device, command).toString();
                    }
                }));
    }

    private DeviceImpl tryFindDeviceByName(final String deviceName) throws DevFailed {
        List<DeviceImpl> allDevices = Lists.newLinkedList(Iterables.concat(Iterables.transform(classList, new Function<DeviceClassBuilder, List<DeviceImpl>>() {
            @Override
            public List<DeviceImpl> apply(DeviceClassBuilder input) {
                return input.getDeviceImplList();
            }
        })));

        Optional<DeviceImpl> device = Iterables.tryFind(allDevices, new Predicate<DeviceImpl>() {
            @Override
            public boolean apply(DeviceImpl input) {
                return deviceName.equalsIgnoreCase(input.getName());
            }
        });
        if (!device.isPresent()) {
            //try to find device by alias
            device = Iterables.tryFind(allDevices, new Predicate<DeviceImpl>() {
                @Override
                public boolean apply(DeviceImpl input) {
                    try {
                        //returns alias or deviceName
                        return TangoUtil.getfullNameForDevice(deviceName).equalsIgnoreCase(input.getName());
                    } catch (DevFailed devFailed) {
                        logger.warn("Failed to get full name for device {}", deviceName);
                        DevFailedUtils.logDevFailed(devFailed, logger);
                        return false;
                    }
                }
            });
        }
        if (!device.isPresent()) {
            DevFailedUtils.throwDevFailed(ExceptionMessages.DEVICE_NOT_FOUND, deviceName + AdminDevice.DOES_NOT_EXIST);
        }
        return device.get();
    }

    private StringBuilder buildPollingStatus(final DeviceImpl device, final IPollable pollable) {
        // XXX WARN!!! The string table is parsed by jive.Do not change a letter
        // of
        // the result!
        //TODO do we need special output formatter for jive?
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
}
