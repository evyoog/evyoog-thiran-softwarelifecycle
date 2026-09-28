package com.vyoog.review;

import java.util.UUID;

public record ReviewParticipantInput(UUID userId, ReviewParticipantRole role) {}
