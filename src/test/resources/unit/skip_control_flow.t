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

# 1) Single frame - MYLABEL
{
    my $out = '';
    sub test_once { last MYLABEL }
    MYLABEL: {
        $out .= 'A';
        test_once();
        $out .= 'B';
    }
    $out .= 'C';
    ok_tap($out eq 'AC', 'last MYLABEL exits MYLABEL block (single frame)');
}

# 2) Two frames, scalar context - MYLABEL
{
    my $out = '';
    sub inner2 { last MYLABEL }
    sub outer2 { my $x = inner2(); return $x; }
    MYLABEL: {
        $out .= 'A';
        my $r = outer2();
        $out .= 'B';
    }
    $out .= 'C';
    ok_tap($out eq 'AC', 'last MYLABEL exits MYLABEL block (2 frames, scalar context)');
}

# 3) Two frames, void context - MYLABEL
{
    my $out = '';
    sub innerv { last MYLABEL }
    sub outerv { innerv(); }
    MYLABEL: {
        $out .= 'A';
        outerv();
        $out .= 'B';
    }
    $out .= 'C';
    ok_tap($out eq 'AC', 'last MYLABEL exits MYLABEL block (2 frames, void context)');
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
