package com.loopers.config;

import com.navercorp.fixturemonkey.FixtureMonkey;
import com.navercorp.fixturemonkey.FixtureMonkeyBuilder;
import com.navercorp.fixturemonkey.api.introspector.ConstructorPropertiesArbitraryIntrospector;

public final class FixtureMonkeyConfig {
    private FixtureMonkeyConfig() {}

    public static FixtureMonkey create() {
        return builder().build();
    }

    public static FixtureMonkey create(long seed) {
        return builder().seed(seed).build();
    }

    private static FixtureMonkeyBuilder builder() {
        return FixtureMonkey.builder()
                .objectIntrospector(ConstructorPropertiesArbitraryIntrospector.INSTANCE);
    }
}
