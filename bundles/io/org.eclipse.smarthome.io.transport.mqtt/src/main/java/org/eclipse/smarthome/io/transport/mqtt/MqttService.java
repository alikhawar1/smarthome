/**
 * Copyright (c) 2014,2018 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.smarthome.io.transport.mqtt;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import javax.naming.ConfigurationException;

import org.apache.commons.lang.StringUtils;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.smarthome.core.events.EventPublisher;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MQTT Service for creating new connections to MQTT brokers from the Smart Home configuration file and registering
 * message publishers and subscribers. This service is the main entry point for all bundles wanting to use the MQTT
 * transport.
 *
 * @author David Graeff - Added/Removed observer interface, Add/Remove/Enumerate broker connections.
 * @author Davy Vanherbergen
 * @author Markus Rathgeb - Synchronize access to broker connections
 */
@Component(immediate = true, service = {
        MqttService.class }, configurationPid = "org.eclipse.smarthome.mqtt", property = {
                "service.pid=org.eclipse.smarthome.mqtt" })
@NonNullByDefault
public class MqttService {
    public static class Config {
        public final @Nullable String name;
        public final @Nullable String url;
        public final @Nullable String user;
        public final @Nullable String pwd;
        public final @Nullable String clientId;
        public final @Nullable Integer keepAlive;
        public final @Nullable Integer qos;
        public final @Nullable Boolean retain;
        public final @Nullable String lwt;
        public final @Nullable String lwtTopic;
        public final byte @Nullable [] lwtMessage;
        public final @Nullable Integer lwtQos;
        public final @Nullable Boolean lwtRetain;

        public Config(final Map<String, String> cfg) {
            name = cfg.get(NAME_PROPERTY);
            url = cfg.get("url");
            user = cfg.get("user");
            pwd = cfg.get("pwd");
            clientId = cfg.get("clientId");
            keepAlive = asInt(cfg.get("keepAlive"));
            qos = asInt(cfg.get("qos"));
            retain = asBool(cfg.get("retain"));

            // Check for Last Will and Testament
            lwt = cfg.get("lwt");

            // Inspect explicit given LWT values
            lwtTopic = cfg.get("lwtTopic");
            String tmp = cfg.get("lwtMessage");
            if (tmp != null) {
                lwtMessage = tmp.getBytes(StandardCharsets.UTF_8);
            } else {
                lwtMessage = null;
            }
            Integer tmpInt = asInt(cfg.get("lwtQos"));
            if (tmpInt != null && tmpInt >= 0 && tmpInt <= 2) {
                lwtQos = tmpInt;
            } else {
                lwtQos = null;
            }
            lwtRetain = asBool(cfg.get("lwtRetain"));
        }

        private static @Nullable Integer asInt(final @Nullable String value) {
            if (value == null || value.isEmpty()) {
                return null;
            }
            try {
                return Integer.valueOf(value);
            } catch (final NumberFormatException ex) {
                return null;
            }
        }

        private static @Nullable Boolean asBool(final @Nullable String value) {
            if (value == null || value.isEmpty()) {
                return null;
            }
            return Boolean.valueOf(value);
        }
    }

    private static final String NAME_PROPERTY = "name";
    private final Logger logger = LoggerFactory.getLogger(MqttService.class);
    private final Map<String, MqttBrokerConnection> brokerConnections = new ConcurrentHashMap<String, MqttBrokerConnection>();
    private final List<MqttBrokersObserver> brokersObservers = new CopyOnWriteArrayList<>();

    @Deprecated
    private @Nullable EventPublisher eventPublisher;

