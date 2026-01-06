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

# 4) Single frame - LABEL2
{
    my $out = '';
    sub test2_once { last LABEL2 }
    LABEL2: {
        $out .= 'A';
        test2_once();
        $out .= 'B';
    }
    $out .= 'C';
    ok_tap($out eq 'AC', 'last LABEL2 exits LABEL2 block (single frame)');
}

# 5) Two frames, scalar context - LABEL2
{
    my $out = '';
    sub inner_label2 { last LABEL2 }
    sub outer_label2 { my $x = inner_label2(); return $x; }
    LABEL2: {
        $out .= 'A';
        my $r = outer_label2();
        $out .= 'B';
    }
    $out .= 'C';
    ok_tap($out eq 'AC', 'last LABEL2 exits LABEL2 block (2 frames, scalar context)');
}

# 6) Two frames, void context - LABEL2
{
    my $out = '';
    sub innerv_label2 { last LABEL2 }
    sub outerv_label2 { innerv_label2(); }
    LABEL2: {
        $out .= 'A';
        outerv_label2();
        $out .= 'B';
    }
    $out .= 'C';
    ok_tap($out eq 'AC', 'last LABEL2 exits LABEL2 block (2 frames, void context)');
}

# 7) Single frame - LABEL3
{
    my $out = '';
    sub test3_once { last LABEL3 }
    LABEL3: {
        $out .= 'A';
        test3_once();
        $out .= 'B';
    }
    $out .= 'C';
    ok_tap($out eq 'AC', 'last LABEL3 exits LABEL3 block (single frame)');
}

# 8) Two frames, scalar context - LABEL3
{
    my $out = '';
    sub inner_label3 { last LABEL3 }
    sub outer_label3 { my $x = inner_label3(); return $x; }
    LABEL3: {
        $out .= 'A';
        my $r = outer_label3();
        $out .= 'B';
    }
    $out .= 'C';
    ok_tap($out eq 'AC', 'last LABEL3 exits LABEL3 block (2 frames, scalar context)');
}

# 9) Two frames, void context - LABEL3
{
    my $out = '';
    sub innerv_label3 { last LABEL3 }
    sub outerv_label3 { innerv_label3(); }
    LABEL3: {
        $out .= 'A';
        outerv_label3();
        $out .= 'B';
    }
    $out .= 'C';
    ok_tap($out eq 'AC', 'last LABEL3 exits LABEL3 block (2 frames, void context)');
}

# 10) Stale marker bug - labeled block in eval leaves marker
{
    my $out = '';
    # This eval creates a labeled block that might leave a stale marker
    eval "\${\x{30cd}single:\x{30cd}colon} = 'test'";
    $out .= 'A';
    
    # This SKIP block should work normally, not be affected by stale marker
    MYLABEL: {
        $out .= 'B';
        $out .= 'C';
    }
    $out .= 'D';
    ok_tap($out eq 'ABCD', 'labeled block in eval does not leave stale marker');
}

print "1..$t\n";
