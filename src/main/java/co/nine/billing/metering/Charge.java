package co.nine.billing.metering;

import co.nine.billing.domain.Money;

import java.util.UUID;

/** The outcome of metering one event. {@code replayed} is true when the event had already been charged. */
public record Charge(UUID transactionId, Money amount, boolean replayed) {}
