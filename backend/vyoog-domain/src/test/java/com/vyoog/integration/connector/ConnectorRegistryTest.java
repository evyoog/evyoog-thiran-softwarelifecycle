package com.vyoog.integration.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** VYB-0913 (F40): connectors are found by the connection they are bound to. */
class ConnectorRegistryTest {

    private static Connector connector(String key) {
        return new Connector() {
            public String connectionKey() { return key; }
            public String description() { return key + " connector"; }
            public Set<String> operations() { return Set.of(key + ".send"); }
        };
    }

    @Test
    void VYB0913_AC1_connectorsAreFoundByTheirConnectionKeyAndListedInOrder() {
        ConnectorRegistry registry = new ConnectorRegistry(List.of(connector("planning"), connector("alpha")));
        assertThat(registry.all()).extracting(Connector::connectionKey).containsExactly("alpha", "planning");
        assertThat(registry.forConnection("planning")).isPresent();
        assertThat(registry.forConnection("nope")).isEmpty();
    }

    @Test
    void VYB0913_AC1_twoConnectorsOnOneConnectionIsAStartupError() {
        assertThatThrownBy(() -> new ConnectorRegistry(List.of(connector("planning"), connector("planning"))))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("planning");
    }
}
