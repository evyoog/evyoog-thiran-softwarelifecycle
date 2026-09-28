package com.vyoog.trace;

import java.util.UUID;

/** One node along a traversed path (VYB-0145 AC2: results carry the path). */
public record TraceHop(TraceObjectType type, UUID id) {

    /** Parses one "TYPE:uuid" element out of the recursive query's path array. */
    static TraceHop parse(String token) {
        int sep = token.indexOf(':');
        return new TraceHop(TraceObjectType.valueOf(token.substring(0, sep)),
                             UUID.fromString(token.substring(sep + 1)));
    }
}
