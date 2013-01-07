//+======================================================================
// $Source$
//
// Project:   Tango
//
// Description:  java source code for the TANGO client/server API.
//
// $Author$
//
// Copyright (C) :      2004,2005,2006,2007,2008,2009,2010,2011,2012
//						European Synchrotron Radiation Facility
//                      BP 220, Grenoble 38043
//                      FRANCE
//
// This file is part of Tango.
//
// Tango is free software: you can redistribute it and/or modify
// it under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
// 
// Tango is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU Lesser General Public License for more details.
// 
// You should have received a copy of the GNU Lesser General Public License
// along with Tango.  If not, see <http://www.gnu.org/licenses/>.
//
// $Revision$
//
//-======================================================================


package fr.esrf.TangoApi.events;


import fr.esrf.Tango.DevError;
import fr.esrf.Tango.DevFailed;
import fr.esrf.TangoApi.*;
import fr.esrf.TangoDs.Except;
import fr.esrf.TangoDs.TangoConst;
import org.omg.CosNotifyComm.StructuredPushConsumerPOA;

import java.util.Enumeration;
import java.util.Hashtable;


/**
 * @author pascal_verdier
 */
abstract public class EventConsumer extends StructuredPushConsumerPOA
        implements TangoConst, Runnable, IEventConsumer {

    protected static int subscribe_event_id = 0;
    protected static Hashtable<String, EventChannelStruct>   channel_map        = new Hashtable<String, EventChannelStruct>();
    protected static Hashtable<String, String>               device_channel_map = new Hashtable<String, String>();
    protected static Hashtable<String, EventCallBackStruct>  event_callback_map = new Hashtable<String, EventCallBackStruct>();
    protected static Hashtable<String, EventCallBackStruct>  failed_event_callback_map = new Hashtable<String, EventCallBackStruct>();

    //===============================================================
    //===============================================================
    abstract protected  void checkDeviceConnection(DeviceProxy device,
                        String attribute, DeviceData deviceData, String event_name) throws DevFailed;

    abstract protected void connect_event_channel(ConnectionStructure cs) throws DevFailed;
    abstract protected boolean reSubscribe(EventChannelStruct event_channel_struct,
                                  EventCallBackStruct callback_struct);
    abstract protected void removeFilters(EventCallBackStruct cb_struct) throws DevFailed;

    abstract protected String getEventSubscriptionCommandName();

    abstract protected void checkIfAlreadyConnected(DeviceProxy device,
                                           String attribute,
                                           String event_name,
                                           CallBack callback,
                                           int max_size,
                                           boolean stateless) throws DevFailed;

    abstract protected void setAdditionalInfoToEventCallBackStruct(EventCallBackStruct callback_struct,
                            String device_name, String attribute, String event_name,
                            String[]filters, EventChannelStruct channel_struct)throws DevFailed;

    abstract protected void unsubscribeTheEvent(EventCallBackStruct callbackStruct) throws DevFailed;

    abstract protected void checkIfHeartbeatSkipped(String name, EventChannelStruct eventChannelStruct);
    //===============================================================
    //===============================================================
    protected EventConsumer() throws DevFailed {
        //  Default constructor
    }
    //===============================================================
    //===============================================================
    static Hashtable<String, EventChannelStruct> getChannelMap() {
        return channel_map;
    }
    //===============================================================
    //===============================================================
    static Hashtable<String, EventCallBackStruct>  getEventCallbackMap() {
        return event_callback_map;
    }
    //===============================================================
    //===============================================================
    public void disconnect_structured_push_consumer() {
        System.out.println("calling EventConsumer.disconnect_structured_push_consumer()");
    }
    //===============================================================
    //===============================================================
    public void offer_change(org.omg.CosNotification.EventType[] added, org.omg.CosNotification.EventType[] removed)
            throws org.omg.CosNotifyComm.InvalidEventType {
        System.out.println("calling EventConsumer.offer_change()");
    }
    //===============================================================
    //===============================================================
    protected void push_structured_event_heartbeat(String domain_name) {
        try {
            if (channel_map.containsKey(domain_name)) {
                EventChannelStruct event_channel_struct = channel_map.get(domain_name);
                event_channel_struct.last_heartbeat = System.currentTimeMillis();
            } else {
                //	In case of (use_db==false)
                //	domain name is only device name
                //	but key is full name (//host:port/a/b/c....)
                Enumeration keys = channel_map.keys();
                boolean found = false;
                while (keys.hasMoreElements() && !found) {
                    String name = (String) keys.nextElement();
                    EventChannelStruct event_channel_struct = channel_map.get(name);
                    if (event_channel_struct.adm_device_proxy.name().equals(domain_name)) {
                        event_channel_struct.last_heartbeat = System.currentTimeMillis();
                        found = true;
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    //===============================================================
    //===============================================================
    private void callEventSubscriptionAndConnect(DeviceProxy device, String attribute, String event_name)
            throws DevFailed {
        //	EventSubscriptionChange call is now done before (PV 11/01/08)
        String device_name = device.name();
        String[] info = new String[]{
                device_name, attribute, "subscribe", event_name
        };
        DeviceData argin = new DeviceData();
        argin.insert(info);
        String cmdName = getEventSubscriptionCommandName();
        ApiUtil.printTrace(device.get_adm_dev().name() + ".command_inout(\"" +
                    cmdName + "\")");
        DeviceData argout =
                device.get_adm_dev().command_inout(cmdName, argin);
        ApiUtil.printTrace("    command_inout done.");

        //	And then connect to device
        checkDeviceConnection(device, attribute, argout, event_name);
    }
    //===============================================================
    //===============================================================
    public int subscribe_event(DeviceProxy device,
                               String attribute,
                               int event,
                               CallBack callback,
                               String[] filters,
                               boolean stateless)
            throws DevFailed {
        return subscribe_event(device, attribute, event, callback, -1, filters, stateless);
    }

    //===============================================================
    //===============================================================
    public int subscribe_event(DeviceProxy device,
                               String attribute,
                               int event,
                               int max_size,
                               String[] filters,
                               boolean stateless)
            throws DevFailed {
        return subscribe_event(device, attribute, event, null, max_size, filters, stateless);
    }

    //===============================================================
    //===============================================================
    public int subscribe_event(DeviceProxy device,
                               String attribute,
                               int event,
                               CallBack callback,
                               int max_size,
                               String[] filters,
                               boolean stateless)
            throws DevFailed {
        //	Set the event name;
        String event_name = eventNames[event];
        ApiUtil.printTrace("=============> subscribing for " + device.name() + "/" +
                attribute + "/" +event_name);

        //	Check if already connected
        checkIfAlreadyConnected(device, attribute, event_name, callback, max_size, stateless);

        //	if no callback (null), create EventQueue
        if (callback == null && max_size >= 0) {
            //	Check if already created (in case of reconnection stateless mode)
            if (device.getEventQueue() == null)
                if (max_size > 0)
                    device.setEventQueue(new EventQueue(max_size));
                else
                    device.setEventQueue(new EventQueue());
        }

        String device_name = device.name();
        String callback_key = device_name.toLowerCase() + "/" + attribute + "." + event_name;
        try {
            //	Inform server that we want to subscribe and try to connect
            ApiUtil.printTrace("calling callEventSubscriptionAndConnect() method");
            callEventSubscriptionAndConnect(device, attribute.toLowerCase(), event_name);
            ApiUtil.printTrace("call callEventSubscriptionAndConnect() method done");
        } catch (DevFailed e) {
            if (!stateless || e.errors[0].desc.equals("Command ZmqEventSubscriptionChange not found"))
                throw e;
            else {
                //	Build Event CallBack Structure and add it to map
                subscribe_event_id++;
                EventCallBackStruct new_event_callback_struct =
                        new EventCallBackStruct(device,
                                attribute,
                                event_name,
                                "",
                                callback,
                                max_size,
                                subscribe_event_id,
                                event,
//                                "",
//                                -1,
                                filters,
                                false);
                failed_event_callback_map.put(callback_key, new_event_callback_struct);
                return subscribe_event_id;
            }
        }

        //	Prepare filters for heartbeat events on channel_name
        String channel_name = device_channel_map.get(device_name);
        EventChannelStruct event_channel_struct = channel_map.get(channel_name);
        event_channel_struct.last_subscribed = System.currentTimeMillis();

        //	Check if a new event or a re-trying one
        int evnt_id;
        EventCallBackStruct failed_struct = failed_event_callback_map.get(callback_key);
        if (failed_struct == null) {
            //	It is a new one
            subscribe_event_id++;
            evnt_id = subscribe_event_id;
        } else
            evnt_id = failed_struct.id;

        //	Build Event CallBack Structure if any
        EventCallBackStruct new_event_callback_struct =
                new EventCallBackStruct(device,
                        attribute,
                        event_name,
                        channel_name,
                        callback,
                        max_size,
                        evnt_id,
                        event,
//                        constraint_expr,
//                        filter_id,
                        filters,
                        true);
        setAdditionalInfoToEventCallBackStruct(new_event_callback_struct,
                device_name, attribute, event_name, filters, event_channel_struct);
        event_callback_map.put(callback_key, new_event_callback_struct);


        //	Thread to read the attribute by a simple synchronous call and
        //	force callback execution after release monitor.
        //	This is necessary for the first point in "change" mode,
        //	but it is not necessary to be serialized in case of
        //	read attribute or callback execution a little bit long.
        if ((event == CHANGE_EVENT) ||
                (event == PERIODIC_EVENT) ||
                (event == QUALITY_EVENT) ||
                (event == ARCHIVE_EVENT) ||
                (event == USER_EVENT) ||
                (event == ATT_CONF_EVENT)) {
            new PushAttrValueLater(new_event_callback_struct).start();
        }
        return evnt_id;
    }
    //===============================================================
    /**
     * Try to connect if it failed at subscribe
     */
    //===============================================================
    static void subscribeIfNotDone() {
        Enumeration callback_structs = failed_event_callback_map.elements();
        while (callback_structs.hasMoreElements()) {
            EventCallBackStruct eventCallBackStruct =
                    (EventCallBackStruct) callback_structs.nextElement();
            String callbackKey = eventCallBackStruct.device.name().toLowerCase() +
                    "/" + eventCallBackStruct.attr_name + "." + eventCallBackStruct.event_name;

            if (eventCallBackStruct.consumer!=null) {
                try {
                    subscribeIfNotDone(eventCallBackStruct, callbackKey);
                } catch (DevFailed e) {
                    //	Send error to callback
                    sendErrorToCallback(eventCallBackStruct, callbackKey, e);
                }
            }
            else {
                //System.err.println("====================================================");
                //System.err.println("callback_struct.consumer=null  for " + callbackKey);
                //  Never connected. Do not know if ZMQ or Notifd.
                //  ToDo invalid ZMQ
                /********************/
                if (EventConsumerUtil.isZmqLoadable()) {
                    try {
                        //  Try for zmq
                        eventCallBackStruct.consumer = ZmqEventConsumer.getInstance();
                        subscribeIfNotDone(eventCallBackStruct, callbackKey);
                        return;

                    }
                    catch (DevFailed e) {
                        if (e.errors[0].desc.equals("Command ZmqEventSubscriptionChange not found")) {
                            try {
                                //  Try for notifd
                                eventCallBackStruct.consumer = NotifdEventConsumer.getInstance();
                                subscribeIfNotDone(eventCallBackStruct, callbackKey);
                                return;
                            }
                            catch (DevFailed e2) {
                               System.err.println(e2);
                                //  reset if both have failed
                                eventCallBackStruct.consumer = null;
                                //	Send error to callback
                                sendErrorToCallback(eventCallBackStruct, callbackKey, e2);
                            }
                        }
                        else {
                            //	Send error to callback
                            eventCallBackStruct.consumer = null;
                            sendErrorToCallback(eventCallBackStruct, callbackKey, e);
                        }
                    }
                }
                else
                /***************************/
                {
                    try {
                        //  Try for notifd
                        eventCallBackStruct.consumer = NotifdEventConsumer.getInstance();
                        subscribeIfNotDone(eventCallBackStruct, callbackKey);
                        return;
                    }
                    catch (DevFailed e) {
                        System.err.println(e);
                        //	Send error to callback
                        sendErrorToCallback(eventCallBackStruct, callbackKey, e);
                    }
                }
            }
        }
    }
    //===============================================================
    //===============================================================
    private static void sendErrorToCallback(EventCallBackStruct cs, String callbackKey, DevFailed e) {

        int source = (cs.consumer instanceof NotifdEventConsumer)?
                EventData.NOTIFD_EVENT : EventData.ZMQ_EVENT;
        EventData eventData =
                new EventData(cs.device, callbackKey,
                        cs.event_name, source,
                        cs.event_type, null, null, null, e.errors);

        if (cs.use_ev_queue) {
            EventQueue ev_queue = cs.device.getEventQueue();
            ev_queue.insert_event(eventData);
        } else
            cs.callback.push_event(eventData);
    }
     //===============================================================
    //===============================================================
    private static void subscribeIfNotDone(EventCallBackStruct eventCallBackStruct,
                                          String callbackKey) throws DevFailed{

        eventCallBackStruct.consumer.subscribe_event(
                eventCallBackStruct.device,
                eventCallBackStruct.attr_name,
                eventCallBackStruct.event_type,
                eventCallBackStruct.callback,
                eventCallBackStruct.max_size,
                eventCallBackStruct.filters,
                false);
        failed_event_callback_map.remove(callbackKey);
    }
    //===============================================================
    //===============================================================
    static EventCallBackStruct getCallBackStruct(Hashtable map, int id) {
        Enumeration keys = map.keys();
        while (keys.hasMoreElements()) {
            String name = (String) keys.nextElement();
            EventCallBackStruct callback_struct =
                    (EventCallBackStruct) map.get(name);
            if (callback_struct.id == id)
                return callback_struct;
        }
        return null;
    }

    //===============================================================
    //===============================================================
    private void removeCallBackStruct(Hashtable map, EventCallBackStruct cb_struct) throws DevFailed {
        removeFilters(cb_struct);
        String callback_key = cb_struct.device.name().toLowerCase() +
                "/" + cb_struct.attr_name + "." + cb_struct.event_name;
        map.remove(callback_key);
    }

    //===============================================================
    //===============================================================
    public void unsubscribe_event(int event_id) throws DevFailed {
        //	Get callback struct for event ID
        EventCallBackStruct callbackStruct =
                getCallBackStruct(event_callback_map, event_id);
        if (callbackStruct != null) {
            removeCallBackStruct(event_callback_map, callbackStruct);
            unsubscribeTheEvent(callbackStruct);
        }
        else {
            //	If not found check if in failed map
            callbackStruct =
                    getCallBackStruct(failed_event_callback_map, event_id);
            if (callbackStruct != null)
                removeCallBackStruct(failed_event_callback_map, callbackStruct);
            else
                Except.throw_event_system_failed("API_EventNotFound",
                        "Failed to unsubscribe event, the event id (" + event_id +
                                ") specified does not correspond with any known one",
                        "EventConsumer.unsubscribe_event()");
        }
    }

    //===============================================================
    /*
     * Re subscribe event selected by name
     */
    //===============================================================
     void reSubscribeByName(EventChannelStruct event_channel_struct, String name) {
        Enumeration callback_structs = event_callback_map.elements();
        while (callback_structs.hasMoreElements()) {
            EventCallBackStruct callback_struct = (EventCallBackStruct) callback_structs.nextElement();
            if (callback_struct.channel_name.equals(name)) {
                reSubscribe(event_channel_struct, callback_struct);
            }
        }
    }
    //===============================================================
    /*
     * Push event containing exception
     */
    //===============================================================
    void pushReceivedException(EventChannelStruct event_channel_struct, EventCallBackStruct callback_struct, DevError error) {
        try {
            if (event_channel_struct != null) {
                if (event_channel_struct.consumer instanceof NotifdEventConsumer) {
                    if (!callback_struct.filter_ok) {
                        callback_struct.filter_id = NotifdEventConsumer.getInstance().add_filter_for_channel(
                                event_channel_struct, callback_struct.filter_constraint);
                        callback_struct.filter_ok = true;
                    }
                }
            } else
                return;

            int eventSource = (event_channel_struct.consumer instanceof NotifdEventConsumer)?
                    EventData.NOTIFD_EVENT : EventData.ZMQ_EVENT;
            DevError[] errors = {error};
            String domain_name = callback_struct.device.name() + "/" + callback_struct.attr_name.toLowerCase();
            EventData event_data =
                    new EventData(event_channel_struct.adm_device_proxy,
                            domain_name, callback_struct.event_name, callback_struct.event_type,
                            eventSource, null, null, null, errors);

            CallBack callback = callback_struct.callback;
            event_data.device = callback_struct.device;
            event_data.name = callback_struct.device.name();
            event_data.event = callback_struct.event_name;

            if (callback_struct.use_ev_queue) {
                EventQueue ev_queue = callback_struct.device.getEventQueue();
                ev_queue.insert_event(event_data);
            } else
                callback.push_event(event_data);

        } catch (DevFailed e) { /* */ }
    }
    //===============================================================
    /*
     * Read attribute and push result as event.
     */
    //===============================================================
    void readAttributeAndPush(EventChannelStruct event_channel_struct, EventCallBackStruct callback_struct) {
        //	Check if known event name
        boolean found = false;
        for (int i = 0; !found && i < eventNames.length; i++)
            found = callback_struct.event_name.equals(eventNames[i]);
        if (!found)
            return;

        //	Else do the synchronous call
        DeviceAttribute da = null;
        AttributeInfoEx info = null;
        DevError[] err = null;
        String domain_name = callback_struct.device.name() + "/" + callback_struct.attr_name;
        boolean old_transp = callback_struct.device.get_transparency_reconnection();
        callback_struct.device.set_transparency_reconnection(true);
        try {
            if (callback_struct.event_name.equals(eventNames[ATT_CONF_EVENT]))
                info = callback_struct.device.get_attribute_info_ex(callback_struct.attr_name);
            else
                da = callback_struct.device.read_attribute(callback_struct.attr_name);

            // The reconnection worked fine. The heartbeat should come back now,
            // when the notifd has not closed the connection.
            // Increase the counter to detect when the heartbeat is not coming back.
            event_channel_struct.has_notifd_closed_the_connection++;
        } catch (DevFailed e) {
            err = e.errors;
        }
        callback_struct.device.set_transparency_reconnection(old_transp);
        int eventSource = (event_channel_struct.consumer instanceof NotifdEventConsumer)?
                EventData.NOTIFD_EVENT : EventData.ZMQ_EVENT;
        EventData event_data =
                new EventData(callback_struct.device, domain_name,
                        callback_struct.event_name, eventSource,
                        callback_struct.event_type, da, info, null, err);

        if (callback_struct.use_ev_queue) {
            EventQueue ev_queue = callback_struct.device.getEventQueue();
            ev_queue.insert_event(event_data);
        } else
            callback_struct.callback.push_event(event_data);
    }



    //===============================================================
    /**
     * Thread to read the attribute by a simple synchronous call and
     * force callback execution after release monitor.
     * This is necessary for the first point in "change" mode,
     * but it is not necessary to be serialized in case of
     * read attribute or callback execution a little bit long.
     */
    //===============================================================
    class PushAttrValueLater extends Thread {
        private EventCallBackStruct cb_struct;

        //===============================================================
        PushAttrValueLater(EventCallBackStruct cb_struct) {
            this.cb_struct = cb_struct;
        }

        //===============================================================
        public void run() {
            //	Then read attribute
            DeviceAttribute deviceAttribute = null;
            AttributeInfoEx info = null;
            DevError[] err = null;
            String eventName = cb_struct.device.name() + "/" + cb_struct.attr_name.toLowerCase();
            try {
                if (cb_struct.event_type==ATT_CONF_EVENT) {
                    info = cb_struct.device.get_attribute_info_ex(cb_struct.attr_name);
                }
                else {
                    deviceAttribute = cb_struct.device.read_attribute(cb_struct.attr_name);
                }
            } catch (DevFailed e) {
                err = e.errors;
            }

            //	And push value
            int eventSource = (cb_struct.consumer instanceof NotifdEventConsumer)?
                    EventData.NOTIFD_EVENT : EventData.ZMQ_EVENT;
            EventData event_data =
                    new EventData(cb_struct.device,
                            eventName,
                            cb_struct.event_name,
                            cb_struct.event_type,
                            eventSource,
                            deviceAttribute, info, null, err);
            if (cb_struct.use_ev_queue) {
                EventQueue ev_queue = cb_struct.device.getEventQueue();
                ev_queue.insert_event(event_data);
            } else
                cb_struct.callback.push_event(event_data);
            cb_struct.setSynchronousDone(true);
        }
    }
    //===============================================================
    //===============================================================





    //===============================================================
    //===============================================================
    protected class ConnectionStructure {
        String      tangoHost;
        String      channelName;
        String      attributeName;
        String      deviceName;
        String      eventName;
        Database    dbase;
        DeviceData  deviceData = null;
        boolean     reconnect = false;
        //===========================================================
        ConnectionStructure(String tangoHost,
                            String channelName,
                            String deviceName,
                            String attributeName,
                            String eventName,
                            Database dbase,
                            DeviceData deviceData,
                            boolean reconnect) {
            this.tangoHost      = tangoHost;
            this.channelName    = channelName;
            this.deviceName     = deviceName;
            this.attributeName  = attributeName;
            this.eventName      = eventName;
            this.dbase          = dbase;
            this.deviceData     = deviceData;
            this.reconnect      = reconnect;
        }
        //===========================================================
        ConnectionStructure(String tangoHost, String name, Database dbase, boolean reconnect) {
            this(tangoHost, name, null, null, null, dbase, null, reconnect);
        }
        //===========================================================
        public String toString() {
            return "channel name: " + channelName +
                 "\ndbase:        " + dbase +
                 "\nreconnect:    " + reconnect;
        }
        //===========================================================
    }
    //===============================================================
    //===============================================================
}
