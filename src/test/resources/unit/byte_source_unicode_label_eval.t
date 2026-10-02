use strict;
use warnings;
use utf8;
use feature 'unicode_eval';
LÁBEL: {
    my $source = "redo LÁBEL;";
    utf8::downgrade($source);
    eval $source;
    my $ok = $@ =~ /Unrecognized character/;
    print "1..1\n";
    print(($ok ? 'ok' : 'not ok'),
        " 1 - downgraded eval source rejects a Unicode label\n");
}
