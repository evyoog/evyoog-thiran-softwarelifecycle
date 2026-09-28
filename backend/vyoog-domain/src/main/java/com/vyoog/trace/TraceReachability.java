package com.vyoog.trace;

import java.util.List;
import java.util.UUID;

/** One reachable node from a traversal, at the given depth, with the path that reached it. */
public record TraceReachability(TraceObjectType type, UUID id, int depth, List<TraceHop> path) {}
