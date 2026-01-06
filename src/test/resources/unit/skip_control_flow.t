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

# 1) Single frame - SKIP
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

# 2) Two frames, scalar context - SKIP
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

# 3) Two frames, void context - SKIP
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

# 4) Single frame - TODO
{
    my $out = '';
    sub todo_once { last TODO }
    TODO: {
        $out .= 'A';
        todo_once();
        $out .= 'B';
    }
    $out .= 'C';
    ok_tap($out eq 'AC', 'last TODO exits TODO block (single frame)');
}

# 5) Two frames, scalar context - TODO
{
    my $out = '';
    sub inner_todo { last TODO }
    sub outer_todo { my $x = inner_todo(); return $x; }
    TODO: {
        $out .= 'A';
        my $r = outer_todo();
        $out .= 'B';
    }
    $out .= 'C';
    ok_tap($out eq 'AC', 'last TODO exits TODO block (2 frames, scalar context)');
}

# 6) Two frames, void context - TODO
{
    my $out = '';
    sub innerv_todo { last TODO }
    sub outerv_todo { innerv_todo(); }
    TODO: {
        $out .= 'A';
        outerv_todo();
        $out .= 'B';
    }
    $out .= 'C';
    ok_tap($out eq 'AC', 'last TODO exits TODO block (2 frames, void context)');
}

# 7) Single frame - CLEANUP
{
    my $out = '';
    sub cleanup_once { last CLEANUP }
    CLEANUP: {
        $out .= 'A';
        cleanup_once();
        $out .= 'B';
    }
    $out .= 'C';
    ok_tap($out eq 'AC', 'last CLEANUP exits CLEANUP block (single frame)');
}

# 8) Two frames, scalar context - CLEANUP
{
    my $out = '';
    sub inner_cleanup { last CLEANUP }
    sub outer_cleanup { my $x = inner_cleanup(); return $x; }
    CLEANUP: {
        $out .= 'A';
        my $r = outer_cleanup();
        $out .= 'B';
    }
    $out .= 'C';
    ok_tap($out eq 'AC', 'last CLEANUP exits CLEANUP block (2 frames, scalar context)');
}

# 9) Two frames, void context - CLEANUP
{
    my $out = '';
    sub innerv_cleanup { last CLEANUP }
    sub outerv_cleanup { innerv_cleanup(); }
    CLEANUP: {
        $out .= 'A';
        outerv_cleanup();
        $out .= 'B';
    }
    $out .= 'C';
    ok_tap($out eq 'AC', 'last CLEANUP exits CLEANUP block (2 frames, void context)');
}

print "1..$t\n";
