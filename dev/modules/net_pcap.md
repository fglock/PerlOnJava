# Net::Pcap Support for PerlOnJava

## Scope

Net::Pcap 0.21 is an XS binding to libpcap. PerlOnJava provides a portable
offline reader for the API used by Net::Frame::Dump::Offline. It supports
classic pcap files in both byte orders, microsecond and nanosecond timestamps,
and pcapng section, interface, enhanced-packet, packet, and simple-packet
blocks. Live capture is explicitly unsupported.

The implementation is bundled code rather than a distribution preference, so
all CPAN consumers can use the same offline descriptor API.

## Progress Tracking

### Current Status: Offline capture support complete

### Completed Phases

- [x] Offline descriptor and classic pcap reader (2026-08-08)
  - Added open, metadata, packet iteration, loop, and close operations
  - Supports both byte orders and microsecond/nanosecond timestamp formats
- [x] pcapng reader (2026-08-08)
  - Added section/interface tracking and the three packet block formats
  - Honors per-interface timestamp-resolution options
- [x] Net::Frame compatibility layer (2026-08-08)
  - Added empty filter compilation/installation and exported pcap aliases
  - Added focused bundled-module tests

### Next Steps

1. Add BPF evaluation if a consumer needs non-empty offline filters
2. Implement live capture only through a portable Java packet-capture library
3. Expand dump-file writing when a bundled consumer requires it

### Open Questions

- Which Java capture library should back live interfaces without introducing
  platform-specific native setup for ordinary offline consumers

## Related Documents

- `docs/guides/module-porting.md`
- `dev/design/patch-and-cpan-prefs-layout.md`
