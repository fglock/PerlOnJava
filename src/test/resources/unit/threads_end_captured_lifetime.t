use strict;
use warnings;

use threads;

print "1..1\n";
our $destroyed = 0;

{
    package ThreadEndCaptureGuard;

    sub new { bless {}, $_[0] }
    sub value { 42 }
    sub DESTROY { ++$main::destroyed }
}

my $instance = ThreadEndCaptureGuard->new;
sub captured_owner { $instance->value }

END {
    my $ok = !$destroyed && captured_owner() == 42;
    print($ok ? "ok 1" : "not ok 1",
        " - captured package lexical remains alive through END\n");
}

threads->create(sub { captured_owner(); return 1 });
