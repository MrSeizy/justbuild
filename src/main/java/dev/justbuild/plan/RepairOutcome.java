package dev.justbuild.plan;

/** What happened to a repair, typed so player lines and the next critique never parse message text.
 * {@code ReviewCheck} produces only ACCEPTED and the REJECTED_TECHNICAL, REJECTED_REMOVAL and REJECTED_ROUTE values;
 * the review loop adds REJECTED_LIMIT and FAILED. */
public enum RepairOutcome { ACCEPTED, REJECTED_TECHNICAL, REJECTED_REMOVAL, REJECTED_ROUTE, REJECTED_LIMIT, FAILED }
