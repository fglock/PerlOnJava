use strict;
use warnings;

print "1..1\n";

our @seen;

END {
    push @seen, 'first';
}

END {
    push @seen, 'second';
    require B;
    push @{ B::end_av()->object_2svref }, sub {
        push @seen, 'pushed';
        my $got = join ',', @seen;
        if ($got eq 'second,first,pushed') {
            print "ok 1 - B::end_av append runs after remaining END blocks\n";
        }
        else {
            print "not ok 1 - B::end_av append runs after remaining END blocks\n";
            print "# got: $got\n";
        }
    };
}
