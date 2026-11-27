package org.perlonjava.runtime.runtimetypes;

/** Optional counters retained for owner-ledger lifecycle regression tests. */
final class ReachabilityQueryStats {
    long rootQueries;
    long rootsSeeded;
    long edgesInspected;
    long nodesVisited;
    long snapshotsBuilt;
    long deferredBasesProcessed;
    long externalRootSnapshotsBuilt;
    long externalRootSnapshotEdgesInspected;
    long externalRootSnapshotNodesVisited;
    long liveRootSnapshotsBuilt;
    long liveRootSnapshotNodesVisited;
    long globalCodeCaptureRootsWalked;
    long unblessedAggregateDrainPositiveProofsCached;
    long scalarReachabilityQueries;
    long scalarReachabilityRootWalks;
    long codeOwnerSlotRestorations;
}
