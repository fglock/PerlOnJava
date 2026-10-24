package org.perlonjava.runtime.runtimetypes;

/** Optional per-runtime counters for deterministic reachability cost tests. */
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
    long weakBackrefCaptureRootsWalked;
    long weakBackrefCaptureNodesVisited;
    long weakBackrefCaptureEdgesInspected;
    long weakBackrefCaptureSnapshotsBuilt;
    long unblessedAggregateDrainPositiveProofsCached;
    long scalarReachabilityQueries;
    long scalarReachabilityRootWalks;
    long aggregateOwnerRootProofs;
    long aggregateOwnerRootProofCacheHits;
}
