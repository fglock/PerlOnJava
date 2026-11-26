use strict;
use warnings;
use Test::More tests => 1;

package CallerLineProbe;
sub throw {
    my $reported_line;
    {
        package DB;
        my $depth = 1;
        my @frame;
        while (@frame = caller($depth++)) {
            next if $frame[3] =~ /CallerLineProbe::throw/;
            $reported_line = $frame[2];
            last;
        }
    }
    die bless { line => $reported_line }, 'CallerLineProbe::Error';
}

package main;
my $eval_line = __LINE__ + 1;
eval {
    CallerLineProbe::throw();
};
my $reported_line = $@->{line};
is($reported_line, $eval_line,
    'caller reports the eval block line after skipping the throw frame');

done_testing;
