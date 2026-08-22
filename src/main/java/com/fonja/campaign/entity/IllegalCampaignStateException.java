package com.fonja.campaign.entity;

/**
 * A lifecycle transition was attempted from a state that does not allow it. Distinct from a plain
 * {@link IllegalStateException} so the service layer can map exactly this to a 409 without also
 * swallowing unrelated failures raised from inside a transition.
 */
public class IllegalCampaignStateException extends IllegalStateException {

    public IllegalCampaignStateException(String message) {
        super(message);
    }
}
