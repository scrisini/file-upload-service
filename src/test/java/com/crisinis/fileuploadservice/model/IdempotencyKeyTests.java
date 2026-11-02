package com.crisinis.fileuploadservice.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class IdempotencyKeyTests {

    @Test
    void theSameKeyAlwaysMeansTheSameFile() {
        String id = new IdempotencyKey("7c9e6679-7425-40de-944b-e07fc1f90ae7").fileId();

        assertThat(new IdempotencyKey("7c9e6679-7425-40de-944b-e07fc1f90ae7").fileId()).isEqualTo(id);
        assertThat(new IdempotencyKey("7c9e6679-7425-40de-944b-e07fc1f90ae8").fileId()).isNotEqualTo(id);
        // a canonical UUID, so it passes the same checks as random ids
        assertThat(UUID.fromString(id).toString()).isEqualTo(id);
    }

    @Test
    void theIdIsNotTheKey() {
        assertThat(new IdempotencyKey("7c9e6679-7425-40de-944b-e07fc1f90ae7").fileId())
                .isNotEqualTo("7c9e6679-7425-40de-944b-e07fc1f90ae7");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "has space", "semi;colon", "slash/", "ünïcode", "x\n"})
    void refusesKeysOutsideTheAllowedCharacters(String key) {
        assertThatThrownBy(() -> new IdempotencyKey(key)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Idempotency-Key");
    }

    @Test
    void acceptsUpTo128Characters() {
        new IdempotencyKey("k".repeat(128));
        new IdempotencyKey("order-42:attempt.1_a");
        assertThatThrownBy(() -> new IdempotencyKey("k".repeat(129))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IdempotencyKey(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
