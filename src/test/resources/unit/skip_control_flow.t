#!/usr/bin/env perl
use strict;
use warnings;

# Minimal TAP without Test::More (we need this to work even when skip()/TODO are broken)
my $t = 0;
sub ok_tap {
    my ($cond, $name) = @_;
    $t++;
    print(($cond ? "ok" : "not ok"), " $t - $name\n");
}

# 1) Single frame
{
    my $out = '';
    sub skip_once { last SKIP }
    SKIP: {
        $out .= 'A';
        skip_once();
        $out .= 'B';
    }
    $out .= 'C';
    ok_tap($out eq 'AC', 'last SKIP exits SKIP block (single frame)');
}

# 2) Two frames, scalar context
{
    my $out = '';
    sub inner2 { last SKIP }
    sub outer2 { my $x = inner2(); return $x; }
    SKIP: {
        $out .= 'A';
        my $r = outer2();
        $out .= 'B';
    }
    $out .= 'C';
    ok_tap($out eq 'AC', 'last SKIP exits SKIP block (2 frames, scalar context)');
}

# 3) Two frames, void context
{
    my $out = '';
    sub innerv { last SKIP }
    sub outerv { innerv(); }
    SKIP: {
        $out .= 'A';
        outerv();
        $out .= 'B';
    }
    $out .= 'C';
    ok_tap($out eq 'AC', 'last SKIP exits SKIP block (2 frames, void context)');
}

print "1..$t\n";
