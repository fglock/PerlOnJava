use strict;
use warnings;

print "1..1\n";

eval <<'CODE';
BEGIN { $^H = 0x10000 }
qr/\(?{/
CODE

print $@ =~ /Constant\(qq\) unknown/
    ? "ok 1 - HINT_NEW_RE preserves the legacy qr diagnostic\n"
    : "not ok 1 - HINT_NEW_RE preserves the legacy qr diagnostic: $@\n";