    /**
     * The expected service configuration looks like this:
     *
     * broker1.name=Some name
     * broker1.url=tcp://123.123.123.132
     *
     * broker2.qos=2
     * broker2.url=ssl://111.222.333.444
     *
     * @param properties Service configuration
     * @return A 'list' of broker configurations as key-value maps. A configuration map at least contains a "name".
     */
    public Map<String, Config> extractBrokerConfigurations(Map<String, Object> properties) {
        Map<String, Map<String, String>> configPerBroker = new HashMap<String, Map<String, String>>();
        for (Entry<String, Object> entry : properties.entrySet()) {
            String key = entry.getKey();
            // ignore the non-broker properties
            if (key.equals("service.pid") || key.equals("objectClass") || key.equals("component.name")
                    || key.equals("component.id")) {
                continue;
            }

            if (!(entry.getValue() instanceof String)) {
                logger.warn("Unexpected value in broker configuration {}:{}", entry.getKey(), entry.getValue());
                continue;
            }

            String value = (String) entry.getValue();

            String[] subkeys = key.split("\\.");
            if (subkeys.length != 2 || StringUtils.isBlank(value)) {
                logger.debug("MQTT Broker property '{}={}' should have the format 'broker.propertykey=value'", key,
                        value);
                continue;
            }
            String brokername = subkeys[0].toLowerCase();

            Map<String, String> brokerConfig = configPerBroker.get(brokername);
            if (brokerConfig == null) {
                brokerConfig = new HashMap<>();
                configPerBroker.put(brokername, brokerConfig);
                brokerConfig.put(NAME_PROPERTY, brokername);
            }

            brokerConfig.put(subkeys[1], value);
        }

        return configPerBroker.entrySet().stream()
                .collect(Collectors.toMap(entry -> entry.getKey(), entry -> new Config(entry.getValue())));
    }

    /**
     * Create broker connections based on the service configuration. This will disconnect and
     * discard all existing textual configured brokers.
     */
    @Modified
    public void modified(Map<String, Object> config) {
        // Disconnect and discard existing brokers
        Iterator<Map.Entry<String, MqttBrokerConnection>> it = brokerConnections.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, MqttBrokerConnection> entry = it.next();
            MqttBrokerConnection connection = entry.getValue();
            if (connection.isTextualConfiguredBroker()) {
                logger.debug("Received new Mqtt configuration: Close connection to {}:{}", connection.getName(),
                        connection.getClientId());
                connection.close();
                it.remove();
            }
        }

        // load broker configurations from configuration file
        if (config == null || config.isEmpty()) {
            return;
        }

        Map<String, Config> brokerConfigs = extractBrokerConfigurations(config);

