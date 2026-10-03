package com.vyoog.integration.connector;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** VYB-0913: every {@link Connector} bean, by the connection key it is bound to. */
@Component
public class ConnectorRegistry {

    private final Map<String, Connector> byKey;

    public ConnectorRegistry(List<Connector> connectors) {
        this.byKey = connectors.stream().collect(Collectors.toMap(
            Connector::connectionKey, Function.identity(),
            (a, b) -> { throw new IllegalStateException("Two connectors are bound to connection \"" + a.connectionKey() + "\""); }));
    }

    public List<Connector> all() {
        return byKey.values().stream().sorted(java.util.Comparator.comparing(Connector::connectionKey)).toList();
    }

    public Optional<Connector> forConnection(String connectionKey) {
        return Optional.ofNullable(byKey.get(connectionKey));
    }
}
