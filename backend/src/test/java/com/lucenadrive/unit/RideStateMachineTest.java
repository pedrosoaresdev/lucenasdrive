package com.lucenadrive.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lucenadrive.common.error.ApiException;
import com.lucenadrive.common.error.ErrorCode;
import com.lucenadrive.ride.ActorRole;
import com.lucenadrive.ride.RideStateMachine;
import com.lucenadrive.ride.RideStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RideStateMachineTest {

    @ParameterizedTest
    @CsvSource({
            "REQUESTED,SEARCHING_DRIVER,SYSTEM",
            "SEARCHING_DRIVER,ACCEPTED,DRIVER",
            "ACCEPTED,DRIVER_ARRIVING,DRIVER",
            "DRIVER_ARRIVING,DRIVER_ARRIVED,DRIVER",
            "DRIVER_ARRIVED,IN_PROGRESS,DRIVER",
            "IN_PROGRESS,COMPLETED,DRIVER",
            "SEARCHING_DRIVER,CANCELLED,PASSENGER",
            "DRIVER_ARRIVED,CANCELLED,PASSENGER",
            "IN_PROGRESS,CANCELLED,STAFF"})
    void allowsValidTransitions(RideStatus from, RideStatus to, ActorRole actor) {
        assertThat(RideStateMachine.canTransition(from, to, actor)).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
            "SEARCHING_DRIVER,IN_PROGRESS,DRIVER",
            "ACCEPTED,COMPLETED,DRIVER",
            "COMPLETED,CANCELLED,STAFF",
            "CANCELLED,SEARCHING_DRIVER,SYSTEM",
            "REQUESTED,ACCEPTED,DRIVER"})
    void rejectsInvalidTransitions(RideStatus from, RideStatus to, ActorRole actor) {
        assertThatThrownBy(() -> RideStateMachine.assertTransition(from, to, actor))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);
    }

    @Test
    void passengerCannotStartOrCompleteTrip() {
        assertThatThrownBy(() -> RideStateMachine.assertTransition(RideStatus.DRIVER_ARRIVED, RideStatus.IN_PROGRESS,
                ActorRole.PASSENGER)).extracting(e -> ((ApiException) e).code()).isEqualTo(ErrorCode.FORBIDDEN);
        assertThatThrownBy(() -> RideStateMachine.assertTransition(RideStatus.IN_PROGRESS, RideStatus.COMPLETED,
                ActorRole.PASSENGER)).extracting(e -> ((ApiException) e).code()).isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    void passengerCannotCancelTripInProgress() {
        assertThat(RideStateMachine.canTransition(RideStatus.IN_PROGRESS, RideStatus.CANCELLED, ActorRole.PASSENGER)).isFalse();
        assertThat(RideStateMachine.canTransition(RideStatus.IN_PROGRESS, RideStatus.CANCELLED, ActorRole.DRIVER)).isFalse();
    }
}