        for (Config brokerConfig : brokerConfigs.values()) {
            try {
                final MqttBrokerConnection conn = addBrokerConnection(brokerConfig);
                if (conn == null) {
                    logger.warn("MqttBroker connection name already present.");
                    continue;
                }
                conn.start();
            } catch (ConfigurationException e) {
                logger.warn("MqttBroker connection configuration faulty: {}", e.getMessage());
            } catch (MqttException e) {
                logger.warn("MqttBroker start failed: {}", e.getMessage(), e);
            }
        }
    }

    @Activate
    public void activate(Map<String, Object> config) {
        logger.debug("Starting MQTT Service...");
        modified(config);
    }

    @Deactivate
    public void deactivate() {
        logger.debug("Stopping MQTT Service...");
        for (final MqttBrokerConnection conn : brokerConnections.values()) {
            conn.close();
        }
        brokerConnections.clear();
    }

    /**
     * Add a listener to get notified of new/removed brokers.
     *
     * @param observer The observer
     */
    public void addBrokersListener(MqttBrokersObserver observer) {
        brokersObservers.add(observer);
    }

    /**
     * Remove a listener and don't get notified of new/removed brokers anymore.
     *
     * @param observer The observer
     */
    public void removeBrokersListener(MqttBrokersObserver observer) {
        brokersObservers.remove(observer);
    }

    /**
     * Return true if a broker listener has been added via addBrokersListener().
     */
    public boolean hasBrokerObservers() {
        return !brokersObservers.isEmpty();
    }

    /**
     * Lookup an broker connection by name.
     *
     * @param brokerName to look for.
     * @return existing connection or null
     */
    public @Nullable MqttBrokerConnection getBrokerConnection(String brokerName) {
        synchronized (brokerConnections) {
            return brokerConnections.get(brokerName.toLowerCase());
        }
    }

    /**
     * Adds a broker to the service. The broker connection will not be altered (started/stopped),
     * by adding it to the service.
     *
     * The broker connection will be identified by its name. The name must be unique within the service.
     *
     * @param connection The broker connection object
     * @return Return true if the connection could be added successfully, return false if there is already
     *         an existing connection with the same name.
     */
    public boolean addBrokerConnection(MqttBrokerConnection connection) {
        synchronized (brokerConnections) {
            final String brokerID = connection.getName().toLowerCase();
            if (brokerConnections.containsKey(brokerID)) {
                return false;
            }
            brokerConnections.put(brokerID, connection);
            for (MqttBrokersObserver o : brokersObservers) {
                o.brokerAdded(connection);
            }
        }
        return true;
    }

    /**
     * Add a broker by a configuration.
     *
     * <p>
     * You need to provide at least a "name" and an "url".
     * Additional properties are "user","pwd","qos","retain","lwt","keepAlive","clientId", please read the
     * service configuration documentation for a detailed description.
     *
     * @param cfg The configuration key-value map.
     * @return Returns the created broker connection or null if there is already a connection with the same name.
     * @throws ConfigurationException Most likely your provided name and url are invalid.
     * @throws MqttException
     */
    public @Nullable MqttBrokerConnection addBrokerConnection(Config cfg) throws ConfigurationException, MqttException {
        // Extract mandatory fields
        String brokerID = cfg.name;
        if (brokerID == null || brokerID.isEmpty()) {
            throw new ConfigurationException("MQTT Broker property 'name' is not provided");
        }
        brokerID = brokerID.toLowerCase();

        final String brokerURL = cfg.url;
        if (brokerURL == null || brokerURL.isEmpty()) {
            throw new ConfigurationException("MQTT Broker property 'url' is not provided");
        }

        // Add the connection
        MqttBrokerConnection connection;
        synchronized (brokerConnections) {
            connection = brokerConnections.get(brokerID);
            if (connection != null) {
                return null;
            }
            connection = new MqttBrokerConnection(brokerID, brokerURL, true);
            brokerConnections.put(brokerID, connection);
        }

        // Extract further configurations
        connection.setCredentials(cfg.user, cfg.pwd);
        connection.setClientId(cfg.clientId);
        if (cfg.keepAlive != null) {
            connection.setKeepAliveInterval(cfg.keepAlive);
        }
        if (cfg.qos != null) {
            connection.setQos(cfg.qos);
        }
        if (cfg.retain != null) {
            connection.setRetain(cfg.retain);
        }
        MqttWillAndTestament will = MqttWillAndTestament.fromString(cfg.lwt, cfg.lwtTopic, cfg.lwtMessage, cfg.lwtQos,
                cfg.lwtRetain);
        if (will != null) {
            logger.debug("Setting last will: {}", will);
            connection.setLastWill(will);
        }

        for (MqttBrokersObserver o : brokersObservers) {
            o.brokerAdded(connection);
        }

        return connection;
    }

    /**
     * Remove a broker connection
     *
     * @param connection The broker connection
     */
    public void removeBrokerConnection(MqttBrokerConnection connection) {
        synchronized (brokerConnections) {
            if (brokerConnections.remove(connection.getName().toLowerCase(), connection)) {
                for (MqttBrokersObserver o : brokersObservers) {
                    o.brokerRemoved(connection);
                }
            }
        }
    }

    /**
     * Remove a broker connection by name
     *
     * @param brokerName The broker name
     * @return Returns the removed broker connection, or null if there was none with the given name.
     */
    public @Nullable MqttBrokerConnection removeBrokerConnection(String brokerName) {
        synchronized (brokerConnections) {
            MqttBrokerConnection connection = brokerConnections.remove(brokerName.toLowerCase());
            if (connection != null) {
                for (MqttBrokersObserver o : brokersObservers) {
                    o.brokerRemoved(connection);
                }
            }
            return connection;
        }
    }

    /**
     * Returns all currently configured brokers, textual as well as dynamically added ones.
     */
    public Collection<MqttBrokerConnection> getAllBrokerConnections() {
        return brokerConnections.values();
    }

    /**
     * Register a new connection observer that could act on MQTT connection changes.
     * This is deprecated, please register on the broker connection object instead.
     *
     * @deprecated
     * @param brokerName Name of the broker that connection should be observed.
     * @param connectionObserver The connection observer that should be informed about connection changes.
     */
    @Deprecated
    public void registerConnectionObserver(String brokerName, MqttConnectionObserver connectionObserver) {
        MqttBrokerConnection brokerConnection = getBrokerConnection(brokerName);
        if (brokerConnection != null) {
            brokerConnection.addConnectionObserver(connectionObserver);
        }
    }

    /**
     * Unregister an existing connection observer.
     *
     * @deprecated
     * @param brokerName Name of the broker that connection has been observed.
     * @param connectionObserver The connection observer that should not be informed anymore.
     */
    @Deprecated
    public void unregisterConnectionObserver(String brokerName, MqttConnectionObserver connectionObserver) {
        MqttBrokerConnection brokerConnection = getBrokerConnection(brokerName);
        if (brokerConnection != null) {
            brokerConnection.removeConnectionObserver(connectionObserver);
        }
    }

    /**
     * Register a new message consumer which can process messages received on
     *
     * @deprecated
     * @param brokerName Name of the broker on which to listen for messages.
     * @param mqttMessageConsumer Consumer which will process any received message.
     */
    @Deprecated
    public void registerMessageConsumer(String brokerName, MqttMessageConsumer mqttMessageConsumer) {
        try {
            MqttBrokerConnection brokerConnection = getBrokerConnection(brokerName);
            if (brokerConnection != null) {
                brokerConnection.addConsumer(mqttMessageConsumer);
                mqttMessageConsumer.setEventPublisher(eventPublisher);
            }
        } catch (MqttException e) {
            logger.debug("Consumer could not be activated", e);
        }
    }

    /**
     * Unregisters an existing message consumer.
     *
     * @deprecated
     * @param mqttMessageConsumer Consumer which needs to be unregistered.
     */
    @Deprecated
    public void unregisterMessageConsumer(String brokerName, MqttMessageConsumer mqttMessageConsumer) {
        MqttBrokerConnection brokerConnection = getBrokerConnection(brokerName);
        if (brokerConnection != null) {
            brokerConnection.removeConsumer(mqttMessageConsumer);
        }
    }

    /**
     * @deprecated
     */
    @Deprecated
    public void registerMessageProducer(String brokerName, MqttMessageProducer commandPublisher) {
        MqttBrokerConnection brokerConnection = getBrokerConnection(brokerName);
        if (brokerConnection != null) {
            brokerConnection.addProducer(commandPublisher);
        }
    }

    /**
     * @deprecated
     */
    @Deprecated
    public void unregisterMessageProducer(String brokerName, MqttMessageProducer commandPublisher) {
        MqttBrokerConnection brokerConnection = getBrokerConnection(brokerName);
        if (brokerConnection != null) {
            brokerConnection.removeProducer(commandPublisher);
        }
    }

    /**
     * Set the publisher to use for publishing SmartHome updates.
     * This is deprecated, please use declarative services to add your
     * own copy of EventPublisher to your bundle.
     *
     * @deprecated
     * @param eventPublisher EventPublisher
     */
    @Deprecated
    @Reference(cardinality = ReferenceCardinality.MANDATORY, policy = ReferencePolicy.DYNAMIC)
    public void setEventPublisher(EventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    /**
     * Remove the publisher to use for publishing SmartHome updates.
     *
     * @deprecated
     * @param eventPublisher EventPublisher
     */
    @Deprecated
    public void unsetEventPublisher(EventPublisher eventPublisher) {
        this.eventPublisher = null;
    }
}
