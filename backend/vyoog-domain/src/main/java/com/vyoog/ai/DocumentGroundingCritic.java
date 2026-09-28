package com.vyoog.ai;

import java.util.List;

/**
 * VYB-0667, agent 3 of 3: reads the draft description against the findings it was
 * supposed to be built from, and names every claim that isn't in them.
 *
 * <p>Its verdict is not advisory. An ungrounded draft is sent back to the synthesiser
 * once with the critic's list; whatever it still cannot support after that is stored
 * and shown next to the description, because a claim nothing in the document supports
 * is exactly the thing a reviewer needs to see rather than the thing to quietly drop.
 */
public interface DocumentGroundingCritic {

    record Critique(List<String> unsupportedClaims, String guidance) {

        public boolean grounded() {
            return unsupportedClaims == null || unsupportedClaims.isEmpty();
        }
    }

    Critique critique(String description, List<DocumentFinding> findings);

    String modelName();
}
