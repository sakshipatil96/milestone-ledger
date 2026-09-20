package dev.sakshi.milestoneledger.payments;

import java.util.UUID;

record ManualAllocationRequest(UUID demandId, String amountPaise, String reason) { }
